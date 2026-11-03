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
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.dbeaver.debug.DBGEvent;
import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Distinguishes a nested routine's end marker from successful completion of the target CALL. */
class GaussDBDebugCompletionStateTest {
    private static final String FINISHED = "[EXECUTION FINISHED]";
    private static final String STEP = "SELECT * FROM DBE_PLDEBUGGER.step()";
    private static final String CONTINUE = "SELECT * FROM DBE_PLDEBUGGER.continue()";
    private static final String STACK = "SELECT * FROM DBE_PLDEBUGGER.backtrace()";

    private final VoidProgressMonitor monitor = new VoidProgressMonitor();
    private final BlockingQueue<DBGEvent> events = new LinkedBlockingQueue<>();
    private GaussDBDebugSession session;
    private JDBCStatement statement;

    @BeforeEach
    void setup() throws Exception {
        GaussDBDebugController controller = mock(GaussDBDebugController.class);
        JDBCExecutionContext context = mock(JDBCExecutionContext.class);
        JDBCSession connection = mock(JDBCSession.class);
        statement = mock(JDBCStatement.class);
        when(context.openSession(any(), any(), anyString())).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        GaussDBProcedure routine = mock(GaussDBProcedure.class);
        when(routine.getObjectId()).thenReturn(100L);
        session = new GaussDBDebugSession(controller, context, context, routine);
        field("attached", true);
        field("targetFinished", new CountDownLatch(1));
        doAnswer(call -> {
            events.add(call.getArgument(0));
            return null;
        }).when(controller).fireEvent(any());
    }

    @Test
    void nestedEndFromARealControlJobStillAllowsContinueIntoTheCaller() throws Exception {
        doReturn(source(FINISHED)).when(statement).executeQuery(STEP);
        doReturn(stack(200, 100)).when(statement).executeQuery(STACK);
        doReturn(source("INSERT INTO results VALUES (30);")).when(statement).executeQuery(CONTINUE);

        session.execStepInto();
        awaitSuspension();

        assertFalse(session.isDone());
        assertFalse(session.isTransactionCompletionPending());
        assertTrue(session.canResume());
        assertFalse((boolean) field("controlFinished"));

        assertDoesNotThrow(session::resume);
        awaitSuspension();

        assertFalse(session.isDone());
        assertTrue(session.canResume());
        verify(statement).executeQuery(CONTINUE);
    }

    @Test
    void outerEndFromARealControlJobPublishesTransactionCompletionOnlyAfterTargetSuccess() throws Exception {
        CountDownLatch targetFinished = (CountDownLatch) field("targetFinished");
        JDBCResultSet eof = source(FINISHED);
        when(statement.executeQuery(CONTINUE)).thenAnswer(call -> {
            field("targetSucceeded", true);
            targetFinished.countDown();
            return eof;
        });

        session.resume();
        awaitSuspension();

        assertTrue(session.isDone());
        assertTrue(session.isTransactionCompletionPending());
        assertFalse(session.canResume());
        assertTrue((boolean) field("controlFinished"));
        verify(statement, never()).executeQuery(STACK);
    }

    @Test
    void recursiveFrameWithTheSameOidAsTheEntryIsStillAnActiveInvocation() throws Exception {
        doReturn(stack(100, 100)).when(statement).executeQuery(STACK);

        assertFalse(session.isWholeInvocationFinished(monitor));

        assertFalse(session.isDone());
        assertTrue(session.canResume());
        assertFalse(session.isTransactionCompletionPending());
    }

    @ParameterizedTest
    @ValueSource(strings = {"42501", "08006", "D0011", "XX000"})
    void errorsOtherThanNotAttachedAreNotMistakenForSuccessfulCompletion(String state) throws Exception {
        SQLException sqlFailure = new SQLException("backtrace failed", state);
        when(statement.executeQuery(STACK)).thenThrow(sqlFailure);

        DBGException failure = assertThrows(DBGException.class, () -> session.isWholeInvocationFinished(monitor));

        assertSame(sqlFailure, failure.getCause());
        assertFalse(session.isTransactionCompletionPending());
        assertFalse(session.isDone());
    }

