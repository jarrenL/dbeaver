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
package org.jkiss.dbeaver.ext.postgresql.model;

import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreSequenceDdlTest {
    private void assertLoadedCache(boolean modern) throws Exception {
        var source = mock(PostgreDataSource.class);
        var schema = mock(PostgreSchema.class);
        var database = mock(PostgreDatabase.class);
        var context = mock(PostgreExecutionContext.class);
        var session = mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCSession.class);
        var statement = mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        var rows = mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet.class);
        when(schema.getDataSource()).thenReturn(source);
        when(schema.getName()).thenReturn("test_schema");
        when(source.getDefaultInstance()).thenReturn(database);
        when(source.isServerVersionAtLeast(10, 0)).thenReturn(modern);
        when(database.isInstanceConnected()).thenReturn(true);
        when(database.getDefaultContext(any(), eq(true))).thenReturn(context);
        when(context.openSession(any(), any(), anyString())).thenReturn(session);
        when(session.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getLong("start_value")).thenReturn(10L);
        when(rows.getLong("min_value")).thenReturn(1L);
        when(rows.getLong("max_value")).thenReturn(1000L);
        when(rows.getLong("increment_by")).thenReturn(3L);
        when(rows.getLong(modern ? "cache_size" : "cache_value")).thenReturn(64L);
        var sequence = new PostgreSequence(schema) {
            @Override
            public String getFullyQualifiedName(org.jkiss.dbeaver.model.DBPEvaluationContext evaluationContext) {
                return "test_schema.test_sequence";
            }
        };
        var monitor = mock(DBRProgressMonitor.class);
        assertEquals(64L, sequence.getAdditionalInfo(monitor).getCacheValue());
        var ddl = new StringBuilder();
        sequence.getSequenceBody(monitor, ddl, false);
        assertTrue(ddl.toString().contains("CACHE 64"));
        verify(session, times(1)).close();
        verify(statement, times(1)).close();
        verify(rows, times(1)).close();
    }

    @Test
    void legacySequenceCatalogUsesCacheValueColumn() throws Exception {
        assertLoadedCache(false);
    }

    @Test
    void modernSequenceViewUsesCacheSizeColumn() throws Exception {
        assertLoadedCache(true);
    }

    private String body(long start, long min, long max, long increment, boolean cycle) throws Exception {
        var info = new PostgreSequence.AdditionalInfo();
        info.setLoaded(true);
        info.setStartValue(start);
        info.setMinValue(min);
        info.setMaxValue(max);
        info.setIncrementBy(increment);
        info.setCacheValue(1);
        info.setCycled(cycle);
        var sequence = new PostgreSequence(mock(PostgreSchema.class)) {
            @Override
            public AdditionalInfo getAdditionalInfo(DBRProgressMonitor monitor) {
                return info;
            }
        };
        var sql = new StringBuilder();
        sequence.getSequenceBody(mock(DBRProgressMonitor.class), sql, false);
        return sql.toString();
    }

    @Test
    void descendingSequencePreservesNegativeIncrement() throws Exception {
        assertTrue(body(-2, -100, -1, -3, false).contains("INCREMENT BY -3"));
    }

    @Test
    void loadedNegativeBoundsAreNotReplacedByDefaults() throws Exception {
        String ddl = body(-2, -100, -1, -3, false);
        assertTrue(ddl.contains("MINVALUE -100"));
        assertTrue(ddl.contains("MAXVALUE -1"));
        assertTrue(ddl.contains("START -2"));
        assertFalse(ddl.contains("NO MINVALUE"));
        assertFalse(ddl.contains("NO MAXVALUE"));
    }

    @Test
    void zeroMaximumIsARealLoadedBound() throws Exception {
        assertTrue(body(-1, -100, 0, -1, false).contains("MAXVALUE 0"));
    }

    @Test
    void ascendingSequenceRetainsExplicitCycleAndCache() throws Exception {
        String ddl = body(10, 1, 100, 3, true);
        assertTrue(ddl.contains("INCREMENT BY 3"));
        assertTrue(ddl.contains("MINVALUE 1"));
        assertTrue(ddl.contains("MAXVALUE 100"));
        assertTrue(ddl.contains("START 10"));
        assertTrue(ddl.contains("CACHE 1"));
        assertTrue(ddl.endsWith("CYCLE"));
        assertFalse(ddl.contains("NO CYCLE"));
    }
}
