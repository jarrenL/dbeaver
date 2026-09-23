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
    @Test
    void commitFailurePreventsAtomicAndLegacyReplayEvenAfterSuccessfulRollback() throws Exception {
        var f = new AtomicFixture();
        var lostReply = new DBCException("commit response lost");
        doThrow(lostReply).when(f.transaction).commit(f.session);
        assertSame(lostReply, assertThrows(DBException.class, f::save).getCause());
        assertThrows(DBException.class, f::save);
        assertThrows(DBException.class, () -> f.context.saveChanges(f.monitor, Map.of()));
        verify(f.transaction, times(1)).commit(f.session);
        verify(f.manager, times(1)).executePersistAction(f.session, f.first, f.action);
        verify(f.first, never()).updateModel();
    }

    @Test
    void restoreFailurePreservesStatementErrorAndPreventsReplay() throws Exception {
        var f = new AtomicFixture();
        var statementError = new DBException("statement failed");
        var restoreError = new DBCException("restore failed");
        doThrow(statementError).when(f.manager).executePersistAction(f.session, f.second, f.action);
        doThrow(restoreError).when(f.transaction).setAutoCommit(any(), eq(true));
        assertSame(statementError, assertThrows(DBException.class, f::save));
        assertArrayEquals(new Throwable[]{restoreError}, statementError.getSuppressed());
        assertThrows(DBException.class, f::save);
        verify(f.manager, times(1)).executePersistAction(f.session, f.first, f.action);
    }

    @Test
    void committedCommandsCannotReplayWhenAutoCommitRestorationFails() throws Exception {
        var f = new AtomicFixture();
        var restoreError = new DBCException("restore failed");
        doThrow(restoreError).when(f.transaction).setAutoCommit(any(), eq(true));
        assertSame(restoreError, assertThrows(DBException.class, f::save));
        assertFalse(f.context.isDirty());
        assertThrows(DBException.class, f::save);
        verify(f.transaction, times(1)).commit(f.session);
        verify(f.first).updateModel();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void sensitiveCommandSuppressesLoggingAndRestoresItEvenWhenActionFails(boolean fail) throws Exception {
        var f = new AtomicFixture();
        when(f.session.isLoggingEnabled()).thenReturn(true);
        when(f.first.isDisableSessionLogging()).thenReturn(true);
        if (fail) {
            doThrow(new DBException("sensitive action failed")).when(f.manager)
                .executePersistAction(f.session, f.first, f.action);
            assertThrows(DBException.class, f::save);
        } else {
            f.save();
        }
        var order = inOrder(f.session, f.manager);
        order.verify(f.session).enableLogging(false);
        order.verify(f.manager).executePersistAction(f.session, f.first, f.action);
        order.verify(f.session).enableLogging(true);
        verify(f.session, times(1)).enableLogging(false);
        verify(f.session, times(1)).enableLogging(true);
    }

    @Test
    void disabledSessionLoggingIsNeverEnabledByAtomicSave() throws Exception {
        var f = new AtomicFixture();
        when(f.session.isLoggingEnabled()).thenReturn(false);
        when(f.first.isDisableSessionLogging()).thenReturn(true);
        f.save();
        verify(f.session, never()).enableLogging(anyBoolean());
    }

    @Test
    void successfulAtomicSaveCommitsOnceBeforeModelUpdates() throws Exception {
        var f = new AtomicFixture();
        f.save();
        var order = inOrder(f.manager, f.transaction, f.first, f.second);
        order.verify(f.manager).executePersistAction(f.session, f.first, f.action);
        order.verify(f.manager).executePersistAction(f.session, f.second, f.action);
        order.verify(f.transaction).commit(f.session);
        order.verify(f.first).updateModel();
        order.verify(f.second).updateModel();
        verify(f.transaction, times(1)).commit(any());
        verify(f.transaction, never()).rollback(any(), any());
        assertFalse(f.context.isDirty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"manual", "disconnected", "noDdlTransactions", "noTransactions"})
    void unsupportedAtomicContextDoesNotExecuteOrChangeTransaction(String condition) throws Exception {
        var f = new AtomicFixture();
        switch (condition) {
            case "manual" -> when(f.transaction.isAutoCommit()).thenReturn(false);
            case "disconnected" -> when(f.execution.isConnected()).thenReturn(false);
            case "noDdlTransactions" -> when(f.source.getInfo().supportsTransactionsForDDL()).thenReturn(false);
            case "noTransactions" -> when(f.transaction.isSupportsTransactions()).thenReturn(false);
        }
        assertThrows(DBException.class, f::save);
        verifyNoInteractions(f.manager);
        verify(f.transaction, never()).setAutoCommit(any(), anyBoolean());
        verify(f.transaction, never()).commit(any());
        verify(f.transaction, never()).rollback(any(), any());
    }

    @Test
    void cancelBetweenCommandsRollsBackWithoutPublishingModels() throws Exception {
        var f = new AtomicFixture();
        doAnswer(invocation -> { when(f.monitor.isCanceled()).thenReturn(true); return null; })
            .when(f.manager).executePersistAction(f.session, f.first, f.action);
        assertThrows(DBException.class, f::save);
        verify(f.manager, never()).executePersistAction(f.session, f.second, f.action);
        verify(f.transaction).rollback(f.session, null);
        verify(f.transaction, never()).commit(any());
        verify(f.first, never()).updateModel();
        verify(f.second, never()).updateModel();
    }

    @Test
    void rollbackFailureIsSuppressedAndNeverRestoresAutoCommit() throws Exception {
        var f = new AtomicFixture();
        var failure = new DBException("statement failed");
        var rollback = new DBCException("rollback failed");
        doThrow(failure).when(f.manager).executePersistAction(f.session, f.second, f.action);
        doThrow(rollback).when(f.transaction).rollback(f.session, null);
        assertSame(failure, assertThrows(DBException.class, f::save));
        assertArrayEquals(new Throwable[]{rollback}, failure.getSuppressed());
        verify(f.transaction, never()).setAutoCommit(any(), eq(true));
        verify(f.transaction, never()).commit(any());
        verify(f.first, never()).updateModel();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static class AtomicFixture {
        final DBCExecutionContext execution = mock(DBCExecutionContext.class, withSettings().extraInterfaces(DBCTransactionManager.class));
        final DBCTransactionManager transaction = (DBCTransactionManager) execution;
        final DBCSession session = mock(DBCSession.class);
        final org.jkiss.dbeaver.model.DBPDataSource source = mock(org.jkiss.dbeaver.model.DBPDataSource.class, RETURNS_DEEP_STUBS);
        final DBEObjectManager manager = mock(DBEObjectManager.class);
        final DBECommand first = mock(DBECommand.class);
        final DBECommand second = mock(DBECommand.class);
        final DBEPersistAction action = mock(DBEPersistAction.class);
        final VoidProgressMonitor monitor = spy(new VoidProgressMonitor());
        final AbstractCommandContext context = new AbstractCommandContext(execution, true) {};

        AtomicFixture() throws Exception {
            when(execution.isConnected()).thenReturn(true);
            when(execution.getDataSource()).thenReturn(source);
            when(source.getInfo().supportsTransactionsForDDL()).thenReturn(true);
            when(transaction.isSupportsTransactions()).thenReturn(true);
            when(transaction.isAutoCommit()).thenReturn(true);
            when(execution.openSession(any(), any(), anyString())).thenReturn(session);
            when(action.getType()).thenReturn(DBEPersistAction.ActionType.NORMAL);
            when(first.getPersistActions(any(), any(), any())).thenReturn(new DBEPersistAction[]{action});
            when(second.getPersistActions(any(), any(), any())).thenReturn(new DBEPersistAction[]{action});
            var type = Class.forName(AbstractCommandContext.class.getName() + "$CommandQueue");
            var constructor = type.getDeclaredConstructor(DBEObjectManager.class, type, DBPObject.class);
            constructor.setAccessible(true);
            var queue = (java.util.Collection) constructor.newInstance(manager, null, mock(DBPObject.class));
            queue.add(first);
            queue.add(second);
            var field = AbstractCommandContext.class.getDeclaredField("commandQueues");
            field.setAccessible(true);
            field.set(context, List.of(queue));
        }

        void save() throws DBException {
            context.saveChanges(monitor, Map.of(DBECommandContext.OPTION_ATOMIC_TRANSACTION, true));
        }
    }

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
