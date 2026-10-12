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
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GaussDBBreakpointBoundaryTest {
    private static final String VALIDATE_SQL =
        "SELECT canbreak FROM DBE_PLDEBUGGER.info_code(?::oid) WHERE lineno=?";
    private static final String ADD_SQL =
        "SELECT DBE_PLDEBUGGER.add_breakpoint(?::oid, ?::integer)";

    private final DBRProgressMonitor monitor = mock(DBRProgressMonitor.class);
    private JDBCSession connection;
    private GaussDBDebugSession session;

    @BeforeEach
    void setUp() {
        JDBCExecutionContext context = mock(JDBCExecutionContext.class);
        connection = mock(JDBCSession.class);
        when(context.openSession(any(), any(), anyString())).thenReturn(connection);
        session = new GaussDBDebugSession(
            mock(GaussDBDebugController.class), context, context, mock(GaussDBProcedure.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, Integer.MAX_VALUE})
    void markerRepresentableLineBoundariesAreSentWithoutNarrowing(long line) throws Exception {
        JDBCPreparedStatement validate = mock(JDBCPreparedStatement.class);
        JDBCResultSet validation = mock(JDBCResultSet.class);
        when(connection.prepareStatement(VALIDATE_SQL)).thenReturn(validate);
        when(validate.executeQuery()).thenReturn(validation);
        when(validation.next()).thenReturn(true);
        when(validation.getBoolean(1)).thenReturn(true);

        JDBCPreparedStatement add = mock(JDBCPreparedStatement.class);
        JDBCResultSet registration = mock(JDBCResultSet.class);
        when(connection.prepareStatement(ADD_SQL)).thenReturn(add);
        when(add.executeQuery()).thenReturn(registration);
        when(registration.next()).thenReturn(true);
        when(registration.getInt(1)).thenReturn(71);

        var descriptor = new GaussDBDebugBreakpointDescriptor(901L, line, "review_db");
        session.addBreakpoint(monitor, descriptor);

        verify(validate).setLong(1, 901L);
        verify(validate).setInt(2, Math.toIntExact(line));
        verify(add).setString(1, "901");
        verify(add).setInt(2, Math.toIntExact(line));
        assertSame(descriptor, session.getBreakpoints().getFirst());
        assertEquals(71, descriptor.getServerId());
    }

    @ParameterizedTest
    @ValueSource(longs = {-100, -1, 0})
    void nonPositiveSourceLinesCannotCreateServerBreakpoints(long line) throws Exception {
        JDBCPreparedStatement validate = mock(JDBCPreparedStatement.class);
        JDBCResultSet validation = mock(JDBCResultSet.class);
        when(connection.prepareStatement(VALIDATE_SQL)).thenReturn(validate);
        when(validate.executeQuery()).thenReturn(validation);
        when(validation.next()).thenReturn(false);

        DBGException failure = assertThrows(DBGException.class,
            () -> session.addBreakpoint(monitor, new GaussDBDebugBreakpointDescriptor(902L, line)));

        assertTrue(failure.getMessage().contains(Long.toString(line)));
        verify(validate).setLong(1, 902L);
        verify(validate).setInt(2, Math.toIntExact(line));
        verify(connection, never()).prepareStatement(ADD_SQL);
        assertTrue(session.getBreakpoints().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(longs = {((long) Integer.MIN_VALUE) - 1, ((long) Integer.MAX_VALUE) + 1, Long.MIN_VALUE, Long.MAX_VALUE})
    void sourceLinesOutsideTheJdbcIntegerRangeAreNeverTruncated(long line) throws Exception {
        JDBCPreparedStatement validate = mock(JDBCPreparedStatement.class);
        when(connection.prepareStatement(VALIDATE_SQL)).thenReturn(validate);

        assertThrows(ArithmeticException.class,
            () -> session.addBreakpoint(monitor, new GaussDBDebugBreakpointDescriptor(903L, line)));

        verify(validate).setLong(1, 903L);
        verify(validate, never()).executeQuery();
        verify(connection, never()).prepareStatement(ADD_SQL);
        assertTrue(session.getBreakpoints().isEmpty());
    }
}
