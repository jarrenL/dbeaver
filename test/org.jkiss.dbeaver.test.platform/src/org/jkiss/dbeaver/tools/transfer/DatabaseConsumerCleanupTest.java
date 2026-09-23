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
package org.jkiss.dbeaver.tools.transfer;

import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCTransactionManager;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataBulkLoader;
import org.jkiss.dbeaver.model.struct.DBSDataManipulator;
import org.jkiss.dbeaver.model.struct.DBSDataManipulatorExt;
import org.jkiss.dbeaver.tools.transfer.database.DatabaseTransferConsumer;
import org.jkiss.dbeaver.tools.transfer.database.DatabaseConsumerSettings;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;

class DatabaseConsumerCleanupTest extends DBeaverUnitTest {
    private final VoidProgressMonitor monitor = new VoidProgressMonitor();
    private DatabaseTransferConsumer consumer;
    private DBCExecutionContext context;
    private DBCTransactionManager transactions;
    private DBCSession session;

    @BeforeEach
    void prepare() throws Exception {
        consumer = new DatabaseTransferConsumer();
        context = Mockito.mock(DBCExecutionContext.class,
            Mockito.withSettings().extraInterfaces(DBCTransactionManager.class));
        transactions = (DBCTransactionManager) context;
        session = Mockito.mock(DBCSession.class);
        Mockito.when(context.isConnected()).thenReturn(true);
        Mockito.when(transactions.isSupportsTransactions()).thenReturn(true);
        Mockito.when(session.getExecutionContext()).thenReturn(context);
        Mockito.when(session.getProgressMonitor()).thenReturn(monitor);
        set("targetContext", context);
        set("targetSession", session);
        set("oldAutoCommit", Boolean.TRUE);
        set("useIsolatedConnection", true);
    }

    @Test
    void rollbackPrecedesAutocommitRestoreAndConnectionClose() throws Exception {
        consumer.close();
        var order = Mockito.inOrder(transactions, session);
        order.verify(transactions).rollback(session, null);
        order.verify(transactions).setAutoCommit(monitor, true);
        order.verify(session).close();
        order.verify(context).close();
        Mockito.verify(transactions, Mockito.never()).commit(Mockito.any());
        consumer.close();
        Mockito.verify(session, Mockito.times(1)).close();
        Mockito.verify(context, Mockito.times(1)).close();
    }

    @Test
    void rollbackFailureStillClosesOwnedResourcesWithoutEnablingAutocommit() throws Exception {
        Mockito.doThrow(new RuntimeException("rollback failed")).when(transactions).rollback(session, null);
        consumer.close();
        Mockito.verify(transactions, Mockito.never()).setAutoCommit(Mockito.any(), Mockito.anyBoolean());
        Mockito.verify(session).close();
        Mockito.verify(context).close();
    }

    @Test
    void sharedContextIsNotClosedAndOriginalManualModeIsRestored() throws Exception {
        set("useIsolatedConnection", false);
        set("oldAutoCommit", Boolean.FALSE);
        consumer.close();
        Mockito.verify(transactions).rollback(session, null);
        Mockito.verify(transactions).setAutoCommit(monitor, false);
        Mockito.verify(session).close();
        Mockito.verify(context, Mockito.never()).close();
    }

    @Test
    void autocommitCleanupDoesNotIssueRollback() throws Exception {
        Mockito.when(transactions.isAutoCommit()).thenReturn(true);
        consumer.close();
        Mockito.verify(transactions, Mockito.never()).rollback(Mockito.any(), Mockito.any());
        Mockito.verify(transactions).setAutoCommit(monitor, true);
        Mockito.verify(session).close();
        Mockito.verify(context).close();
    }

    @Test
    void sessionCloseFailureStillClosesIsolatedContext() throws Exception {
        Mockito.doThrow(new RuntimeException("session close failed")).when(session).close();
        consumer.close();
        Mockito.verify(context).close();
    }

    @Test
    void sourceCancellationDiscardsPendingBatchWithoutCommit() throws Exception {
        DBCSession source = Mockito.mock(DBCSession.class);
        DBRProgressMonitor sourceMonitor = Mockito.mock(DBRProgressMonitor.class);
        Mockito.when(source.getProgressMonitor()).thenReturn(sourceMonitor);
        Mockito.when(sourceMonitor.isCanceled()).thenReturn(true);
        var batch = Mockito.mock(DBSDataManipulator.ExecuteBatch.class);
        set("executeBatch", batch);
        set("rowsExported", 3L);
        consumer.fetchEnd(source, Mockito.mock(DBCResultSet.class));
        Mockito.verify(batch).close();
        Mockito.verifyNoMoreInteractions(batch);
        Mockito.verify(transactions, Mockito.never()).commit(Mockito.any());
        consumer.close();
        Mockito.verify(transactions).rollback(session, null);
    }

