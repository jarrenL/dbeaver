/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBDebugRoleTest {
    private static final String ADMIN_SQL = "SELECT (r.rolsuper OR r.rolsystemadmin) "
        + "FROM pg_catalog.pg_roles r WHERE r.rolname=current_user";
    private static final String MEMBER_SQL = "SELECT pg_catalog.pg_has_role(current_user,'gs_role_pldebugger','member')";
    private final JDBCSession session = mock(JDBCSession.class);

    private JDBCPreparedStatement query(String sql, boolean value) throws SQLException {
        JDBCPreparedStatement statement = mock(JDBCPreparedStatement.class);
        JDBCResultSet result = mock(JDBCResultSet.class);
        when(session.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getBoolean(1)).thenReturn(value);
        return statement;
    }

    @Test
    void acceptsMemberWithoutRequiringVisibilityOfBuiltinRole() throws Exception {
        query(ADMIN_SQL, false);
        query(MEMBER_SQL, true);
        assertDoesNotThrow(() -> GaussDBDebugCapabilityDetector.checkDebuggerRole(session));
        verify(session).prepareStatement(MEMBER_SQL);
    }

    @Test
    void acceptsAdministratorWithoutLookingUpOptionalRole() throws Exception {
        query(ADMIN_SQL, true);
        assertDoesNotThrow(() -> GaussDBDebugCapabilityDetector.checkDebuggerRole(session));
        verify(session, never()).prepareStatement(MEMBER_SQL);
    }

    @Test
    void rejectsOrdinaryNonMember() throws Exception {
        query(ADMIN_SQL, false);
        query(MEMBER_SQL, false);
        assertThrows(DBGException.class, () -> GaussDBDebugCapabilityDetector.checkDebuggerRole(session));
    }

    @Test
    void rejectsNonAdministratorWhenRoleDoesNotExist() throws Exception {
        query(ADMIN_SQL, false);
        when(query(MEMBER_SQL, false).executeQuery()).thenThrow(new SQLException("role missing", "42704"));
        assertThrows(DBGException.class, () -> GaussDBDebugCapabilityDetector.checkDebuggerRole(session));
    }

    @Test
    void doesNotHideUnexpectedDatabaseErrors() throws Exception {
        query(ADMIN_SQL, false);
        SQLException failure = new SQLException("connection lost", "08006");
        when(query(MEMBER_SQL, false).executeQuery()).thenThrow(failure);
        assertSame(failure, assertThrows(SQLException.class, () -> GaussDBDebugCapabilityDetector.checkDebuggerRole(session)));
    }
}
