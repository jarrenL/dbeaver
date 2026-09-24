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
package org.jkiss.dbeaver.runtime.qm;

import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.qm.QMEventAction;
import org.jkiss.dbeaver.model.qm.QMMetaEvent;
import org.jkiss.dbeaver.model.qm.meta.QMMConnectionInfo;
import org.jkiss.dbeaver.model.qm.meta.QMMProjectInfo;
import org.jkiss.dbeaver.model.qm.meta.QMMStatementExecuteInfo;
import org.jkiss.dbeaver.model.qm.meta.QMMStatementInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryHistoryEventMapperTest {
    @TempDir
    Path directory;

    private QMMStatementExecuteInfo execution() {
        var connection = mock(QMMConnectionInfo.class);
        when(connection.isLoggingEnabled()).thenReturn(true);
        when(connection.getProjectId()).thenReturn("project-id");
        when(connection.getContainerId()).thenReturn("connection-id");
        when(connection.getContainerName()).thenReturn("验收连接");
        when(connection.getDriverId()).thenReturn("gaussdb:gaussdb");
        when(connection.getProjectInfo()).thenReturn(QMMProjectInfo.builder().setId("project-id").setName("项目名称").build());
        when(connection.getContextName()).thenReturn("SQL 编辑器");
        var statement = mock(QMMStatementInfo.class);
        when(statement.getPurpose()).thenReturn(DBCExecutionPurpose.USER);
        var execution = mock(QMMStatementExecuteInfo.class);
        when(execution.getConnection()).thenReturn(connection);
        when(execution.getStatement()).thenReturn(statement);
        when(execution.isClosed()).thenReturn(true);
        when(execution.getOpenTime()).thenReturn(100L);
        when(execution.getCloseTime()).thenReturn(120L);
        when(execution.getFetchBeginTime()).thenReturn(120L);
        when(execution.getFetchEndTime()).thenReturn(150L);
        when(execution.getQueryString()).thenReturn("SELECT '中文'\n-- 多行");
        when(execution.getSchema()).thenReturn("模式");
        when(execution.getCatalog()).thenReturn("database");
        when(execution.getFetchRowCount()).thenReturn(42L);
        when(execution.getUpdateRowCount()).thenReturn(-1L);
        return execution;
    }

    private QMMetaEvent event(QMMStatementExecuteInfo execution) {
        return new QMMetaEvent(execution, QMEventAction.END, 120, "current-session");
    }

    @Test
    void completedSelectSurvivesFileReopenAsDetachedClosedMetadata() throws Exception {
        var source = execution();
        var id = UUID.randomUUID();
        var snapshot = QueryHistoryEventMapper.snapshot(id, event(source));
        assertNotNull(snapshot);
        var file = directory.resolve("history.json");
        new QueryHistoryStore(file, 10).put(snapshot);
        var restored = QueryHistoryEventMapper.restore(new QueryHistoryStore(file, 10).getEntries().getFirst());
        var query = (QMMStatementExecuteInfo) restored.getObject();
        assertEquals(source.getQueryString(), query.getQueryString());
        assertEquals(42, query.getFetchRowCount());
        assertEquals(-1, query.getUpdateRowCount());
        assertEquals(50, query.getDuration());
        assertTrue(query.isClosed());
        assertFalse(query.isFetching());
        assertNull(query.getStatement().getReference());
        assertNull(query.getSavepoint());
        assertNull(query.getConnection().getTransaction());
        assertEquals("project-id", query.getConnection().getProjectId());
        assertEquals("项目名称", query.getConnection().getProjectInfo().getName());
        assertEquals("SQL 编辑器", query.getConnection().getContextName());
        assertEquals("connection-id", query.getConnection().getContainerId());
        assertEquals("验收连接", query.getConnection().getContainerName());
        assertEquals("gaussdb:gaussdb", query.getConnection().getDriverId());
        assertEquals("模式", query.getSchema());
        assertEquals("database", query.getCatalog());
        assertEquals("history:" + id, restored.getSessionId());
        assertNotEquals("current-session", restored.getSessionId());
    }

    @Test
    void dmlUpdateCountAndErrorStatusAreNotConfusedWithFetchCount() {
        var source = execution();
        when(source.getUpdateRowCount()).thenReturn(7L);
        when(source.getFetchRowCount()).thenReturn(0L);
        when(source.isTransactional()).thenReturn(true);
        when(source.getErrorCode()).thenReturn(42);
        when(source.getErrorMessage()).thenReturn("失败\n说明");
        var restored = (QMMStatementExecuteInfo) QueryHistoryEventMapper.restore(
            QueryHistoryEventMapper.snapshot(UUID.randomUUID(), event(source))).getObject();
        assertEquals(7, restored.getUpdateRowCount());
        assertEquals(0, restored.getFetchRowCount());
        assertTrue(restored.hasError());
        assertTrue(restored.isTransactional());
        assertEquals(42, restored.getErrorCode());
        assertEquals("失败\n说明", restored.getErrorMessage());
    }

    @Test
    void activeFetchingAndDeletedQueriesAreNotPersisted() {
        var source = execution();
        when(source.isClosed()).thenReturn(false);
        assertNull(QueryHistoryEventMapper.snapshot(UUID.randomUUID(), event(source)));
        when(source.isClosed()).thenReturn(true);
        when(source.isFetching()).thenReturn(true);
        assertNull(QueryHistoryEventMapper.snapshot(UUID.randomUUID(), event(source)));
        when(source.isFetching()).thenReturn(false);
        when(source.isHistoryDeleted()).thenReturn(true);
        assertNull(QueryHistoryEventMapper.snapshot(UUID.randomUUID(), event(source)));
    }

    @Test
    void disabledLoggingAndBlankSqlAreNotPersisted() {
        var source = execution();
        when(source.getConnection().isLoggingEnabled()).thenReturn(false);
        assertNull(QueryHistoryEventMapper.snapshot(UUID.randomUUID(), event(source)));
        when(source.getConnection().isLoggingEnabled()).thenReturn(true);
        when(source.getQueryString()).thenReturn(null, " \n\t");
        assertNull(QueryHistoryEventMapper.snapshot(UUID.randomUUID(), event(source)));
        assertNull(QueryHistoryEventMapper.snapshot(UUID.randomUUID(), event(source)));
    }

    @Test
    void nonQueryEventsAreNotConvertedToSql() {
        var event = new QMMetaEvent(mock(QMMConnectionInfo.class), QMEventAction.END, 120, "session");
        assertNull(QueryHistoryEventMapper.snapshot(UUID.randomUUID(), event));
    }

    @Test
    void missingDisplayNameFallsBackToStableConnectionId() {
        var source = execution();
        when(source.getConnection().getContainerName()).thenReturn(null);
        assertEquals("connection-id", QueryHistoryEventMapper.snapshot(UUID.randomUUID(), event(source)).dataSourceName());
    }
}