    @Test
    void targetCancellationAlsoDiscardsPendingBatch() throws Exception {
        DBCSession source = Mockito.mock(DBCSession.class);
        Mockito.when(source.getProgressMonitor()).thenReturn(monitor);
        DBRProgressMonitor targetMonitor = Mockito.mock(DBRProgressMonitor.class);
        Mockito.when(targetMonitor.isCanceled()).thenReturn(true);
        Mockito.when(session.getProgressMonitor()).thenReturn(targetMonitor);
        var batch = Mockito.mock(DBSDataManipulator.ExecuteBatch.class);
        set("executeBatch", batch);
        set("rowsExported", 3L);
        consumer.fetchEnd(source, Mockito.mock(DBCResultSet.class));
        Mockito.verify(batch).close();
        Mockito.verifyNoMoreInteractions(batch);
        Mockito.verify(transactions, Mockito.never()).commit(Mockito.any());
        consumer.close();
        Mockito.verify(transactions).rollback(session, null);
    }

    @Test
    void canceledBulkLoadIsClosedWithoutFlushOrFinish() throws Exception {
        DBRProgressMonitor canceled = Mockito.mock(DBRProgressMonitor.class);
        Mockito.when(canceled.isCanceled()).thenReturn(true);
        Mockito.when(session.getProgressMonitor()).thenReturn(canceled);
        var bulk = Mockito.mock(DBSDataBulkLoader.BulkLoadManager.class);
        set("bulkLoadManager", bulk);
        set("rowsExported", 3L);
        consumer.fetchEnd(session, Mockito.mock(DBCResultSet.class));
        Mockito.verifyNoInteractions(bulk);
        consumer.close();
        Mockito.verify(bulk).close();
        Mockito.verifyNoMoreInteractions(bulk);
        Mockito.verify(transactions, Mockito.never()).commit(Mockito.any());
    }

    @Test
    void emptyFetchClosesBatchWithoutExecutingOrCommitting() throws Exception {
        var batch = Mockito.mock(DBSDataManipulator.ExecuteBatch.class);
        set("executeBatch", batch);
        consumer.fetchEnd(session, Mockito.mock(DBCResultSet.class));
        consumer.fetchEnd(session, Mockito.mock(DBCResultSet.class));
        Mockito.verify(batch).close();
        Mockito.verifyNoMoreInteractions(batch);
        Mockito.verify(transactions, Mockito.never()).commit(Mockito.any());
    }

    @Test
    void cancellationDuringFinalBulkFlushDoesNotFinishLoad() throws Exception {
        assertCancellationDuringFlush(false);
    }

    @Test
    void sourceCancellationDuringFinalBulkFlushDoesNotFinishLoad() throws Exception {
        assertCancellationDuringFlush(true);
    }

    private void assertCancellationDuringFlush(boolean cancelSource) throws Exception {
        DBRProgressMonitor progress = Mockito.mock(DBRProgressMonitor.class);
        DBCSession source = Mockito.mock(DBCSession.class);
        Mockito.when(source.getProgressMonitor()).thenReturn(cancelSource ? progress : monitor);
        Mockito.when(session.getProgressMonitor()).thenReturn(cancelSource ? monitor : progress);
        var target = Mockito.mock(DBSDataManipulatorExt.class);
        consumer.setTargetObject(target);
        set("targetAttributes", java.util.List.of());
        var bulk = Mockito.mock(DBSDataBulkLoader.BulkLoadManager.class);
        Mockito.doAnswer(invocation -> {
            Mockito.when(progress.isCanceled()).thenReturn(true);
            return null;
        }).when(bulk).flushRows(session);
        set("settings", Mockito.mock(DatabaseConsumerSettings.class));
        set("bulkLoadManager", bulk);
        set("rowsExported", 3L);
        consumer.fetchEnd(source, Mockito.mock(DBCResultSet.class));
        Mockito.verify(bulk).flushRows(session);
        Mockito.verify(bulk, Mockito.never()).finishBulkLoad(Mockito.any());
        Mockito.verifyNoInteractions(target);
        consumer.close();
        Mockito.verify(bulk).close();
        Mockito.verify(transactions, Mockito.never()).commit(Mockito.any());
    }

    @Test
    void nonCanceledBulkFetchStillFlushesAndFinishesBeforeClose() throws Exception {
        var bulk = Mockito.mock(DBSDataBulkLoader.BulkLoadManager.class);
        set("settings", Mockito.mock(DatabaseConsumerSettings.class));
        set("bulkLoadManager", bulk);
        set("rowsExported", 3L);
        consumer.fetchEnd(session, Mockito.mock(DBCResultSet.class));
        consumer.close();
        var order = Mockito.inOrder(bulk);
        order.verify(bulk).flushRows(session);
        order.verify(bulk).finishBulkLoad(session);
        order.verify(bulk).close();
    }

    private void set(String name, Object value) throws Exception {
        Field field = DatabaseTransferConsumer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(consumer, value);
    }
}
