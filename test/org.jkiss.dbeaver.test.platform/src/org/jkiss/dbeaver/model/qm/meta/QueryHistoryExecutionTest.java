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
package org.jkiss.dbeaver.model.qm.meta;

import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryHistoryExecutionTest {
    @Test
    void rollbackCollectorUsesTransactionEndTimeWhileConnectionRemainsOpen() throws Exception {
        var connection = historyConnection();
        connection.changeTransactional(true);
        var transaction = connection.getTransaction();
        var context = mock(org.jkiss.dbeaver.model.exec.DBCExecutionContext.class, RETURNS_DEEP_STUBS);
        var project = context.getDataSource().getContainer().getProject();
        when(project.getSessionContext().getPrimaryAuthSpace()).thenReturn(null);
        when(project.getWorkspace().getAuthContext().getSpaceSession(any(), any(), eq(false))).thenReturn(null);
        var collector = mock(org.jkiss.dbeaver.runtime.qm.QMMCollectorImpl.class, CALLS_REAL_METHODS);
        doReturn(connection).when(collector).getConnectionInfo(context);
        var events = new java.util.ArrayList<org.jkiss.dbeaver.model.qm.QMMetaEvent>();
        var field = org.jkiss.dbeaver.runtime.qm.QMMCollectorImpl.class.getDeclaredField("eventPool");
        field.setAccessible(true);
        field.set(collector, events);
        // Skip the constructor's asynchronous dispatcher, but execute the actual handler and event creation.
        collector.handleTransactionRollback(context, null);
        assertEquals(1, events.size());
        assertSame(transaction, events.getFirst().getObject());
        assertEquals(org.jkiss.dbeaver.model.qm.QMEventAction.END, events.getFirst().getAction());
        assertEquals(0, connection.getCloseTime());
        assertTrue(transaction.getCloseTime() > 0);
        assertEquals(transaction.getCloseTime(), events.getFirst().getTimestamp());
        assertEquals(transaction.getCloseTime(), org.jkiss.dbeaver.model.qm.QMUtils.getObjectEventTime(events.getFirst()));
    }

    private QMMConnectionInfo historyConnection() {
        return new QMMConnectionInfo(1000, 0, null, "fixture", "fixture", "gaussdb",
            new org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration(), "instance", "editor", false);
    }

    @Test
    void rollbackAndLaterAutoCommitTransitionRetainSeparateHistoryEvents() {
        var connection = historyConnection();
        connection.changeTransactional(true);
        var original = connection.getTransaction();
        long opened = original.getOpenTime();
        assertTrue(opened > 0);
        assertSame(original, connection.rollback(null));
        assertTrue(original.isClosed());
        assertFalse(original.isCommitted());
        assertEquals(opened, original.getOpenTime());
        assertTrue(original.getCloseTime() >= opened);
        var next = connection.getTransaction();
        assertNotSame(original, next);
        assertSame(original, next.getPrevious());
        assertSame(next, connection.changeTransactional(false));
        assertTrue(next.isCommitted());
        assertFalse(original.isCommitted(), "Auto-commit transition must not relabel the preceding rollback");
        assertEquals(opened, original.getOpenTime());
    }

    @Test
    void commitClosesCurrentTransactionAndStartsIndependentHistory() {
        var connection = historyConnection();
        connection.changeTransactional(true);
        var original = connection.getTransaction();
        assertSame(original, connection.commit());
        assertTrue(original.isClosed());
        assertTrue(original.isCommitted());
        assertTrue(original.getCurrentSavepoint().isCommitted());
        assertFalse(connection.getTransaction().isClosed());
        assertSame(original, connection.getTransaction().getPrevious());
    }

    @Test
    void closingHistoryConnectionRollsBackUnfinishedTransaction() {
        var connection = historyConnection();
        connection.changeTransactional(true);
        var original = connection.getTransaction();
        connection.close();
        assertTrue(connection.isClosed());
        assertNull(connection.getTransaction());
        assertTrue(original.isClosed());
        assertFalse(original.isCommitted());
        assertFalse(original.getCurrentSavepoint().isCommitted());
        connection.close();
        assertFalse(original.isCommitted());
    }

    private QMMStatementExecuteInfo create(String sql, DBCExecutionPurpose purpose, boolean modifying) throws Exception {
        var statement = mock(QMMStatementInfo.class);
        when(statement.getPurpose()).thenReturn(purpose);
        var dialect = mock(SQLDialect.class);
        when(dialect.isTransactionModifyingQuery(sql)).thenReturn(modifying);
        // OSGi gives the model and test bundles different class loaders, even in the same package.
        var constructor = QMMStatementExecuteInfo.class.getDeclaredConstructor(QMMStatementInfo.class,
            QMMTransactionSavepointInfo.class, String.class, QMMStatementExecuteInfo.class,
            SQLDialect.class, String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(statement, null, sql, null, dialect, "测试模式", "test_database");
    }

    private void invoke(QMMStatementExecuteInfo execution, String name, Class<?>[] types, Object... arguments) throws Exception {
        var method = QMMStatementExecuteInfo.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(execution, arguments);
    }

    @Test
    void successfulSelectRetainsTextContextAndFetchedRows() throws Exception {
        var execution = create("SELECT '中文'", DBCExecutionPurpose.USER, false);
        assertFalse(execution.isClosed());
        assertEquals(-1, execution.getDuration());
        invoke(execution, "beginFetch", new Class<?>[0]);
        assertTrue(execution.isFetching());
        invoke(execution, "endFetch", new Class<?>[]{long.class}, 23L);
        invoke(execution, "close", new Class<?>[]{long.class, Throwable.class}, -1L, null);
        assertFalse(execution.isFetching());
        assertTrue(execution.isClosed());
        assertFalse(execution.hasError());
        assertFalse(execution.isTransactional());
        assertEquals(23, execution.getFetchRowCount());
        assertEquals(-1, execution.getUpdateRowCount());
        assertEquals("SELECT '中文'", execution.getText());
        assertEquals("测试模式", execution.getSchema());
        assertEquals("test_database", execution.getCatalog());
    }

    @Test
    void dmlAffectedRowsAreSeparateFromFetchedRows() throws Exception {
        var execution = create("UPDATE t SET x=1", DBCExecutionPurpose.USER, true);
        invoke(execution, "close", new Class<?>[]{long.class, Throwable.class}, 7L, null);
        assertTrue(execution.isTransactional());
        assertEquals(7, execution.getUpdateRowCount());
        assertEquals(0, execution.getFetchRowCount());
    }

    @Test
    void metadataQueriesAreNotMarkedModifyingByDialectAlone() throws Exception {
        var execution = create("SELECT metadata", DBCExecutionPurpose.META, true);
        invoke(execution, "close", new Class<?>[]{long.class, Throwable.class}, -1L, null);
        assertFalse(execution.isTransactional());
    }

    @Test
    void failedQueryPreservesVendorErrorAndTransactionState() throws Exception {
        var execution = create("SELECT missing", DBCExecutionPurpose.USER, false);
        invoke(execution, "close", new Class<?>[]{long.class, Throwable.class}, -1L,
            new SQLException("fixture missing relation", "42P01", 4321));
        assertTrue(execution.hasError());
        assertEquals(4321, execution.getErrorCode());
        assertEquals("fixture missing relation", execution.getErrorMessage());
        assertTrue(execution.isTransactional());
        assertTrue(execution.isClosed());
    }

    @Test
    void restoredHistoryDurationIncludesExecutionAndCompletedFetch() {
        var restored = new QMMStatementExecuteInfo(1000, 1050, mock(QMMStatementInfo.class),
            "SELECT 1", 4, 0, null, 1100, 1130, false, "s", "d");
        assertEquals(80, restored.getDuration());
        assertEquals(4, restored.getFetchRowCount());
        assertFalse(restored.hasError());
        assertFalse(restored.isFetching());
    }

    @Test
    void clearingHistoryErrorDoesNotEraseSqlText() throws Exception {
        var execution = create("SELECT 1", DBCExecutionPurpose.USER, false);
        invoke(execution, "setError", new Class<?>[]{int.class, String.class}, 1, "fixture error");
        assertTrue(execution.hasError());
        invoke(execution, "setError", new Class<?>[]{int.class, String.class}, 0, "");
        assertFalse(execution.hasError());
        assertEquals("SELECT 1", execution.getQueryString());
    }
}
