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
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> failureStages() {
        return java.util.stream.Stream.of(true, false).flatMap(cancel ->
            java.util.stream.Stream.of("create", "execute", "next", "value", "wasNull", "resultClose", "statementClose", "combined")
                .map(stage -> org.junit.jupiter.params.provider.Arguments.of(cancel, stage)));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("failureStages")
    void jdbcFailuresPreserveCauseAndNeverReplaySignal(boolean cancel, String stage) throws Exception {
        var source = mock(PostgreDataSource.class);
        when(source.getContainer()).thenReturn(mock(org.jkiss.dbeaver.model.DBPDataSourceContainer.class));
        var connection = mock(JDBCSession.class);
        when(connection.getDataSource()).thenReturn(source);
        var statement = mock(JDBCStatement.class);
        var result = mock(JDBCResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        String sql = "SELECT pg_catalog." + (cancel ? "pg_cancel_backend" : "pg_terminate_backend") + "(42)";
        when(statement.executeQuery(sql)).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(true);
        var failure = new java.sql.SQLException("failure at " + stage, "08006");
        var resultClose = new java.sql.SQLException("result close failed", "08006");
        var statementClose = new java.sql.SQLException("statement close failed", "08006");
        switch (stage) {
            case "create" -> when(connection.createStatement()).thenThrow(failure);
            case "execute" -> when(statement.executeQuery(sql)).thenThrow(failure);
            case "next" -> when(result.next()).thenThrow(failure);
            case "value" -> when(result.getBoolean(1)).thenThrow(failure);
            case "wasNull" -> when(result.wasNull()).thenThrow(failure);
            case "resultClose" -> doThrow(failure).when(result).close();
            case "statementClose" -> doThrow(failure).when(statement).close();
            case "combined" -> {
                when(result.getBoolean(1)).thenThrow(failure);
                doThrow(resultClose).when(result).close();
                doThrow(statementClose).when(statement).close();
            }
            default -> throw new AssertionError(stage);
        }
        var manager = new PostgreSessionManager(source);
        var actual = assertThrows(DBException.class, () -> manager.alterSession(connection, "42",
            Map.of(PostgreSessionManager.OPTION_QUERY_CANCEL, cancel)));
        assertSame(failure, actual.getCause());
        assertArrayEquals(stage.equals("combined") ? new Throwable[] {resultClose, statementClose} : new Throwable[0],
            failure.getSuppressed());
        verify(connection).createStatement();
        verify(statement, times(stage.equals("create") ? 0 : 1)).executeQuery(sql);
        verify(statement, times(stage.equals("create") ? 0 : 1)).close();
        verify(result, times(stage.equals("create") || stage.equals("execute") ? 0 : 1)).close();
        verify(statement, never()).execute(anyString());
        verify(connection, never()).close();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void rejectedSignalRemainsPrimaryWhenBothResourcesFailToClose(boolean cancel) throws Exception {
        var connection = mock(JDBCSession.class);
        var statement = mock(JDBCStatement.class);
        var result = mock(JDBCResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(false);
        var first = new java.sql.SQLException("result close failed");
        var second = new java.sql.SQLException("statement close failed");
        doThrow(first).when(result).close();
        doThrow(second).when(statement).close();
        var manager = new PostgreSessionManager(mock(PostgreDataSource.class));
        var actual = assertThrows(DBException.class, () -> manager.alterSession(connection, "42",
            Map.of(PostgreSessionManager.OPTION_QUERY_CANCEL, cancel)));
        assertEquals(org.jkiss.dbeaver.ext.postgresql.internal.PostgreSQLMessages.session_operation_not_confirmed,
            actual.getMessage());
        assertArrayEquals(new Throwable[] {first, second}, actual.getSuppressed());
        verify(statement).executeQuery(anyString());
        verify(result).close();
        verify(statement).close();
        verify(connection, never()).close();
    }

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
