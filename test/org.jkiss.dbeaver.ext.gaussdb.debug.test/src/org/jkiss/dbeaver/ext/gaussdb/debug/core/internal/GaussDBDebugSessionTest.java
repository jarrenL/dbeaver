/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jkiss.dbeaver.debug.DBGTransactionAction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GaussDBDebugSessionTest {
    @Test
    void expectedAbortDoesNotBecomeTargetFailureDialog() throws Exception {
        JDBCStatement statement = mock(JDBCStatement.class);
        JDBCResultSet result = mock(JDBCResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT DBE_PLDEBUGGER.abort()")).thenReturn(result);
        when(result.next()).thenReturn(true);
        session.doDetach(monitor);
        assertEquals(org.eclipse.core.runtime.IStatus.CANCEL,
            session.handleTargetFailure(new SQLException("receive abort message")).getSeverity());
    }

    @Test
    void unexpectedTargetFailureRemainsAnError() {
        assertEquals(org.eclipse.core.runtime.IStatus.ERROR,
            session.handleTargetFailure(new SQLException("division by zero", "22012")).getSeverity());
    }

    @Test
    void readsAndPreserves64BitBackendProcessId() throws Exception {
        long pid = 281470169823552L;
        JDBCExecutionContext context = mock(JDBCExecutionContext.class);
        JDBCSession jdbc = mock(JDBCSession.class);
        JDBCStatement statement = mock(JDBCStatement.class);
        JDBCResultSet result = mock(JDBCResultSet.class);
        when(context.openSession(any(), any(), anyString())).thenReturn(jdbc);
        when(jdbc.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT pg_backend_pid()")).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getLong(1)).thenReturn(pid);
        long actual = GaussDBDebugSession.queryLong(context, new VoidProgressMonitor(), "SELECT pg_backend_pid()", "Read target process");
        assertEquals(pid, actual);
        verify(result, never()).getInt(1);
        GaussDBDebugSessionInfo info = new GaussDBDebugSessionInfo(actual, "dn_6001", 3);
        assertEquals(pid, info.getID());
        assertEquals(pid, info.toMap().get("pid"));
        assertTrue(info.getTitle().contains(Long.toString(pid)));
    }

    private final VoidProgressMonitor monitor = new VoidProgressMonitor();
    private JDBCSession connection;
    private GaussDBDebugSession session;
    private GaussDBDebugController controller;

    @BeforeEach
    void setup() {
        JDBCExecutionContext context = mock(JDBCExecutionContext.class);
        connection = mock(JDBCSession.class);
        when(context.openSession(any(), any(), anyString())).thenReturn(connection);
        controller = mock(GaussDBDebugController.class);
        session = new GaussDBDebugSession(controller, context, context, mock(GaussDBProcedure.class));
    }

    private JDBCPreparedStatement query(String sql, boolean row, boolean bool, int number) throws SQLException {
        JDBCPreparedStatement statement = mock(JDBCPreparedStatement.class);
        JDBCResultSet result = mock(JDBCResultSet.class);
        when(connection.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(row, false);
        when(result.getBoolean(1)).thenReturn(bool);
        when(result.getInt(1)).thenReturn(number);
        return statement;
    }

    private JDBCPreparedStatement validBreakpoint(int id) throws SQLException {
        query("SELECT canbreak FROM DBE_PLDEBUGGER.info_code(?::oid) WHERE lineno=?", true, true, 0);
        return query("SELECT DBE_PLDEBUGGER.add_breakpoint(?::oid, ?::integer)", true, true, id);
    }

    @Test
    void selectsAnExecutableOverloadAndPrefersOidWhenBothExist() throws Exception {
        assertEquals("oid", GaussDBDebugCapabilityDetector.breakpointArgumentType(Set.of("26 23", "25 23")));
        assertEquals("text", GaussDBDebugCapabilityDetector.breakpointArgumentType(Set.of("25 23")));
        assertThrows(DBGException.class, () -> GaussDBDebugCapabilityDetector.breakpointArgumentType(Set.of("26 20")));
    }

    @Test
    void firstEnableRegistersInitiallyDisabledBreakpointWithTypedArguments() throws Exception {
        JDBCPreparedStatement add = validBreakpoint(0);
        GaussDBDebugBreakpointDescriptor breakpoint = new GaussDBDebugBreakpointDescriptor(172034, 4);
        breakpoint.setEnabled(false);
        session.enableBreakpoint(monitor, breakpoint);
        assertEquals(1, session.getBreakpoints().size());
        assertEquals(0, breakpoint.getServerId());
        assertTrue(breakpoint.isEnabled());
        verify(add).setString(1, "172034");
        verify(add).setInt(2, 4);
    }

    @Test
    void disableAndDeleteUnregisteredBreakpointsAreNoOps() throws Exception {
        GaussDBDebugBreakpointDescriptor breakpoint = new GaussDBDebugBreakpointDescriptor(172034, 4);
        session.disableBreakpoint(monitor, breakpoint);
        session.removeBreakpoint(monitor, breakpoint);
        assertTrue(session.getBreakpoints().isEmpty());
        verifyNoInteractions(connection);
    }

    @Test
    void registeredBreakpointCanBeDisabledReenabledAndRemovedIncludingIdZero() throws Exception {
        validBreakpoint(0);
        GaussDBDebugBreakpointDescriptor breakpoint = new GaussDBDebugBreakpointDescriptor(172034, 4);
        session.addBreakpoint(monitor, breakpoint);
        for (String command : new String[]{"disable_breakpoint", "enable_breakpoint", "delete_breakpoint"}) {
            when(connection.prepareStatement("SELECT DBE_PLDEBUGGER." + command + "(?)"))
                .thenReturn(mock(JDBCPreparedStatement.class));
        }
        session.disableBreakpoint(monitor, breakpoint);
        assertFalse(breakpoint.isEnabled());
        session.enableBreakpoint(monitor, breakpoint);
        assertTrue(breakpoint.isEnabled());
        assertEquals(1, session.getBreakpoints().size());
        session.removeBreakpoint(monitor, breakpoint);
        assertTrue(session.getBreakpoints().isEmpty());
        verify(connection.prepareStatement("SELECT DBE_PLDEBUGGER.delete_breakpoint(?)")).setInt(1, 0);
    }

    @Test
    void repeatedBreakpointNotificationsOnlyApplyStateTransitions() throws Exception {
        validBreakpoint(0);
        GaussDBDebugBreakpointDescriptor breakpoint = new GaussDBDebugBreakpointDescriptor(172034, 4);
        session.addBreakpoint(monitor, breakpoint);
        JDBCPreparedStatement enable = mock(JDBCPreparedStatement.class);
        JDBCPreparedStatement disable = mock(JDBCPreparedStatement.class);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.enable_breakpoint(?)")).thenReturn(enable);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.disable_breakpoint(?)")).thenReturn(disable);
        // Notifications may carry a different descriptor for the same source line.
        GaussDBDebugBreakpointDescriptor notification = new GaussDBDebugBreakpointDescriptor(172034, 4);
        session.enableBreakpoint(monitor, notification);
        verifyNoInteractions(enable);
        session.disableBreakpoint(monitor, notification);
        session.disableBreakpoint(monitor, notification);
        verify(disable).execute();
        session.enableBreakpoint(monitor, notification);
        session.enableBreakpoint(monitor, notification);
        verify(enable).execute();
        assertTrue(breakpoint.isEnabled());
    }

    @Test
    void failedBreakpointTransitionRemainsRetryable() throws Exception {
        validBreakpoint(0);
        GaussDBDebugBreakpointDescriptor breakpoint = new GaussDBDebugBreakpointDescriptor(172034, 4);
        session.addBreakpoint(monitor, breakpoint);
        JDBCPreparedStatement disable = mock(JDBCPreparedStatement.class);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.disable_breakpoint(?)")).thenReturn(disable);
        when(disable.execute()).thenThrow(new SQLException("connection lost", "08006")).thenReturn(true);
        assertThrows(DBGException.class, () -> session.disableBreakpoint(monitor, breakpoint));
        assertTrue(breakpoint.isEnabled());
        session.disableBreakpoint(monitor, breakpoint);
        assertFalse(breakpoint.isEnabled());
        verify(disable, times(2)).execute();
    }

    @Test
    void duplicateRegistrationDeletesOldServerBreakpointBeforeAdding() throws Exception {
        validBreakpoint(0);
        session.addBreakpoint(monitor, new GaussDBDebugBreakpointDescriptor(172034, 4));
        validBreakpoint(1);
        JDBCPreparedStatement delete = mock(JDBCPreparedStatement.class);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.delete_breakpoint(?)")).thenReturn(delete);
        session.addBreakpoint(monitor, new GaussDBDebugBreakpointDescriptor(172034, 4));
        verify(delete).setInt(1, 0);
        verify(delete).execute();
        assertEquals(1, session.getBreakpoints().size());
        assertEquals(1, ((GaussDBDebugBreakpointDescriptor) session.getBreakpoints().getFirst()).getServerId());
    }

    @Test
    void rejectsInvalidLineAndNegativeServerIdWithoutRegistering() throws Exception {
        query("SELECT canbreak FROM DBE_PLDEBUGGER.info_code(?::oid) WHERE lineno=?", true, false, 0);
        assertThrows(DBGException.class, () -> session.addBreakpoint(monitor, new GaussDBDebugBreakpointDescriptor(172034, 1)));
        validBreakpoint(-1);
        assertThrows(DBGException.class, () -> session.addBreakpoint(monitor, new GaussDBDebugBreakpointDescriptor(172034, 4)));
        assertTrue(session.getBreakpoints().isEmpty());
    }

    @Test
    void rejectedVariableValueDoesNotChangeClientValue() throws Exception {
        query("SELECT DBE_PLDEBUGGER.set_var(?, ?)", true, false, 0);
        GaussDBDebugVariable variable = new GaussDBDebugVariable("x", "int4", "7", null, false, 0);
        assertThrows(DBGException.class, () -> session.setVariableVal(variable, "not_an_integer"));
        assertEquals("7", variable.getVal());
    }

    @Test
    void missingResultAndSqlExceptionPreserveClientValue() throws Exception {
        JDBCPreparedStatement set = query("SELECT DBE_PLDEBUGGER.set_var(?, ?)", false, false, 0);
        GaussDBDebugVariable variable = new GaussDBDebugVariable("x", "int4", "7", null, false, 0);
        assertThrows(DBGException.class, () -> session.setVariableVal(variable, "8"));
        when(set.executeQuery()).thenThrow(new SQLException("connection lost", "08006"));
        assertThrows(DBGException.class, () -> session.setVariableVal(variable, "8"));
        assertEquals("7", variable.getVal());
    }

    @Test
    void successfulExpressionUsesServerValueAndSupportsSqlNull() throws Exception {
        query("SELECT DBE_PLDEBUGGER.set_var(?, ?)", true, true, 0);
        JDBCStatement locals = mock(JDBCStatement.class);
        JDBCResultSet result = mock(JDBCResultSet.class);
        when(connection.createStatement()).thenReturn(locals);
        when(locals.executeQuery("SELECT * FROM DBE_PLDEBUGGER.info_locals(0)")).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getString("varname")).thenReturn("x");
        when(result.getString("vartype")).thenReturn("int4");
        when(result.getString("value")).thenReturn("9");
        GaussDBDebugVariable variable = new GaussDBDebugVariable("x", "int4", "7", null, false, 0);
        session.setVariableVal(variable, "4 + 5");
        assertEquals("9", variable.getVal());
        query("SELECT DBE_PLDEBUGGER.set_var(?, ?)", true, true, 0);
        when(result.next()).thenReturn(true, false);
        when(result.getString("value")).thenReturn(null);
        session.setVariableVal(variable, "NULL");
        assertNull(variable.getVal());
    }

    @Test
    void attachRetriesOnlyTheTargetNotReadyState() throws Exception {
        JDBCPreparedStatement attach = mock(JDBCPreparedStatement.class);
        when(attach.execute()).thenThrow(new SQLException("not ready", "D0011")).thenReturn(true);
        GaussDBDebugSession.attachWithRetry(attach, monitor, () -> false);
        verify(attach, times(2)).execute();
        JDBCPreparedStatement denied = mock(JDBCPreparedStatement.class);
        when(denied.execute()).thenThrow(new SQLException("denied", "42501"));
        assertThrows(SQLException.class, () -> GaussDBDebugSession.attachWithRetry(denied, monitor, () -> false));
        verify(denied).execute();
    }

    @Test
    void attachStopsWhenTargetHasAlreadyFinished() throws Exception {
        JDBCPreparedStatement attach = mock(JDBCPreparedStatement.class);
        assertThrows(DBGException.class, () -> GaussDBDebugSession.attachWithRetry(attach, monitor, () -> true));
        verifyNoInteractions(attach);
    }

    @Test
    void callerFrameVariablesCannotAccidentallyModifyTheCurrentFrame() {
        GaussDBDebugVariable caller = new GaussDBDebugVariable("x", "int4", "111", null, false, 1);
        assertTrue(caller.isReadOnly());
        assertThrows(DBGException.class, () -> session.setVariableVal(caller, "999"));
        assertEquals("111", caller.getVal());
        verifyNoInteractions(connection);
    }

    @Test
    void runtimeErrorTerminatesWithoutIssuingCommandsRejectedInTheErrorWait() throws Exception {
        assertFalse(session.terminateOnExecutionError("ordinary source line"));
        assertFalse(session.terminateOnExecutionError(null));
        assertTrue(session.terminateOnExecutionError("[EXECUTION HAS ERROR OCCURRED!]"));
        assertTrue(session.isDone());
        assertFalse(session.isTransactionCompletionPending());
        verify(controller).fireEvent(argThat(event -> event.getKind() == org.jkiss.dbeaver.debug.DBGEvent.TERMINATE));
        verifyNoInteractions(connection);
    }

    @Test
    void constantsAreRejectedBeforeSql() {
        assertThrows(DBGException.class, () -> session.setVariableVal(
            new GaussDBDebugVariable("c", "int4", "7", null, true, 0), "8"));
        verifyNoInteractions(connection);
    }

    private void field(String name, Object value) throws Exception {
        var field = GaussDBDebugSession.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(session, value);
    }

    private void completedTarget() throws Exception {
        field("done", true);
        field("targetSucceeded", true);
        field("transactionCompletionPending", true);
    }

    private static final String DEFAULT_OVERLOAD_QUERY =
        "SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_proc target " +
        "ON p.proname=target.proname AND p.pronamespace=target.pronamespace " +
        "WHERE target.oid=? AND p.oid<>target.oid LIMIT 1";

    @Test
    void overloadedDefaultTargetIsRejectedBeforeExecutingTheRoutine() throws Exception {
        query(DEFAULT_OVERLOAD_QUERY, true, true, 0);
        assertThrows(DBGException.class, () -> session.validateDefaultInvocation(new VoidProgressMonitor()));
        verify(connection, never()).commit();
    }

    @Test
    void unambiguousDefaultTargetPassesCatalogValidation() throws Exception {
        var statement = query(DEFAULT_OVERLOAD_QUERY, false, false, 0);
        session.validateDefaultInvocation(new VoidProgressMonitor());
        verify(statement).setLong(eq(1), anyLong());
        verify(statement).setQueryTimeout(10);
        verify(statement).executeQuery();
    }

    @Test
    void canceledDefaultValidationDoesNotQuery() {
        var canceled = mock(DBRProgressMonitor.class);
        when(canceled.isCanceled()).thenReturn(true);
        assertThrows(DBGException.class, () -> session.validateDefaultInvocation(canceled));
        verifyNoInteractions(connection);
    }

    @Test
    void failedCommitIsVisibleAndCannotBeAutomaticallyRetried() throws Exception {
        completedTarget();
        doThrow(new SQLException("connection lost", "08006")).when(connection).commit();
        assertThrows(DBGException.class, () -> session.completeTransaction(monitor, DBGTransactionAction.COMMIT));
        assertTrue(session.isTransactionCompletionPending());
        assertThrows(DBGException.class, () -> session.completeTransaction(monitor, DBGTransactionAction.COMMIT));
        verify(connection, times(1)).commit();
        session.completeTransaction(monitor, DBGTransactionAction.ROLLBACK);
        verify(connection).rollback();
        assertFalse(session.isTransactionCompletionPending());
    }

    @Test
    void commitBoundsInfiniteNetworkWaitAndRestoresPreviousTimeout() throws Exception {
        completedTarget();
        session.completeTransaction(monitor, DBGTransactionAction.COMMIT);
        var order = inOrder(connection);
        order.verify(connection).setNetworkTimeout(any(), eq(10000));
        order.verify(connection).commit();
        order.verify(connection).setNetworkTimeout(any(), eq(0));
    }

    @Test
    void commitPreservesShorterUserNetworkTimeout() throws Exception {
        completedTarget(); when(connection.getNetworkTimeout()).thenReturn(1200);
        session.completeTransaction(monitor, DBGTransactionAction.COMMIT);
        verify(connection, times(2)).setNetworkTimeout(any(), eq(1200));
    }

    @Test
    void cancellationBeforeTransactionDoesNotIssueCommit() throws Exception {
        completedTarget();
        var canceled = mock(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor.class);
        when(canceled.isCanceled()).thenReturn(true);
        assertThrows(DBGException.class, () -> session.completeTransaction(canceled, DBGTransactionAction.COMMIT));
        verify(connection, never()).commit();
        assertTrue(session.isTransactionCompletionPending());
    }

    @Test
    void lateTargetAcknowledgmentPublishesCompletionOnce() throws Exception {
        field("controlFinished", true);
        session.publishCompletionIfReady();
        assertFalse(session.isTransactionCompletionPending());
        verifyNoInteractions(controller);
        field("targetSucceeded", true);
        session.publishCompletionIfReady();
        session.publishCompletionIfReady();
        assertTrue(session.isTransactionCompletionPending());
        verify(controller, times(1)).fireEvent(argThat(event -> event.getKind() == org.jkiss.dbeaver.debug.DBGEvent.SUSPEND));
    }

    @Test
    void targetAcknowledgmentBeforeControlCompletionDoesNotPromptEarly() throws Exception {
        field("targetSucceeded", true);
        session.publishCompletionIfReady();
        assertFalse(session.isTransactionCompletionPending());
        verifyNoInteractions(controller);
        field("controlFinished", true);
        session.publishCompletionIfReady();
        assertTrue(session.isTransactionCompletionPending());
    }

    @Test
    void waitsForTargetBeforeCommitting() throws Exception {
        completedTarget();
        var finished = new CountDownLatch(1);
        field("targetFinished", finished);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var commit = executor.submit(() -> { session.completeTransaction(monitor, DBGTransactionAction.COMMIT); return null; });
            verify(connection, never()).commit();
            finished.countDown();
            commit.get(5, TimeUnit.SECONDS);
            verify(connection).commit();
        } finally {
            finished.countDown();
        }
    }

    @Test
    void sessionCloseDoesNotRaceAnInFlightCommit() throws Exception {
        completedTarget();
        var context = mock(JDBCExecutionContext.class);
        when(context.openSession(any(), any(), anyString())).thenReturn(connection);
        field("targetConnection", context);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.turn_off(?::oid)"))
            .thenReturn(mock(JDBCPreparedStatement.class));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var committed = new AtomicBoolean();
        doAnswer(call -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            committed.set(true);
            return null;
        }).when(connection).commit();
        doAnswer(call -> { assertTrue(committed.get(), "connection closed during commit"); return null; }).when(context).close();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var commit = executor.submit(() -> { session.completeTransaction(monitor, DBGTransactionAction.COMMIT); return null; });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var close = executor.submit(() -> { session.closeSession(monitor); return null; });
            verify(context, never()).close();
            release.countDown();
            commit.get(5, TimeUnit.SECONDS);
            close.get(5, TimeUnit.SECONDS);
            verify(context).close();
            verify(connection, never()).rollback();
        } finally {
            release.countDown();
        }
    }

    @Test
    void runningCommandRejectsQueriesWithoutTouchingJdbc() throws Exception {
        field("commandJob", mock(org.eclipse.core.runtime.jobs.Job.class));
        assertThrows(DBGException.class, () -> session.getStack());
        assertThrows(DBGException.class, () -> session.getVariables(null));
        assertThrows(DBGException.class, () -> session.addBreakpoint(monitor, new GaussDBDebugBreakpointDescriptor(1, 4)));
        verifyNoInteractions(connection);
    }

    @Test
    void concurrentDuplicateAddsAreSerializedAndKeepOneRegistration() throws Exception {
        var validate = mock(JDBCPreparedStatement.class);
        var add = mock(JDBCPreparedStatement.class);
        var delete = mock(JDBCPreparedStatement.class);
        when(connection.prepareStatement("SELECT canbreak FROM DBE_PLDEBUGGER.info_code(?::oid) WHERE lineno=?")).thenReturn(validate);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.add_breakpoint(?::oid, ?::integer)")).thenReturn(add);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.delete_breakpoint(?)")).thenReturn(delete);
        when(validate.executeQuery()).thenAnswer(call -> {
            var row = mock(JDBCResultSet.class);
            when(row.next()).thenReturn(true);
            when(row.getBoolean(1)).thenReturn(true);
            return row;
        });
        var firstEntered = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ids = new AtomicInteger();
        when(add.executeQuery()).thenAnswer(call -> {
            int id = ids.getAndIncrement();
            if (id == 0) {
                firstEntered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            var row = mock(JDBCResultSet.class);
            when(row.next()).thenReturn(true);
            when(row.getInt(1)).thenReturn(id);
            return row;
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { session.addBreakpoint(monitor, new GaussDBDebugBreakpointDescriptor(1, 4)); return null; });
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                secondStarted.countDown();
                session.addBreakpoint(monitor, new GaussDBDebugBreakpointDescriptor(1, 4)); return null;
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(1, session.getBreakpoints().size());
            assertEquals(1, ((GaussDBDebugBreakpointDescriptor) session.getBreakpoints().getFirst()).getServerId());
            verify(delete).setInt(1, 0);
            verify(delete).execute();
        } finally {
            release.countDown();
        }
    }

    @Test
    void suspendEventCanReadVariablesAfterTheControlCommandCompletes() throws Exception {
        field("attached", true);
        var statement = mock(JDBCStatement.class);
        when(connection.createStatement()).thenReturn(statement);
        var step = mock(JDBCResultSet.class);
        when(step.next()).thenReturn(true);
        when(step.getString("query")).thenReturn("v := 1;");
        when(statement.executeQuery("SELECT * FROM DBE_PLDEBUGGER.next()")).thenReturn(step);
        when(statement.executeQuery("SELECT * FROM DBE_PLDEBUGGER.info_locals(0)"))
            .thenReturn(mock(JDBCResultSet.class));
        var suspended = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        doAnswer(call -> {
            org.jkiss.dbeaver.debug.DBGEvent event = call.getArgument(0);
            if (event.getKind() == org.jkiss.dbeaver.debug.DBGEvent.SUSPEND) {
                try {
                    assertFalse(session.isWaiting());
                    assertTrue(session.getVariables(null).isEmpty());
                } catch (Throwable e) {
                    failure.set(e);
                } finally {
                    suspended.countDown();
                }
            }
            return null;
        }).when(controller).fireEvent(any());
        session.execStepOver();
        assertTrue(suspended.await(5, TimeUnit.SECONDS));
        assertNull(failure.get());
        verify(statement).executeQuery("SELECT * FROM DBE_PLDEBUGGER.info_locals(0)");
    }

    @Test
    void closeWhileControlIsRunningDoesNotQueueAbortOnTheBusyConnection() throws Exception {
        field("attached", true);
        var statement = mock(JDBCStatement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.turn_off(?::oid)"))
            .thenReturn(mock(JDBCPreparedStatement.class));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(statement.executeQuery("SELECT * FROM DBE_PLDEBUGGER.continue()")).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return mock(JDBCResultSet.class);
        });
        session.execContinue();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        try {
            assertThrows(DBGException.class, () -> session.getVariables(null));
            assertThrows(DBGException.class, () -> session.execStepInto());
            session.closeSession(monitor);
            verify(statement, never()).execute("SELECT DBE_PLDEBUGGER.abort()");
            assertFalse(session.canStepInto());
        } finally {
            release.countDown();
        }
    }
}