    @Test
    void notAttachedWaitsForTheDelayedTargetReplyBeforeAcceptingCompletion() throws Exception {
        when(statement.executeQuery(STACK)).thenThrow(new SQLException("debug target not attached", "D0005"));
        CountDownLatch targetFinished = (CountDownLatch) field("targetFinished");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var completion = executor.submit(() -> session.isWholeInvocationFinished(monitor));
            verify(statement, timeout(5000)).executeQuery(STACK);
            assertFalse(completion.isDone());
            assertFalse(session.isTransactionCompletionPending());

            field("targetSucceeded", true);
            targetFinished.countDown();

            assertTrue(completion.get(5, TimeUnit.SECONDS));
        } finally {
            targetFinished.countDown();
        }
    }

    @Test
    void missingAttachmentAloneDoesNotTurnAFailedTargetIntoSuccess() throws Exception {
        when(statement.executeQuery(STACK)).thenThrow(new SQLException("debug target not attached", "D0005"));
        ((CountDownLatch) field("targetFinished")).countDown();

        DBGException failure = assertThrows(DBGException.class, () -> session.isWholeInvocationFinished(monitor));

        assertTrue(failure.getMessage().contains("did not complete successfully"));
        assertFalse(session.isTransactionCompletionPending());
    }

    @Test
    void anEmptyStackAlsoRequiresSuccessfulTargetExecution() throws Exception {
        doReturn(stack()).when(statement).executeQuery(STACK);
        ((CountDownLatch) field("targetFinished")).countDown();

        assertThrows(DBGException.class, () -> session.isWholeInvocationFinished(monitor));

        assertFalse(session.isTransactionCompletionPending());
    }

    @Test
    void cancelingTheTargetWaitDoesNotClaimACompletedInvocation() throws Exception {
        when(statement.executeQuery(STACK)).thenThrow(new SQLException("debug target not attached", "D0005"));
        DBRProgressMonitor canceled = mock(DBRProgressMonitor.class);
        when(canceled.isCanceled()).thenReturn(true);

        DBGException failure = assertThrows(DBGException.class, () -> session.isWholeInvocationFinished(canceled));

        assertTrue(failure.getMessage().contains("has not finished"));
        assertFalse(session.isDone());
        assertFalse(session.isTransactionCompletionPending());
    }

    @Test
    void anAlreadySuccessfulTargetDoesNotProbeAnExpiredControllerAttachment() throws Exception {
        field("targetSucceeded", true);
        ((CountDownLatch) field("targetFinished")).countDown();

        assertTrue(session.isWholeInvocationFinished(monitor));

        verifyNoInteractions(statement);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "v_message := '[EXECUTION FINISHED]';",
        "-- [EXECUTION FINISHED]",
        "/* [EXECUTION FINISHED] */",
        "RAISE NOTICE '[EXECUTION FINISHED]';",
        "[EXECUTION FINISHED] trailing text",
        "v_total := v_total + 1;",
        ""
    })
    void sourceTextContainingTheMarkerIsNotAProtocolEnd(String source) throws Exception {
        assertFalse(GaussDBDebugSession.isExecutionFinished(source));
        doReturn(source(source)).when(statement).executeQuery(STEP);

        session.execStepInto();
        awaitSuspension();

        assertFalse(session.isDone());
        assertFalse(session.isTransactionCompletionPending());
        assertTrue(session.canResume());
        verify(statement, never()).executeQuery(STACK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "v_message := '[EXECUTION HAS ERROR OCCURRED!]';",
        "-- [EXECUTION HAS ERROR OCCURRED!]",
        "/* [EXECUTION HAS ERROR OCCURRED!] */",
        "RAISE NOTICE '[EXECUTION HAS ERROR OCCURRED!]';",
        "[EXECUTION HAS ERROR OCCURRED!] trailing text"
    })
    void sourceContainingTheErrorMarkerDoesNotTerminateTheTarget(String source) throws Exception {
        doReturn(source(source)).when(statement).executeQuery(STEP);

        session.execStepInto();
        awaitSuspension();

        assertFalse(session.isDone());
        assertTrue(session.canResume());
        assertFalse(session.isTransactionCompletionPending());
        verify(statement, never()).executeQuery(STACK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[EXECUTION HAS ERROR OCCURRED!]", "  [EXECUTION HAS ERROR OCCURRED!]  "})
    void realServerErrorMarkerStillTerminatesInsteadOfOfferingTransactionCompletion(String marker) throws Exception {
        doReturn(source(marker)).when(statement).executeQuery(CONTINUE);

        session.resume();
        awaitEvent(DBGEvent.TERMINATE);

        assertTrue(session.isDone());
        assertFalse(session.canResume());
        assertFalse(session.isTransactionCompletionPending());
        verify(statement, never()).executeQuery(STACK);
    }

    private JDBCResultSet source(String text) throws Exception {
        JDBCResultSet result = mock(JDBCResultSet.class);
        when(result.next()).thenReturn(true, false);
        when(result.getString("query")).thenReturn(text);
        return result;
    }

    private JDBCResultSet stack(long... routineIds) throws Exception {
        JDBCResultSet result = mock(JDBCResultSet.class);
        var row = new java.util.concurrent.atomic.AtomicInteger(-1);
        when(result.next()).thenAnswer(call -> row.incrementAndGet() < routineIds.length);
        when(result.getInt("frameno")).thenAnswer(call -> row.get());
        when(result.getLong("funcoid")).thenAnswer(call -> routineIds[row.get()]);
        when(result.getString("funcname")).thenAnswer(call -> "routine_" + routineIds[row.get()]);
        when(result.getString("query")).thenReturn("caller statement");
        return result;
    }

    private void awaitSuspension() throws Exception {
        awaitEvent(DBGEvent.SUSPEND);
    }

    private void awaitEvent(int expectedKind) throws Exception {
        DBGEvent event;
        do {
            event = events.poll(5, TimeUnit.SECONDS);
            assertNotNull(event, "The control job did not publish its result");
        } while (event.getKind() == DBGEvent.RESUME);
        assertEquals(expectedKind, event.getKind());
        // The event is synchronous, while the command job still owns this lock.
        // Wait for that job to exit before issuing the next controller command.
        ReentrantLock lock = (ReentrantLock) field("controllerLock");
        assertTrue(lock.tryLock(5, TimeUnit.SECONDS));
        lock.unlock();
    }

    private Object field(String name) throws Exception {
        Field field = GaussDBDebugSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(session);
    }

    private void field(String name, Object value) throws Exception {
        Field field = GaussDBDebugSession.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(session, value);
    }
}
