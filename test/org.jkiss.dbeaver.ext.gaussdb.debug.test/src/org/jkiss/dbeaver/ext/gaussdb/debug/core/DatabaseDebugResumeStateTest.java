/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.IProcess;
import org.jkiss.dbeaver.debug.DBGConstants;
import org.jkiss.dbeaver.debug.DBGEvent;
import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.debug.DBGSession;
import org.jkiss.dbeaver.debug.DBGStackFrame;
import org.jkiss.dbeaver.debug.core.model.DatabaseDebugTarget;
import org.jkiss.dbeaver.debug.core.model.DatabaseThread;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugController;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the production model across resumed, paused and transaction-completion states. */
class DatabaseDebugResumeStateTest {
    private DatabaseDebugTarget target;

    @AfterEach
    void closeTarget() throws Exception {
        if (target != null && !target.isTerminated()) {
            target.terminate();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {DBGEvent.CLIENT_REQUEST, DBGEvent.STEP_INTO, DBGEvent.STEP_OVER, DBGEvent.STEP_RETURN})
    void resumeEventClearsSuspensionAndCachedFrames(int detail) throws Exception {
        DBGSession session = resumableSession();
        DatabaseThread thread = pausedTarget(session);
        thread.rebuildStack(List.of(mock(DBGStackFrame.class)));
        assertEquals(1, thread.getStackFrames().length);

        target.handleDebugEvent(new DBGEvent(session, DBGEvent.RESUME, detail));

        assertFalse(target.isSuspended());
        assertFalse(thread.isSuspended());
        assertFalse(target.canResume());
        assertEquals(0, thread.getStackFrames().length);
    }

    @Test
    void nextSuspensionRestoresResumeAndReadsAFreshStack() throws Exception {
        DBGSession session = resumableSession();
        DatabaseThread thread = pausedTarget(session);
        thread.rebuildStack(List.of(mock(DBGStackFrame.class)));
        target.handleDebugEvent(new DBGEvent(session, DBGEvent.RESUME, DBGEvent.STEP_INTO));
        when(session.getStack()).thenAnswer(call -> List.of(mock(DBGStackFrame.class), mock(DBGStackFrame.class)));

        target.handleDebugEvent(new DBGEvent(session, DBGEvent.SUSPEND, DBGEvent.STEP_END));

        assertTrue(target.isSuspended());
        assertTrue(target.canResume());
        assertEquals(2, thread.getStackFrames().length);
        verify(session).getStack();
    }

    @Test
    void transactionCompletionDisablesResumeEvenWhileTheModelPublishesSuspension() throws Exception {
        DBGSession session = resumableSession();
        pausedTarget(session);
        when(session.isTransactionCompletionPending()).thenReturn(true);

        target.handleDebugEvent(new DBGEvent(session, DBGEvent.SUSPEND, DBGEvent.STEP_END));
        assertFalse(target.canResume());
        assertDoesNotThrow(target::resume);

        verify(session, never()).resume();
        assertTrue(target.isSuspended(), "A stale action must not turn transaction completion into a running target");
    }

    @Test
    void providerRejectingResumeMakesALateActionANoOp() throws Exception {
        DBGSession session = resumableSession();
        pausedTarget(session);
        when(session.canResume()).thenReturn(false);

        assertFalse(target.canResume());
        assertDoesNotThrow(target::resume);

        verify(session, never()).resume();
        assertTrue(target.isSuspended());
    }

    @Test
    void staleThreadActionPreservesTheCompletedTargetsCachedFrame() throws Exception {
        DBGSession session = resumableSession();
        DatabaseThread thread = pausedTarget(session);
        thread.rebuildStack(List.of(mock(DBGStackFrame.class)));
        when(session.isTransactionCompletionPending()).thenReturn(true);

        assertDoesNotThrow(thread::resume);

        verify(session, never()).resume();
        verify(session, never()).getStack();
        assertTrue(target.isSuspended());
        assertEquals(1, thread.getStackFrames().length);
    }

    @Test
    void aRunningTargetIgnoresASecondResumeRequest() throws Exception {
        DBGSession session = resumableSession();
        pausedTarget(session);
        target.handleDebugEvent(new DBGEvent(session, DBGEvent.RESUME, DBGEvent.STEP_INTO));

        assertDoesNotThrow(target::resume);

        verify(session, never()).resume();
        assertFalse(target.isSuspended());
    }

    @Test
    void acceptedResumeCallsTheProviderAndChangesTheModelToRunning() throws Exception {
        DBGSession session = resumableSession();
        DatabaseThread thread = pausedTarget(session);
        thread.rebuildStack(List.of(mock(DBGStackFrame.class)));
        doAnswer(call -> {
            target.handleDebugEvent(new DBGEvent(session, DBGEvent.RESUME, DBGEvent.CLIENT_REQUEST));
            return null;
        }).when(session).resume();

        target.resume();

        verify(session).resume();
        assertFalse(target.isSuspended());
        assertFalse(target.canResume());
        assertEquals(0, thread.getStackFrames().length);
    }

    @Test
    void anImmediateResumeThenSuspensionIsNotOverwrittenByLegacyFallback() throws Exception {
        DBGSession session = resumableSession();
        pausedTarget(session);
        doAnswer(call -> {
            target.handleDebugEvent(new DBGEvent(session, DBGEvent.RESUME, DBGEvent.CLIENT_REQUEST));
            target.handleDebugEvent(new DBGEvent(session, DBGEvent.SUSPEND, DBGEvent.BREAKPOINT));
            return null;
        }).when(session).resume();

        target.resume();

        verify(session).resume();
        assertTrue(target.isSuspended());
        assertTrue(target.canResume());
    }

    @Test
    void realProviderErrorsAreReportedWithoutPretendingTheTargetResumed() throws Exception {
        DBGSession session = resumableSession();
        DatabaseThread thread = pausedTarget(session);
        thread.rebuildStack(List.of(mock(DBGStackFrame.class)));
        doThrow(new DBGException("GaussDB rejected continue: connection lost")).when(session).resume();

        DebugException error = assertThrows(DebugException.class, target::resume);

        assertTrue(error.getMessage().contains("connection lost"));
        assertTrue(target.isSuspended());
        assertTrue(target.canResume());
        assertEquals(1, thread.getStackFrames().length);
    }

    @Test
    void rejectedThreadResumePreservesThePausedFrameAndReportsTheActualFailure() throws Exception {
        DBGSession session = resumableSession();
        DatabaseThread thread = pausedTarget(session);
        thread.rebuildStack(List.of(mock(DBGStackFrame.class)));
        doThrow(new DBGException("GaussDB rejected continue: permission denied")).when(session).resume();

        DebugException error = assertThrows(DebugException.class, thread::resume);

        assertTrue(error.getMessage().contains("permission denied"));
        assertTrue(thread.isSuspended());
        assertEquals(1, thread.getStackFrames().length);
        verify(session, never()).getStack();
    }

    @Test
    void providersWithoutTheNewOverrideKeepTheirExistingResumeContract() throws Exception {
        // The default method preserves legacy providers such as PostgreSQL.
        DBGSession session = mock(DBGSession.class, CALLS_REAL_METHODS);
        assertTrue(session.canResume());
        DatabaseThread thread = pausedTarget(session);
        thread.rebuildStack(List.of(mock(DBGStackFrame.class)));
        assertTrue(target.canResume());

        target.resume();

        verify(session).resume();
        assertFalse(target.isSuspended());
        assertEquals(0, thread.getStackFrames().length);
    }

    @ParameterizedTest
    @CsvSource({
        "true,false,false,true",
        "true,true,false,false",
        "true,false,true,false",
        "false,false,false,false"
    })
    void gaussdbResumeEligibilityMatchesActualSessionLifecycle(
        boolean attached, boolean done, boolean closing, boolean expected
    ) throws Exception {
        GaussDBDebugSession session = mock(GaussDBDebugSession.class, CALLS_REAL_METHODS);
        setField(session, GaussDBDebugSession.class, "attached", attached);
        setField(session, GaussDBDebugSession.class, "done", done);
        setField(session, GaussDBDebugSession.class, "closing", closing);

        assertEquals(expected, session.canResume());
    }

    @Test
    void gaussdbCannotResumeWhileAControlCommandIsAlreadyWaiting() throws Exception {
        GaussDBDebugSession session = mock(GaussDBDebugSession.class, CALLS_REAL_METHODS);
        setField(session, GaussDBDebugSession.class, "attached", true);
        setField(session, GaussDBDebugSession.class, "commandJob", mock(Job.class));

        assertFalse(session.canResume());
    }

    private static DBGSession resumableSession() {
        DBGSession session = mock(DBGSession.class);
        when(session.canResume()).thenReturn(true);
        return session;
    }

    private DatabaseThread pausedTarget(DBGSession session) throws Exception {
        ILaunch launch = mock(ILaunch.class);
        ILaunchConfiguration configuration = mock(ILaunchConfiguration.class);
        when(launch.getLaunchConfiguration()).thenReturn(configuration);
        when(configuration.getName()).thenReturn("resume-state-test");
        IProcess process = mock(IProcess.class);
        GaussDBDebugController controller = mock(GaussDBDebugController.class);
        target = new DatabaseDebugTarget(DBGConstants.MODEL_IDENTIFIER_DATABASE, launch, process, controller);
        setField(target, DatabaseDebugTarget.class, "session", session);
        target.handleDebugEvent(new DBGEvent(session, DBGEvent.SUSPEND, DBGEvent.BREAKPOINT));
        return (DatabaseThread) target.getThreads()[0];
    }

    private static void setField(Object instance, Class<?> declaringType, String name, Object value) throws Exception {
        Field field = declaringType.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }
}
