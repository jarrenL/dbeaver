/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.model.qm.meta;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPObject;
import org.jkiss.dbeaver.model.edit.*;
import org.jkiss.dbeaver.model.exec.*;
import org.jkiss.dbeaver.model.impl.edit.AbstractCommandContext;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandContextTransactionBoundaryTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @SuppressWarnings({"rawtypes", "unchecked"})
    void explicitAtomicOptionRollsBackWhileLegacyUiFlagStillCommitsPerCommand(boolean databaseAtomic) throws Exception {
        var execution = mock(DBCExecutionContext.class, withSettings().extraInterfaces(DBCTransactionManager.class));
        var session = mock(DBCSession.class);
        when(execution.openSession(any(), any(), anyString())).thenReturn(session);
        var context = new AbstractCommandContext(execution, true) {};
        var transaction = (DBCTransactionManager) execution;
        when(transaction.isSupportsTransactions()).thenReturn(true);
        when(transaction.isAutoCommit()).thenReturn(databaseAtomic);
        when(execution.isConnected()).thenReturn(true);
        var source = mock(org.jkiss.dbeaver.model.DBPDataSource.class, RETURNS_DEEP_STUBS);
        when(source.getInfo().supportsTransactionsForDDL()).thenReturn(true);
        when(execution.getDataSource()).thenReturn(source);
        var manager = mock(DBEObjectManager.class);
        var first = mock(DBECommand.class);
        var second = mock(DBECommand.class);
        var firstAction = mock(DBEPersistAction.class);
        var secondAction = mock(DBEPersistAction.class);
        when(firstAction.getType()).thenReturn(DBEPersistAction.ActionType.NORMAL);
        when(secondAction.getType()).thenReturn(DBEPersistAction.ActionType.NORMAL);
        when(first.getPersistActions(any(), any(), any())).thenReturn(new DBEPersistAction[]{firstAction});
        when(second.getPersistActions(any(), any(), any())).thenReturn(new DBEPersistAction[]{secondAction});
        var failure = new DBException("Synthetic second command failure");
        doThrow(failure).when(manager).executePersistAction(session, second, secondAction);
        var type = Class.forName(AbstractCommandContext.class.getName() + "$CommandQueue");
        var constructor = type.getDeclaredConstructor(DBEObjectManager.class, type, DBPObject.class);
        constructor.setAccessible(true);
        var queue = (java.util.Collection) constructor.newInstance(manager, null, mock(DBPObject.class));
        queue.add(first);
        queue.add(second);
        var field = AbstractCommandContext.class.getDeclaredField("commandQueues");
        field.setAccessible(true);
        field.set(context, List.of(queue));
        var execute = AbstractCommandContext.class.getDeclaredMethod("executeCommands",
            org.jkiss.dbeaver.model.runtime.DBRProgressMonitor.class, Map.class, DBCTransactionManager.class);
        execute.setAccessible(true);
        if (databaseAtomic) {
            assertSame(failure, assertThrows(DBException.class,
                () -> context.saveChanges(new VoidProgressMonitor(), Map.of(DBECommandContext.OPTION_ATOMIC_TRANSACTION, true))));
            var order = inOrder(manager, transaction);
            order.verify(transaction).setAutoCommit(any(), eq(false));
            order.verify(manager).executePersistAction(session, first, firstAction);
            order.verify(manager).executePersistAction(session, second, secondAction);
            order.verify(transaction).rollback(session, null);
            order.verify(transaction).setAutoCommit(any(), eq(true));
            verify(transaction, never()).commit(any());
            verify(first, never()).updateModel();
            verify(second, never()).updateModel();
            return;
        }
        var thrown = assertThrows(InvocationTargetException.class,
            () -> execute.invoke(context, new VoidProgressMonitor(), Map.of(), transaction));
        assertSame(failure, thrown.getCause());
        var order = inOrder(manager, transaction);
        order.verify(manager).executePersistAction(session, first, firstAction);
        order.verify(transaction).commit(session);
        order.verify(manager).executePersistAction(session, second, secondAction);
        verify(transaction, times(1)).commit(session);
        verify(first).updateModel();
        verify(second, never()).updateModel();
    }
}
