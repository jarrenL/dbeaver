/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObjectState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GaussDBPackageStateRefreshTest {
    private final VoidProgressMonitor monitor = new VoidProgressMonitor();
    private JDBCSession session;
    private JDBCPreparedStatement statement;
    private JDBCResultSet result;
    private GaussDBPackage object;

    @BeforeEach
    void setup() throws Exception {
        session = mock(JDBCSession.class);
        statement = mock(JDBCPreparedStatement.class);
        result = mock(JDBCResultSet.class);
        when(session.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        JDBCResultSet cached = mock(JDBCResultSet.class);
        when(cached.getLong("oid")).thenReturn(73L);
        when(cached.getString("name")).thenReturn("test_package");
        when(cached.getString("spec_valid")).thenReturn("true");
        when(cached.getString("body_valid")).thenReturn("true");
        when(cached.getBoolean("body_present")).thenReturn(true);
        var schema = mock(GaussDBSchema.class);
        var database = mock(GaussDBDatabase.class);
        var context = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreExecutionContext.class);
        when(schema.getParentObject()).thenReturn(database);
        when(database.isInstanceConnected()).thenReturn(true);
        when(database.getDefaultContext(any(), eq(true))).thenReturn(context);
        when(context.openSession(any(), any(), anyString())).thenReturn(session);
        object = new GaussDBPackage(session, schema, cached);
        assertSame(DBSObjectState.NORMAL, object.getObjectState());
    }

    private void refresh() throws Exception {
        object.refreshObjectState(monitor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"08006", "28P01", "42501", "42P01"})
    void failedQueryCannotLeaveOldValidityAndCanRecover(String state) throws Exception {
        SQLException failure = new SQLException("catalog unavailable", state);
        when(statement.executeQuery()).thenThrow(failure);
        if (state.equals("42501") || state.equals("42P01")) {
            assertDoesNotThrow(this::refresh);
        } else {
            assertSame(failure, assertThrows(DBCException.class, this::refresh).getCause());
        }
        assertSame(DBSObjectState.UNKNOWN, object.getSpecificationState());
        assertSame(DBSObjectState.UNKNOWN, object.getBodyState());
        assertSame(DBSObjectState.UNKNOWN, object.getObjectState());
        verify(statement).close();
        verify(session).close();
        doReturn(result).when(statement).executeQuery();
        when(result.next()).thenReturn(true, false);
        when(result.getString("object_type")).thenReturn("S");
        when(result.getString("valid")).thenReturn("t");
        refresh();
        assertSame(DBSObjectState.NORMAL, object.getObjectState());
        assertFalse(object.isBodyPresent());
        verify(statement, times(2)).setLong(1, 73L);
    }

    @Test
    void emptyCatalogClearsPreviouslyPresentBodyAndValidity() throws Exception {
        refresh();
        assertSame(DBSObjectState.UNKNOWN, object.getObjectState());
        assertFalse(object.isBodyPresent());
        verify(result).close();
    }

    @Test
    void invalidBodyMakesPackageInvalidRegardlessOfRowOrder() throws Exception {
        when(result.next()).thenReturn(true, true, false);
        when(result.getString("object_type")).thenReturn("B", "S");
        when(result.getString("valid")).thenReturn("false", "true");
        refresh();
        assertTrue(object.isBodyPresent());
        assertSame(DBSObjectState.NORMAL, object.getSpecificationState());
        assertSame(DBSObjectState.INVALID, object.getBodyState());
        assertSame(DBSObjectState.INVALID, object.getObjectState());
    }
}
