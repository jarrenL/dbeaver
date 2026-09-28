/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.postgresql.model.plan;

import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.plan.DBCQueryPlannerConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.SQLXML;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgrePlanTransactionTest {
    private JDBCSession session() {
        var session = mock(JDBCSession.class);
        when(session.getExecutionContext()).thenReturn(mock(org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext.class));
        return session;
    }

    private PostgreExecutionPlan plan(JDBCSession session, boolean malformed) throws Exception {
        var plan = new PostgreExecutionPlan(false, false, "SELECT 1", new DBCQueryPlannerConfiguration());
        var statement = mock(JDBCStatement.class);
        var result = mock(JDBCResultSet.class);
        var xml = mock(SQLXML.class);
        String text = malformed ? "<" : "<explain><Query><Plan><Node-Type>Result</Node-Type></Plan></Query></explain>";
        when(session.createStatement()).thenReturn(statement);
        when(statement.executeQuery(plan.getPlanQueryString())).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getSQLXML(1)).thenReturn(xml);
        when(xml.getBinaryStream()).thenReturn(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        when(xml.getString()).thenReturn(text);
        return plan;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void manualTransactionRollsBackOnlyItsOwnSavepoint(boolean malformed) throws Exception {
        var session = session();
        Savepoint savepoint = mock(Savepoint.class);
        when(session.setSavepoint()).thenReturn(savepoint);
        var plan = plan(session, malformed);
        if (malformed) assertThrows(DBCException.class, () -> plan.explain(session));
        else plan.explain(session);
        var order = inOrder(session);
        order.verify(session).setSavepoint();
        order.verify(session).createStatement();
        order.verify(session).rollback(savepoint);
        order.verify(session).releaseSavepoint(savepoint);
        verify(session, never()).rollback();
        verify(session, never()).commit();
        verify(session, never()).setAutoCommit(anyBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"state", "savepoint"})
    void setupFailureMustNotExecuteOrRollbackUserTransaction(String stage) throws Exception {
        var session = session();
        SQLException original = new SQLException("synthetic transaction setup failure");
        if (stage.equals("state")) when(session.getAutoCommit()).thenThrow(original);
        else when(session.setSavepoint()).thenThrow(original);
        var plan = new PostgreExecutionPlan(false, false, "SELECT 1", new DBCQueryPlannerConfiguration());
        assertSame(original, assertThrows(DBCException.class, () -> plan.explain(session)).getCause());
        verify(session, never()).createStatement();
        verify(session, never()).rollback();
        verify(session, never()).setAutoCommit(anyBoolean());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rollbackFailureIsReportedAndDoesNotRestoreAutocommit(boolean malformed) throws Exception {
        var session = session();
        when(session.getAutoCommit()).thenReturn(true);
        SQLException rollback = new SQLException("synthetic rollback failure");
        doThrow(rollback).when(session).rollback();
        var plan = plan(session, malformed);
        DBCException failure = assertThrows(DBCException.class, () -> plan.explain(session));
        if (malformed) assertArrayEquals(new Throwable[]{rollback}, failure.getSuppressed());
        else assertSame(rollback, failure.getCause());
        verify(session).setAutoCommit(false);
        verify(session, never()).setAutoCommit(true);
        verify(session, never()).commit();
    }
}
