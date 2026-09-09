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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GaussDBDebugSessionTest {
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
}
