/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.model.exec;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPErrorAssistant;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class DBExecRecoveryBoundaryTest {
    public interface Source extends DBPDataSource, DBPErrorAssistant {
    }

    private Source source() {
        var source = mock(Source.class);
        var container = mock(DBPDataSourceContainer.class);
        var preferences = mock(DBPPreferenceStore.class);
        when(source.getContainer()).thenReturn(container);
        when(container.getPreferenceStore()).thenReturn(preferences);
        when(preferences.getBoolean(ModelPreferences.EXECUTE_RECOVER_ENABLED)).thenReturn(true);
        when(preferences.getInt(ModelPreferences.EXECUTE_RECOVER_RETRY_COUNT)).thenReturn(3);
        when(source.discoverErrorType(any())).thenReturn(DBPErrorAssistant.ErrorType.NORMAL);
        return source;
    }

    @Test
    void unsafeInnerOperationStopsOuterRecoveryBeforeInvalidation() throws Exception {
        var source = source();
        var calls = new AtomicInteger();
        var failure = new DBException("Synthetic uncertain write result");
        assertSame(failure, assertThrows(DBException.class, () -> DBExecUtils.tryExecuteRecover(
            new VoidProgressMonitor(), source, monitor -> {
                calls.incrementAndGet();
                DBExecUtils.preventAutomaticRecovery();
                throw new InvocationTargetException(failure);
            })));
        assertEquals(1, calls.get());
        verify(source, never()).discoverErrorType(any());
    }

    @Test
    void nestedSuppressionSurvivesUntilOutermostOperationEnds() throws Exception {
        var source = source();
        var failure = new DBException("Failure fetching a write result");
        assertSame(failure, assertThrows(DBException.class, () -> DBExecUtils.tryExecuteRecover(
            new VoidProgressMonitor(), source, monitor -> {
                try {
                    DBExecUtils.tryExecuteRecover(monitor, source, inner -> DBExecUtils.preventAutomaticRecovery());
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
                throw new InvocationTargetException(failure);
            })));
        verify(source, never()).discoverErrorType(any());
        // A different root operation must not inherit the suppression flag.
        assertThrows(DBException.class, () -> DBExecUtils.tryExecuteRecover(
            new VoidProgressMonitor(), source, monitor -> { throw new InvocationTargetException(failure); }));
        verify(source).discoverErrorType(failure);
    }

    @Test
    void suppressionOutsideRecoveryDoesNotPoisonLaterOperations() throws Exception {
        var source = source();
        DBExecUtils.preventAutomaticRecovery();
        var failure = new DBException("New independent request");
        assertThrows(DBException.class, () -> DBExecUtils.tryExecuteRecover(
            new VoidProgressMonitor(), source, monitor -> { throw new InvocationTargetException(failure); }));
        verify(source).discoverErrorType(failure);
    }
}
