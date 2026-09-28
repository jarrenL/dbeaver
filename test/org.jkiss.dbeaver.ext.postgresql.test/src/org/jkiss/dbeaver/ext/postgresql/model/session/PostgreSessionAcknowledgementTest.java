/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.postgresql.model.session;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreSessionAcknowledgementTest {
    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "true,null", "true,empty",
        "false,true", "false,false", "false,null", "false,empty"})
    void onlyExplicitTrueAcknowledgesSignal(boolean cancel, String response) throws Exception {
        var connection = mock(JDBCSession.class);
        var statement = mock(JDBCStatement.class);
        var result = mock(JDBCResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        String sql = "SELECT pg_catalog." + (cancel ? "pg_cancel_backend" : "pg_terminate_backend") + "(42)";
        when(statement.executeQuery(sql)).thenReturn(result);
        when(result.next()).thenReturn(!response.equals("empty"));
        when(result.getBoolean(1)).thenReturn(!response.equals("false"));
        when(result.wasNull()).thenReturn(response.equals("null"));
        var manager = new PostgreSessionManager(mock(PostgreDataSource.class));
        org.junit.jupiter.api.function.Executable action = () -> manager.alterSession(connection, "42",
            cancel ? Map.of(PostgreSessionManager.OPTION_QUERY_CANCEL, true) : null);
        if (response.equals("true")) {
            assertDoesNotThrow(action);
        } else {
            assertThrows(DBException.class, action);
        }
        verify(statement).executeQuery(sql);
        verify(statement, never()).execute(anyString());
        verify(result).close();
        verify(statement).close();
        verify(connection, never()).close();
    }
}
