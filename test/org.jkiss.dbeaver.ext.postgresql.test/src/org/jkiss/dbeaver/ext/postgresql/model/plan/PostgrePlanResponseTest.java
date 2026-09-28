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
import java.sql.SQLXML;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgrePlanResponseTest {
    private static final String VALID = "<explain><Query><Plan><Node-Type>Result</Node-Type>"
        + "<Plan-Rows>1</Plan-Rows></Plan></Query></explain>";

    @ParameterizedTest
    @ValueSource(strings = {"no-row", "null-xml", "<", "<explain/>", "<explain><Query/></explain>", "read-source"})
    void invalidResponseIsReportedClearsPreviousPlanAndCanRetry(String response) throws Exception {
        var plan = new PostgreExecutionPlan(false, false, "SELECT 1", new DBCQueryPlannerConfiguration());
        execute(plan, VALID, false);
        assertEquals(1, plan.getPlanNodes(Map.of()).size());
        assertEquals(VALID, plan.getPlanSourceData());
        execute(plan, response, true);
        assertTrue(plan.getPlanNodes(Map.of()).isEmpty());
        assertNull(plan.getPlanSourceData());
        execute(plan, VALID, false);
        assertEquals(1, plan.getPlanNodes(Map.of()).size());
        assertEquals("Result", plan.getPlanNodes(Map.of()).getFirst().getNodeType());
    }

    private void execute(PostgreExecutionPlan plan, String response, boolean fails) throws Exception {
        var session = mock(JDBCSession.class);
        when(session.getExecutionContext()).thenReturn(mock(org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext.class));
        var statement = mock(JDBCStatement.class);
        var result = mock(JDBCResultSet.class);
        when(session.getAutoCommit()).thenReturn(true);
        when(session.createStatement()).thenReturn(statement);
        when(statement.executeQuery(plan.getPlanQueryString())).thenReturn(result);
        when(result.next()).thenReturn(!response.equals("no-row"), false);
        if (!response.equals("null-xml")) {
            SQLXML xml = mock(SQLXML.class);
            String payload = response.equals("read-source") ? VALID : response;
            when(xml.getString()).thenReturn(payload);
            if (response.equals("read-source")) {
                when(xml.getString()).thenThrow(new java.sql.SQLException("Synthetic source read failure", "08006"));
            }
            when(xml.getBinaryStream()).thenReturn(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
            when(result.getSQLXML(1)).thenReturn(xml);
        }
        if (fails) assertThrows(DBCException.class, () -> plan.explain(session));
        else plan.explain(session);
        verify(result).close();
        verify(statement).close();
        verify(session).rollback();
        verify(session).setAutoCommit(true);
    }
}
