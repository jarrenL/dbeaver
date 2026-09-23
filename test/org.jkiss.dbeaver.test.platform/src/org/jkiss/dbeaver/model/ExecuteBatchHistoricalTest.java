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
package org.jkiss.dbeaver.model;

import org.jkiss.dbeaver.model.data.DBDValueHandler;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.impl.data.ExecuteBatchImpl;
import org.jkiss.dbeaver.model.struct.DBSAttributeBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecuteBatchHistoricalTest {
    private static ExecuteBatchImpl batch(DBCStatement statement, int rows) throws Exception {
        var batch = new ExecuteBatchImpl(new DBSAttributeBase[0], null, true) {
            @Override
            protected DBCStatement prepareStatement(DBCSession session, DBDValueHandler[] handlers,
                Object[] values, Map<String, Object> options) {
                return statement;
            }

            @Override
            protected void bindStatement(DBDValueHandler[] handlers, DBCStatement target, Object[] values) {
                // Parameter binding has separate JDBC coverage. Exercise the real batch lifecycle here.
            }
        };
        for (int i = 0; i < rows; i++) batch.add(new Object[0]);
        return batch;
    }

    private static DBCSession session() {
        var session = mock(DBCSession.class, RETURNS_DEEP_STUBS);
        when(session.getDataSource().getInfo().supportsBatchUpdates()).thenReturn(true);
        return session;
    }

    @Test
    void aggregatedVendorCountsProduceCorrectTotalAndCloseStatement() throws Exception {
        var statement = mock(DBCStatement.class);
        when(statement.executeStatementBatch()).thenReturn(new long[]{4, 0, 0, 0});
        assertEquals(4, batch(statement, 4).execute(session(), Map.of()).getRowsUpdated());
        verify(statement, times(4)).addToBatch();
        verify(statement).executeStatementBatch();
        verify(statement).close();
    }

    @Test
    void individualCountsAndZeroMatchesAreSummedWithoutInventingRows() throws Exception {
        var statement = mock(DBCStatement.class);
        when(statement.executeStatementBatch()).thenReturn(new long[]{1, 0, 1, 0});
        assertEquals(2, batch(statement, 4).execute(session(), Map.of()).getRowsUpdated());
        verify(statement).close();
    }

    @Test
    void failedBatchPropagatesErrorAndClosesResources() throws Exception {
        var statement = mock(DBCStatement.class);
        var failure = new DBCException("Batch failed");
        when(statement.executeStatementBatch()).thenThrow(failure);
        assertSame(failure, assertThrows(DBCException.class, () -> batch(statement, 2).execute(session(), Map.of())));
        verify(statement).close();
    }

    @Test
    void cancellationBeforeFirstRowDoesNotExecuteAnything() throws Exception {
        var statement = mock(DBCStatement.class);
        var session = session();
        when(session.getProgressMonitor().isCanceled()).thenReturn(true);
        // DBCStatistics uses -1 for no update count, not an executed zero-row update.
        assertEquals(-1, batch(statement, 2).execute(session, Map.of()).getRowsUpdated());
        verifyNoInteractions(statement);
    }

    @Test
    void successfulBatchIsNotReplayedBySecondExecute() throws Exception {
        var statement = mock(DBCStatement.class);
        when(statement.executeStatementBatch()).thenReturn(new long[]{1, 1});
        var batch = batch(statement, 2);
        assertEquals(2, batch.execute(session(), Map.of()).getRowsUpdated());
        assertEquals(-1, batch.execute(session(), Map.of()).getRowsUpdated());
        verify(statement).executeStatementBatch();
    }

    @Test
    void batchWarningsAreRetainedInOrderWithoutChangingUpdateCount() throws Exception {
        var statement = mock(DBCStatement.class);
        var first = new java.sql.SQLWarning("First warning", "01000");
        var second = new java.sql.SQLWarning("Second warning", "01001");
        when(statement.executeStatementBatch()).thenReturn(new long[]{1, 1});
        when(statement.getStatementWarnings()).thenReturn(new Throwable[]{first, second});
        var statistics = batch(statement, 2).execute(session(), Map.of());
        assertEquals(2, statistics.getRowsUpdated());
        assertEquals(java.util.List.of(first, second), statistics.getWarnings());
        verify(statement).close();
    }

    @Test
    void disabledBatchUsesIndividualExecutionAndKeepsStatementReuse() throws Exception {
        var statement = mock(DBCStatement.class);
        when(statement.getUpdateRowCount()).thenReturn(1L, 0L, 1L);
        var statistics = batch(statement, 3).execute(session(), Map.of(
            org.jkiss.dbeaver.model.struct.DBSDataManipulator.OPTION_DISABLE_BATCHES, true));
        assertEquals(2, statistics.getRowsUpdated());
        verify(statement, times(3)).executeStatement();
        verify(statement, never()).addToBatch();
        verify(statement, never()).executeStatementBatch();
        verify(statement).close();
    }

    @Test
    void individualExecutionFailureRetainsCauseAndStopsRemainingRows() throws Exception {
        var statement = mock(DBCStatement.class);
        var sqlFailure = new java.sql.SQLException("Duplicate fixture key", "23505");
        var failure = new DBCException("Fixture execution failed", sqlFailure);
        when(statement.executeStatement()).thenReturn(false).thenThrow(failure);
        when(statement.getUpdateRowCount()).thenReturn(1L);
        assertSame(failure, assertThrows(DBCException.class, () -> batch(statement, 3).execute(session(), Map.of(
            org.jkiss.dbeaver.model.struct.DBSDataManipulator.OPTION_DISABLE_BATCHES, true))));
        assertSame(sqlFailure, failure.getCause());
        verify(statement, times(2)).executeStatement();
        verify(statement).close();
    }

    @Test
    void cancellationAfterFirstQueuedRowDoesNotQueueRemainingRows() throws Exception {
        var statement = mock(DBCStatement.class);
        var session = session();
        when(session.getProgressMonitor().isCanceled()).thenReturn(false, true);
        when(statement.executeStatementBatch()).thenReturn(new long[]{1});
        // Current contract flushes already queued rows; cancellation is not an automatic rollback.
        assertEquals(1, batch(statement, 3).execute(session, Map.of()).getRowsUpdated());
        verify(statement).addToBatch();
        verify(statement).executeStatementBatch();
        verify(statement).close();
    }
}
