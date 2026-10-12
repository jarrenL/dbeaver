/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GaussDBPackageBreakpointIdentityTest {
    private static final String VALIDATE_SQL =
        "SELECT canbreak FROM DBE_PLDEBUGGER.info_code(?::oid) WHERE lineno=?";
    private static final String ADD_SQL =
        "SELECT DBE_PLDEBUGGER.add_breakpoint(?::oid, ?::integer)";

    @Test
    void packageMembersAtTheSameSourceLineRemainIsolatedByCatalogOid() throws Exception {
        JDBCExecutionContext context = mock(JDBCExecutionContext.class);
        JDBCSession connection = mock(JDBCSession.class);
        when(context.openSession(any(), any(), anyString())).thenReturn(connection);

        JDBCPreparedStatement validate = mock(JDBCPreparedStatement.class);
        JDBCResultSet validation = mock(JDBCResultSet.class);
        when(connection.prepareStatement(VALIDATE_SQL)).thenReturn(validate);
        when(validate.executeQuery()).thenReturn(validation);
        when(validation.next()).thenReturn(true, true);
        when(validation.getBoolean(1)).thenReturn(true);

        JDBCPreparedStatement add = mock(JDBCPreparedStatement.class);
        JDBCResultSet registration = mock(JDBCResultSet.class);
        when(connection.prepareStatement(ADD_SQL)).thenReturn(add);
        when(add.executeQuery()).thenReturn(registration);
        when(registration.next()).thenReturn(true, true);
        when(registration.getInt(1)).thenReturn(31, 32);

        JDBCPreparedStatement delete = mock(JDBCPreparedStatement.class);
        when(connection.prepareStatement("SELECT DBE_PLDEBUGGER.delete_breakpoint(?)")).thenReturn(delete);

        var session = new GaussDBDebugSession(
            mock(GaussDBDebugController.class), context, context, mock(GaussDBProcedure.class));
        DBRProgressMonitor monitor = mock(DBRProgressMonitor.class);
        var packageProcedure = new GaussDBDebugBreakpointDescriptor(7101L, 8, "review_db");
        var sameNamedPackageFunction = new GaussDBDebugBreakpointDescriptor(7202L, 8, "review_db");

        session.addBreakpoint(monitor, packageProcedure);
        session.addBreakpoint(monitor, sameNamedPackageFunction);

        assertEquals(2, session.getBreakpoints().size());
        assertSame(packageProcedure, GaussDBDebugSession.findMatchingBreakpoint(
            sessionBreakpoints(session), new GaussDBDebugBreakpointDescriptor(7101L, 8)));
        assertSame(sameNamedPackageFunction, GaussDBDebugSession.findMatchingBreakpoint(
            sessionBreakpoints(session), new GaussDBDebugBreakpointDescriptor(7202L, 8)));
        assertNull(GaussDBDebugSession.findMatchingBreakpoint(
            sessionBreakpoints(session), new GaussDBDebugBreakpointDescriptor(7303L, 8)));

        var validationOrder = inOrder(validate);
        validationOrder.verify(validate).setLong(1, 7101L);
        validationOrder.verify(validate).setInt(2, 8);
        validationOrder.verify(validate).executeQuery();
        validationOrder.verify(validate).setLong(1, 7202L);
        validationOrder.verify(validate).setInt(2, 8);
        validationOrder.verify(validate).executeQuery();

        session.removeBreakpoint(monitor, new GaussDBDebugBreakpointDescriptor(7101L, 8));

        verify(delete).setInt(1, 31);
        verify(delete).execute();
        assertEquals(1, session.getBreakpoints().size());
        assertSame(sameNamedPackageFunction, session.getBreakpoints().getFirst());
    }

    @SuppressWarnings("unchecked")
    private static java.util.Collection<GaussDBDebugBreakpointDescriptor> sessionBreakpoints(
        GaussDBDebugSession session
    ) {
        return (java.util.Collection<GaussDBDebugBreakpointDescriptor>) (java.util.Collection<?>) session.getBreakpoints();
    }
}
