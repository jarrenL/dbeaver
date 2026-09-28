/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.postgresql.model.session;

import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreSessionIdentityTest {
    private PostgreSession session(long pid, String database) throws Exception {
        var row = mock(ResultSet.class);
        when(row.getLong("pid")).thenReturn(pid);
        // Model the narrowing path explicitly; this is not a claim about a driver's overflow policy.
        when(row.getInt("pid")).thenReturn((int) pid);
        when(row.getString("datname")).thenReturn(database);
        return new PostgreSession(row);
    }

    @ParameterizedTest
    @ValueSource(longs = {42L, 2147483647L, 2147483648L, 140000000000000L, Long.MAX_VALUE})
    void fullIdentifierSurvivesModelAndCancelTerminateRequests(long pid) throws Exception {
        var object = session(pid, "appdb");
        assertEquals(pid, object.getPid());
        assertEquals(Long.toString(pid), object.getSessionId());
        assertEquals(pid + "@appdb", object.toString());
        var connection = mock(JDBCSession.class);
        var statement = mock(JDBCStatement.class);
        when(connection.createStatement()).thenReturn(statement);
        var manager = new PostgreSessionManager(mock(PostgreDataSource.class));
        manager.alterSession(connection, object.getSessionId(), Map.of(PostgreSessionManager.OPTION_QUERY_CANCEL, true));
        manager.alterSession(connection, object.getSessionId(), Map.of(PostgreSessionManager.OPTION_QUERY_CANCEL, false));
        verify(statement).execute("SELECT pg_catalog.pg_cancel_backend(" + pid + ")");
        verify(statement).execute("SELECT pg_catalog.pg_terminate_backend(" + pid + ")");
        verify(statement, times(2)).close();
        verify(connection, never()).close();
    }

    @Test
    void identifiersWithSameLowBitsRemainDistinct() throws Exception {
        var first = session(42L, "appdb");
        var second = session(4294967338L, "appdb");
        var duplicate = session(4294967338L, "appdb");
        var otherDatabase = session(4294967338L, "otherdb");
        assertNotEquals(first, second);
        assertEquals(second, duplicate);
        assertEquals(second.hashCode(), duplicate.hashCode());
        assertNotEquals(second, otherDatabase);
        assertEquals(3, new HashSet<>(java.util.List.of(first, second, duplicate, otherDatabase)).size());
        assertEquals("4294967338", session(4294967338L, null).toString());
    }
}
