/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.postgresql.model.session.PostgreSessionManager;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.admin.sessions.DBAServerSessionManager;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBSessionManagerTest {
    @ParameterizedTest
    @ValueSource(strings = {"08006", "42501", "22003", "null"})
    void unreadableIdentifierCannotPublishAPartialOrZeroPidSnapshot(String failureKind) throws Exception {
        var dataSource = mock(GaussDBDataSource.class);
        when(dataSource.getContainer()).thenReturn(mock(DBPDataSourceContainer.class));
        var session = mock(JDBCSession.class);
        when(session.getDataSource()).thenReturn(dataSource);
        var statement = mock(JDBCPreparedStatement.class);
        var result = mock(JDBCResultSet.class);
        when(result.getSession()).thenReturn(session);
        when(session.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, true, false);
        SQLException failure = new SQLException("PID unavailable", failureKind);
        if (failureKind.equals("null")) {
            when(result.getLong("pid")).thenReturn(42L, 0L);
            when(result.wasNull()).thenReturn(false, true);
        } else {
            when(result.getLong("pid")).thenReturn(42L).thenThrow(failure);
        }
        var manager = new PostgreSessionManager(dataSource);
        var actual = assertThrows(DBException.class, () -> manager.getSessions(session, Map.of()));
        if (failureKind.equals("null")) {
            assertEquals("22004", assertInstanceOf(SQLException.class, actual.getCause()).getSQLState());
        } else {
            assertSame(failure, actual.getCause());
        }
        verify(result).close();
        verify(statement).close();
        verify(session, never()).close();
        verify(session, never()).createStatement();
        // A subsequent explicit refresh can recover without retaining the incomplete snapshot.
        doReturn(43L).when(result).getLong("pid");
        when(result.wasNull()).thenReturn(false);
        when(result.next()).thenReturn(true, false);
        var recovered = manager.getSessions(session, Map.of());
        assertEquals(1, recovered.size());
        assertEquals("43", recovered.get(0).getSessionId());
        verify(result, times(2)).close();
        verify(statement, times(2)).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void inheritedAdapterReadsSessionsAndHonorsIdleFilter(boolean showIdle) throws Exception {
        var dataSource = mock(GaussDBDataSource.class);
        when(dataSource.getAdapter(DBAServerSessionManager.class)).thenCallRealMethod();
        var manager = assertInstanceOf(PostgreSessionManager.class, dataSource.getAdapter(DBAServerSessionManager.class));
        assertSame(dataSource, manager.getDataSource());
        var session = mock(JDBCSession.class);
        var statement = mock(JDBCPreparedStatement.class);
        var result = mock(JDBCResultSet.class);
        Map<String, Object> options = Map.of(PostgreSessionManager.OPTION_SHOW_IDLE, showIdle);
        String sql = "SELECT sa.* FROM pg_catalog.pg_stat_activity sa"
            + (showIdle ? "" : " where sa.state is null or sa.state not like 'idle%'");
        assertEquals(sql, manager.generateSessionReadQuery(options));
        when(session.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getLong("pid")).thenReturn(42L);
        when(result.getString("usename")).thenReturn(" developer ");
        when(result.getString("client_hostname")).thenReturn(" ");
        when(result.getString("client_addr")).thenReturn("2001:db8::1");
        when(result.getString("datname")).thenReturn("appdb");
        when(result.getString("query")).thenReturn("select '中文'");
        var sessions = manager.getSessions(session, options);
        assertEquals(1, sessions.size());
        assertEquals(42, sessions.get(0).getPid());
        assertEquals("developer", sessions.get(0).getUser());
        assertEquals("2001:db8::1", sessions.get(0).getClientHost());
        assertEquals("appdb", sessions.get(0).getDb());
        verify(result).close();
        verify(statement).close();
        verify(session, never()).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"prepare", "execute", "next", "close"})
    void sessionReadFailureIsNotReportedAsAnEmptySuccessfulSnapshot(String stage) throws Exception {
        var dataSource = mock(GaussDBDataSource.class);
        when(dataSource.getContainer()).thenReturn(mock(DBPDataSourceContainer.class));
        var manager = new PostgreSessionManager(dataSource);
        var session = mock(JDBCSession.class);
        when(session.getDataSource()).thenReturn(dataSource);
        var statement = mock(JDBCPreparedStatement.class);
        var result = mock(JDBCResultSet.class);
        when(session.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        var failure = new SQLException("session snapshot unavailable", "08006");
        switch (stage) {
            case "prepare" -> when(session.prepareStatement(anyString())).thenThrow(failure);
            case "execute" -> when(statement.executeQuery()).thenThrow(failure);
            case "next" -> when(result.next()).thenThrow(failure);
            case "close" -> doThrow(failure).when(result).close();
            default -> throw new AssertionError(stage);
        }
        var actual = assertThrows(DBException.class, () -> manager.getSessions(session, Map.of()));
        assertSame(failure, actual.getCause());
        verify(statement, times(stage.equals("prepare") ? 0 : 1)).close();
        verify(result, times(stage.equals("prepare") || stage.equals("execute") ? 0 : 1)).close();
        verify(session, never()).close();
    }
}
