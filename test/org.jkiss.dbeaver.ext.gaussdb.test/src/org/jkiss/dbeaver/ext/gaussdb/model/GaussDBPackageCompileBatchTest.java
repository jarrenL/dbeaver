/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBPackageCompileBatchTest {
    @Test
    void retainsEarlierDiagnosticsWhenLaterPackageFails() throws Exception {
        var monitor = mock(DBRProgressMonitor.class);
        var first = mock(GaussDBPackage.class);
        var second = mock(GaussDBPackage.class);
        var third = mock(GaussDBPackage.class);
        var failure = new DBException("connection lost");
        var result = GaussDBPackageCompileBatch.compile(monitor, List.of(first, second, third),
            GaussDBPackageCompileTarget.ALL, (m, log, object, target) -> {
                if (object == second) {
                    throw failure;
                }
                assertSame(first, object);
                log.error(new GaussDBPackageCompileError(GaussDBPackageCompileTarget.BODY, "bad body", 3));
            });
        assertTrue(result.interrupted());
        assertEquals(1, result.completed());
        assertSame(second, result.stoppedAt());
        assertSame(failure, result.failure());
        assertEquals(1, result.diagnostics().size());
        assertSame(first, result.diagnostics().getFirst().object());
        assertEquals(3, result.diagnostics().getFirst().error().getLine());
        verifyNoInteractions(third);
    }

    @Test
    void cancellationRetainsPartialDiagnosticsWithoutCountingInterruptedObject() {
        var monitor = mock(DBRProgressMonitor.class);
        var object = mock(GaussDBPackage.class);
        var result = GaussDBPackageCompileBatch.compile(monitor, List.of(object), GaussDBPackageCompileTarget.ALL,
            (m, log, pkg, target) -> {
                log.error(new GaussDBPackageCompileError(GaussDBPackageCompileTarget.BODY, "bad", 2));
                when(monitor.isCanceled()).thenReturn(true);
            });
        assertTrue(result.canceled());
        assertTrue(result.interrupted());
        assertEquals(0, result.completed());
        assertEquals(1, result.diagnostics().size());
    }

    @Test
    void canceledBeforeStartDoesNotExecuteCompiler() {
        var monitor = mock(DBRProgressMonitor.class);
        when(monitor.isCanceled()).thenReturn(true);
        var result = GaussDBPackageCompileBatch.compile(monitor, List.of(mock(GaussDBPackage.class)),
            GaussDBPackageCompileTarget.ALL, (m, l, p, t) -> fail("Compiler must not run"));
        assertTrue(result.canceled());
        assertEquals(0, result.completed());
    }

    @Test
    void completedBatchCanStillContainSourceDiagnostics() {
        var result = GaussDBPackageCompileBatch.compile(mock(DBRProgressMonitor.class), List.of(mock(GaussDBPackage.class)),
            GaussDBPackageCompileTarget.ALL, (m, log, p, t) ->
                log.error(new GaussDBPackageCompileError(GaussDBPackageCompileTarget.SPECIFICATION, "bad", 1)));
        assertFalse(result.interrupted());
        assertEquals(1, result.completed());
        assertEquals(1, result.diagnostics().size());
    }
}
