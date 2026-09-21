/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.jkiss.dbeaver.debug.*;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DebugTransactionCompletionTest {
    @Test
    void failedDatabaseOperationDoesNotTerminateTheTarget() throws Exception {
        var monitor = mock(DBRProgressMonitor.class);
        var session = mock(DBGSession.class);
        var terminate = mock(DBGTransactionCompletion.SuccessAction.class);
        var failure = new DBGException("connection lost");
        doThrow(failure).when(session).completeTransaction(monitor, DBGTransactionAction.COMMIT);
        assertSame(failure, assertThrows(DBGException.class, () ->
            DBGTransactionCompletion.complete(monitor, session, DBGTransactionAction.COMMIT, terminate)));
        verifyNoInteractions(terminate);
    }

    @Test
    void successTerminatesOnlyAfterDatabaseAcknowledgment() throws Exception {
        var monitor = mock(DBRProgressMonitor.class);
        var session = mock(DBGSession.class);
        var terminate = mock(DBGTransactionCompletion.SuccessAction.class);
        DBGTransactionCompletion.complete(monitor, session, DBGTransactionAction.ROLLBACK, terminate);
        var order = inOrder(session, terminate);
        order.verify(session).completeTransaction(monitor, DBGTransactionAction.ROLLBACK);
        order.verify(terminate).run();
    }

    @Test
    void preCanceledTaskDoesNotTouchDatabaseOrTerminate() {
        var monitor = mock(DBRProgressMonitor.class);
        when(monitor.isCanceled()).thenReturn(true);
        var session = mock(DBGSession.class);
        var terminate = mock(DBGTransactionCompletion.SuccessAction.class);
        assertThrows(DBGException.class, () ->
            DBGTransactionCompletion.complete(monitor, session, DBGTransactionAction.COMMIT, terminate));
        verifyNoInteractions(session, terminate);
    }
}
