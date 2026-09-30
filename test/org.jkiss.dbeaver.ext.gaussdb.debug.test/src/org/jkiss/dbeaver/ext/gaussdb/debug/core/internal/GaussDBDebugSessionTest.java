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
    void failedInitialDisableKeepsRegisteredBreakpointRetryable() throws Exception {
        validBreakpoint(0);
        var breakpoint = new GaussDBDebugBreakpointDescriptor(172034, 4);
        breakpoint.setEnabled(false);
        JDBCPreparedStatement disable = mock(JDBCPreparedStatement.class);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.disable_breakpoint(?)")).thenReturn(disable);
        when(disable.execute()).thenThrow(new SQLException("disable rejected", "XX000")).thenReturn(true);
        assertThrows(DBGException.class, () -> session.addBreakpoint(monitor, breakpoint));
        assertEquals(1, session.getBreakpoints().size());
        assertSame(breakpoint, session.getBreakpoints().getFirst());
        assertEquals(0, breakpoint.getServerId());
        assertTrue(breakpoint.isEnabled(), "the successful add has not yet been disabled on the server");
        session.disableBreakpoint(monitor, breakpoint);
        verify(disable, times(2)).setInt(1, 0);
        verify(disable, times(2)).execute();
        assertFalse(breakpoint.isEnabled());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"result,false", "result,true", "statement,false", "statement,true"})
    void acknowledgedBreakpointSurvivesResourceCloseFailure(String failingResource, boolean initiallyDisabled) throws Exception {
        query("SELECT canbreak FROM DBE_PLDEBUGGER.info_code(?::oid) WHERE lineno=?", true, true, 0);
        JDBCPreparedStatement add = mock(JDBCPreparedStatement.class);
        JDBCResultSet result = mock(JDBCResultSet.class);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.add_breakpoint(?::oid, ?::integer)")).thenReturn(add);
        when(add.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getInt(1)).thenReturn(0);
        SQLException failure = new SQLException("close failed after acknowledgment", "XX000");
        if (failingResource.equals("result")) {
            doThrow(failure).when(result).close();
        } else {
            doThrow(failure).when(add).close();
        }
        JDBCPreparedStatement disable = mock(JDBCPreparedStatement.class);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.disable_breakpoint(?)")).thenReturn(disable);
        var breakpoint = new GaussDBDebugBreakpointDescriptor(172034, 4);
        breakpoint.setEnabled(!initiallyDisabled);
        DBGException actual = assertThrows(DBGException.class, () -> session.addBreakpoint(monitor, breakpoint));
        assertSame(failure, actual.getCause());
        assertEquals(1, session.getBreakpoints().size());
        assertSame(breakpoint, session.getBreakpoints().getFirst());
        assertEquals(0, breakpoint.getServerId());
        boolean disableCompleted = initiallyDisabled && failingResource.equals("statement");
        assertEquals(!disableCompleted, breakpoint.isEnabled());
        verify(disable, times(disableCompleted ? 1 : 0)).execute();
        JDBCPreparedStatement delete = mock(JDBCPreparedStatement.class);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.delete_breakpoint(?)")).thenReturn(delete);
        session.removeBreakpoint(monitor, breakpoint);
        verify(delete).setInt(1, 0);
        verify(delete).execute();
        assertTrue(session.getBreakpoints().isEmpty());
    }

    @Test
    void initialDisabledRegistrationAcknowledgesDisableBeforePublishingDisabledState() throws Exception {
        validBreakpoint(0);
        var breakpoint = new GaussDBDebugBreakpointDescriptor(172034, 4);
        breakpoint.setEnabled(false);
        JDBCPreparedStatement disable = mock(JDBCPreparedStatement.class);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.disable_breakpoint(?)")).thenReturn(disable);
        when(disable.execute()).thenAnswer(call -> {
            assertTrue(breakpoint.isEnabled());
            return true;
        });
        session.addBreakpoint(monitor, breakpoint);
        assertFalse(breakpoint.isEnabled());
        session.disableBreakpoint(monitor, breakpoint);
        verify(disable).execute();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"enable", "disable", "delete"})
    void acknowledgedBreakpointChangeSurvivesStatementCloseFailure(String command) throws Exception {
        validBreakpoint(0);
        JDBCPreparedStatement initialDisable = mock(JDBCPreparedStatement.class);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.disable_breakpoint(?)")).thenReturn(initialDisable);
        var breakpoint = new GaussDBDebugBreakpointDescriptor(172034, 4);
        breakpoint.setEnabled(!command.equals("enable"));
        session.addBreakpoint(monitor, breakpoint);
        JDBCPreparedStatement change = mock(JDBCPreparedStatement.class);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER." + command + "_breakpoint(?)")).thenReturn(change);
        SQLException failure = new SQLException("close after successful " + command, "XX000");
        doThrow(failure).when(change).close();
        DBGException actual = assertThrows(DBGException.class, () -> changeBreakpoint(command, breakpoint));
        assertSame(failure, actual.getCause());
        if (command.equals("delete")) {
            assertTrue(session.getBreakpoints().isEmpty());
        } else {
            assertSame(breakpoint, session.getBreakpoints().getFirst());
            assertEquals(command.equals("enable"), breakpoint.isEnabled());
        }
        changeBreakpoint(command, breakpoint);
        verify(change).execute();
        verify(change).setInt(1, 0);
    }

    private void changeBreakpoint(String command, GaussDBDebugBreakpointDescriptor breakpoint) throws DBGException {
        switch (command) {
            case "enable" -> session.enableBreakpoint(monitor, breakpoint);
            case "disable" -> session.disableBreakpoint(monitor, breakpoint);
            case "delete" -> session.removeBreakpoint(monitor, breakpoint);
            default -> throw new IllegalArgumentException(command);
        }
    }

    @Test
    void initialDisableCloseFailureKeepsAcknowledgedDisabledState() throws Exception {
        validBreakpoint(0);
        var breakpoint = new GaussDBDebugBreakpointDescriptor(172034, 4);
        breakpoint.setEnabled(false);
        JDBCPreparedStatement disable = mock(JDBCPreparedStatement.class);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.disable_breakpoint(?)")).thenReturn(disable);
        doThrow(new SQLException("disable close failed")).when(disable).close();
        assertThrows(DBGException.class, () -> session.addBreakpoint(monitor, breakpoint));
        assertSame(breakpoint, session.getBreakpoints().getFirst());
        assertFalse(breakpoint.isEnabled());
        session.disableBreakpoint(monitor, breakpoint);
        verify(disable).execute();
    }

    @Test
    void duplicateDeleteCloseFailureDoesNotRetainDeletedServerId() throws Exception {
        validBreakpoint(0);
        session.addBreakpoint(monitor, new GaussDBDebugBreakpointDescriptor(172034, 4));
        JDBCPreparedStatement replacement = validBreakpoint(7);
        JDBCPreparedStatement delete = mock(JDBCPreparedStatement.class);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.delete_breakpoint(?)")).thenReturn(delete);
        doThrow(new SQLException("delete close failed")).when(delete).close();
        var requested = new GaussDBDebugBreakpointDescriptor(172034, 4);
        assertThrows(DBGException.class, () -> session.addBreakpoint(monitor, requested));
        assertTrue(session.getBreakpoints().isEmpty());
        verify(replacement, never()).executeQuery();
        validBreakpoint(8);
        session.addBreakpoint(monitor, requested);
        verify(delete).execute();
        assertEquals(8, requested.getServerId());
        assertEquals(1, session.getBreakpoints().size());
    }

    @Test
    void failedDuplicateDeleteKeepsOriginalRegistrationAndDoesNotAddAgain() throws Exception {
        validBreakpoint(0);
        var original = new GaussDBDebugBreakpointDescriptor(172034, 4);
        session.addBreakpoint(monitor, original);
        JDBCPreparedStatement replacement = validBreakpoint(7);
        JDBCPreparedStatement delete = mock(JDBCPreparedStatement.class);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.delete_breakpoint(?)")).thenReturn(delete);
        when(delete.execute()).thenThrow(new SQLException("delete rejected", "XX000")).thenReturn(true);
        var requested = new GaussDBDebugBreakpointDescriptor(172034, 4);
        assertThrows(DBGException.class, () -> session.addBreakpoint(monitor, requested));
        assertSame(original, session.getBreakpoints().getFirst());
        verify(replacement, never()).executeQuery();
        validBreakpoint(7); // Each catalog query needs a fresh result cursor.
        session.addBreakpoint(monitor, requested);
        assertEquals(1, session.getBreakpoints().size());
        assertSame(requested, session.getBreakpoints().getFirst());
        assertEquals(7, requested.getServerId());
        verify(delete, times(2)).setInt(1, 0);
    }

    @Test
    void failedReplacementAddDoesNotRetryDeletionOfAlreadyDeletedId() throws Exception {
        validBreakpoint(0);
        session.addBreakpoint(monitor, new GaussDBDebugBreakpointDescriptor(172034, 4));
        JDBCPreparedStatement delete = mock(JDBCPreparedStatement.class);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.delete_breakpoint(?)")).thenReturn(delete);
        JDBCPreparedStatement failedAdd = validBreakpoint(7);
        when(failedAdd.executeQuery()).thenThrow(new SQLException("add rejected", "XX000"));
        var requested = new GaussDBDebugBreakpointDescriptor(172034, 4);
        assertThrows(DBGException.class, () -> session.addBreakpoint(monitor, requested));
        assertTrue(session.getBreakpoints().isEmpty());
        validBreakpoint(8);
        session.addBreakpoint(monitor, requested);
        verify(delete).execute();
        verify(delete).setInt(1, 0);
        assertEquals(1, session.getBreakpoints().size());
        assertEquals(8, requested.getServerId());
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

    private JDBCResultSet localVariableResult(String value) throws SQLException {
        JDBCStatement locals = mock(JDBCStatement.class);
        JDBCResultSet result = mock(JDBCResultSet.class);
        when(connection.createStatement()).thenReturn(locals);
        when(locals.executeQuery("SELECT * FROM DBE_PLDEBUGGER.info_locals(0)")).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getString("varname")).thenReturn("x");
        when(result.getString("vartype")).thenReturn("text");
        when(result.getString("value")).thenReturn(value);
        return result;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "4 + 5", "'中文''引号;--'", "NULL"})
    void variableExpressionsAreBoundAndOnlyServerReadbackBecomesCachedValue(String expression) throws Exception {
        JDBCPreparedStatement set = query("SELECT DBE_PLDEBUGGER.set_var(?, ?)", true, true, 0);
        localVariableResult("server representation");
        var variable = new GaussDBDebugVariable("x", "text", "original", null, false, 0);
        session.setVariableVal(variable, expression);
        verify(set).setString(1, "x");
        verify(set).setString(2, expression);
        verify(set).executeQuery();
        assertEquals("server representation", variable.getVal());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "query", "result-close", "statement-close"})
    void failedVariableReadbackDoesNotInventValueOrAutomaticallyRepeatAssignment(String failureStage) throws Exception {
        JDBCPreparedStatement set = query("SELECT DBE_PLDEBUGGER.set_var(?, ?)", true, true, 0);
        JDBCResultSet result = localVariableResult("8");
        JDBCStatement locals = connection.createStatement();
        SQLException failure = new SQLException("readback failure", "XX000");
        switch (failureStage) {
            case "missing" -> when(result.next()).thenReturn(false);
            case "query" -> when(locals.executeQuery(anyString())).thenThrow(failure);
            case "result-close" -> doThrow(failure).when(result).close();
            case "statement-close" -> doThrow(failure).when(locals).close();
            default -> throw new IllegalArgumentException(failureStage);
        }
        var variable = new GaussDBDebugVariable("x", "int4", "7", null, false, 0);
        DBGException actual = assertThrows(DBGException.class, () -> session.setVariableVal(variable, "x + 1"));
        if (!failureStage.equals("missing")) {
            assertSame(failure, actual.getCause());
        }
        assertEquals("7", variable.getVal(), "an unconfirmed cache is not a successful readback");
        verify(set).executeQuery();
        // Refresh independently: do not repeat an expression that may already have changed the target.
        localVariableResult("8");
        assertEquals("8", session.getVariables(null).getFirst().getVal());
        verify(set).executeQuery();
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(DBGTransactionAction.class)
    void confirmedTransactionStaysConfirmedWhenTimeoutRestorationFails(DBGTransactionAction action) throws Exception {
        completedTarget();
        doThrow(new SQLException("restoring timeout failed", "08006"))
            .when(connection).setNetworkTimeout(any(), eq(0));
        assertDoesNotThrow(() -> session.completeTransaction(monitor, action));
        assertFalse(session.isTransactionCompletionPending());
        // A second UI completion notification must not repeat an already acknowledged transaction.
        session.completeTransaction(monitor, action);
        verify(connection, times(action == DBGTransactionAction.COMMIT ? 1 : 0)).commit();
        verify(connection, times(action == DBGTransactionAction.ROLLBACK ? 1 : 0)).rollback();
        verify(connection).close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(DBGTransactionAction.class)
    void transactionFailureKeepsOriginalCauseWhenTimeoutRestorationAlsoFails(DBGTransactionAction action) throws Exception {
        completedTarget();
        SQLException failure = new SQLException("transaction acknowledgment lost", "08006");
        if (action == DBGTransactionAction.COMMIT) {
            doThrow(failure).when(connection).commit();
        } else {
            doThrow(failure).when(connection).rollback();
        }
        doThrow(new SQLException("secondary timeout restoration error"))
            .when(connection).setNetworkTimeout(any(), eq(0));
        DBGException actual = assertThrows(DBGException.class, () -> session.completeTransaction(monitor, action));
        assertSame(failure, actual.getCause());
        assertTrue(session.isTransactionCompletionPending());
        assertThrows(DBGException.class, () -> session.completeTransaction(monitor, DBGTransactionAction.COMMIT));
        verify(connection, times(action == DBGTransactionAction.COMMIT ? 1 : 0)).commit();
        verify(connection, times(action == DBGTransactionAction.ROLLBACK ? 1 : 0)).rollback();
        verify(connection).close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(DBGTransactionAction.class)
    void transactionBoundsLongTimeoutAndRestoresItInOrder(DBGTransactionAction action) throws Exception {
        completedTarget();
        when(connection.getNetworkTimeout()).thenReturn(20000);
        session.completeTransaction(monitor, action);
        var order = inOrder(connection);
        order.verify(connection).setNetworkTimeout(any(), eq(10000));
        if (action == DBGTransactionAction.COMMIT) {
            order.verify(connection).commit();
            verify(connection, never()).rollback();
        } else {
            order.verify(connection).rollback();
            verify(connection, never()).commit();
        }
        order.verify(connection).setNetworkTimeout(any(), eq(20000));
        order.verify(connection).close();
        assertFalse(session.isTransactionCompletionPending());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void timeoutSetupFailureDoesNotExecuteTransaction(boolean failRead) throws Exception {
        completedTarget();
        SQLException failure = new SQLException("timeout setup unavailable");
        if (failRead) {
            when(connection.getNetworkTimeout()).thenThrow(failure);
        } else {
            doThrow(failure).when(connection).setNetworkTimeout(any(), eq(10000));
        }
        DBGException actual = assertThrows(DBGException.class,
            () -> session.completeTransaction(monitor, DBGTransactionAction.COMMIT));
        assertSame(failure, actual.getCause());
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
        verify(connection).close();
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

    @Test
    void busyControllerIsDisconnectedBeforeTargetRollbackClose() throws Exception {
        var control = mock(JDBCExecutionContext.class);
        var target = mock(JDBCExecutionContext.class);
        field("controllerConnection", control);
        field("targetConnection", target);
        field("targetFinished", new CountDownLatch(1));
        var lockField = GaussDBDebugSession.class.getDeclaredField("controllerLock");
        lockField.setAccessible(true);
        var lock = (java.util.concurrent.locks.ReentrantLock) lockField.get(session);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var command = executor.submit(() -> {
                lock.lock();
                try {
                    held.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } finally {
                    lock.unlock();
                }
                return null;
            });
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                session.closeSession(monitor);
                var order = inOrder(control, target);
                order.verify(control).close();
                order.verify(target).close();
            } finally {
                release.countDown();
            }
            command.get(5, TimeUnit.SECONDS);
        }
    }
}
