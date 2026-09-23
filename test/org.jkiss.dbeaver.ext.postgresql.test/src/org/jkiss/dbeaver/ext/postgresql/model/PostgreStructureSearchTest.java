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

import org.jkiss.dbeaver.DBDatabaseException;
import org.jkiss.dbeaver.model.exec.jdbc.*;
import org.jkiss.dbeaver.model.impl.struct.RelationalObjectType;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreStructureSearchTest {
    private PostgreDataSource source;
    private PostgreExecutionContext context;
    private PostgreDatabase database;
    private JDBCSession session;
    private JDBCPreparedStatement statement;
    private JDBCResultSet result;
    private DBRProgressMonitor monitor;
    private PostgreStructureAssistant assistant;

    @BeforeEach
    void setup() throws Exception {
        source = mock(PostgreDataSource.class, RETURNS_DEEP_STUBS);
        context = mock(PostgreExecutionContext.class);
        database = mock(PostgreDatabase.class);
        monitor = mock(DBRProgressMonitor.class);
        session = mock(JDBCSession.class);
        statement = mock(JDBCPreparedStatement.class);
        result = mock(JDBCResultSet.class);
        when(context.getDataSource()).thenReturn(source);
        when(context.getDefaultCatalog()).thenReturn(database);
        when(context.openSession(eq(monitor), any(), anyString())).thenReturn(session);
        when(session.getProgressMonitor()).thenReturn(monitor);
        when(session.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        assistant = new PostgreStructureAssistant(source);
    }

    private DBSStructureAssistant.ObjectsSearchParams params() {
        var params = new DBSStructureAssistant.ObjectsSearchParams(
            new DBSObjectType[] {RelationalObjectType.TYPE_TABLE}, "name'%;--");
        params.setGlobalSearch(true);
        params.setMaxResults(12);
        return params;
    }

    @Test
    void maskIsBoundAndSessionStatementAndResultAreClosed() throws Exception {
        var params = params();
        params.setCaseSensitive(true);
        assertTrue(assistant.findObjectsByMask(monitor, context, params).isEmpty());
        verify(session).prepareStatement(argThat(sql -> sql.contains(" LIKE ?") && sql.endsWith("LIMIT 12")
            && !sql.contains(params.getMask())));
        verify(statement).setString(1, params.getMask());
        verify(result).close();
        verify(statement).close();
        verify(session).close();
    }

    @Test
    void queryFailureIsWrappedAndResourcesAreClosed() throws Exception {
        var failure = new SQLException("fixture failure", "08006");
        when(statement.executeQuery()).thenThrow(failure);
        var error = assertThrows(DBDatabaseException.class, () -> assistant.findObjectsByMask(monitor, context, params()));
        assertSame(failure, error.getCause());
        verify(statement).close();
        verify(session).close();
    }

    @Test
    void filterMatchingNoSchemasMustNotBecomeAnUnrestrictedSearch() throws Exception {
        var schema = mock(PostgreSchema.class);
        when(schema.getName()).thenReturn("excluded");
        when(database.getSchemas(monitor)).thenReturn(List.of(schema));
        var filter = new DBSObjectFilter("allowed", null);
        filter.setEnabled(true);
        when(source.getContainer().getObjectFilter(PostgreSchema.class, database, true)).thenReturn(filter);
        assertTrue(assistant.findObjectsByMask(monitor, context, params()).isEmpty());
        verify(context, never()).openSession(any(), any(), anyString());
    }

    @Test
    void schemaFilterBindsOnlyMatchingSchemaIdsAfterCommentMask() throws Exception {
        var allowed = mock(PostgreSchema.class);
        when(allowed.getName()).thenReturn("allowed");
        when(allowed.getObjectId()).thenReturn(42L);
        var excluded = mock(PostgreSchema.class);
        when(excluded.getName()).thenReturn("excluded");
        when(excluded.getObjectId()).thenReturn(99L);
        when(database.getSchemas(monitor)).thenReturn(List.of(allowed, excluded));
        var filter = new DBSObjectFilter("allowed", null);
        filter.setEnabled(true);
        when(source.getContainer().getObjectFilter(PostgreSchema.class, database, true)).thenReturn(filter);
        var params = params();
        params.setSearchInComments(true);
        assertTrue(assistant.findObjectsByMask(monitor, context, params).isEmpty());
        verify(session).prepareStatement(argThat(sql -> sql.contains("ILIKE ?")
            && sql.contains("pc.relnamespace IN (?)") && sql.contains("obj_description")));
        verify(statement).setString(1, params.getMask());
        verify(statement).setString(2, params.getMask());
        verify(statement).setLong(3, 42L);
        verify(statement, never()).setLong(anyInt(), eq(99L));
    }

    @Test
    void missingSchemaIsSkippedWithoutPublishingAnUnresolvableReference() throws Exception {
        when(result.next()).thenReturn(true, false);
        when(result.getString("relkind")).thenReturn("r");
        when(result.getString("relname")).thenReturn("orphan");
        when(result.getLong("relnamespace")).thenReturn(42L);
        assertTrue(assistant.findObjectsByMask(monitor, context, params()).isEmpty());
        verify(result).close();
    }

    @ParameterizedTest
    @CsvSource({"r,PostgreTable", "p,PostgreTable", "f,PostgreTableForeign", "v,PostgreView", "m,PostgreMaterializedView"})
    void relationKindsKeepTheirTypeAndResolveTheCorrectObject(String kind, String expectedClass) throws Exception {
        var schema = mock(PostgreSchema.class);
        var target = mock(PostgreTableBase.class);
        when(result.next()).thenReturn(true, false);
        when(result.getString("relkind")).thenReturn(kind);
        when(result.getString("relname")).thenReturn("target");
        when(result.getLong("relnamespace")).thenReturn(42L);
        when(result.getLong("oid")).thenReturn(123L);
        when(database.getSchema(monitor, 42L)).thenReturn(schema);
        when(schema.getTable(monitor, 123L)).thenReturn(target);
        var references = assistant.findObjectsByMask(monitor, context, params());
        assertEquals(1, references.size());
        var reference = references.get(0);
        assertEquals(expectedClass, reference.getObjectClass().getSimpleName());
        assertEquals("target", reference.getName());
        assertSame(target, reference.resolveObject(monitor));
        when(schema.getTable(monitor, 123L)).thenReturn(null);
        assertThrows(org.jkiss.dbeaver.DBException.class, () -> reference.resolveObject(monitor));
    }

    @Test
    void cancellationPreventsReadingRowsAndStillClosesResources() throws Exception {
        when(monitor.isCanceled()).thenReturn(true);
        assertTrue(assistant.findObjectsByMask(monitor, context, params()).isEmpty());
        verify(result, never()).next();
        verify(result).close();
        verify(statement).close();
        verify(session).close();
    }
}
