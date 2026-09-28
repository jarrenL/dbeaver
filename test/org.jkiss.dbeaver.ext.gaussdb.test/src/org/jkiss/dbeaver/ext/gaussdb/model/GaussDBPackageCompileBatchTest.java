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
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> targetsAndCancellation() {
        return java.util.Arrays.stream(GaussDBPackageCompileTarget.values()).flatMap(target ->
            java.util.stream.Stream.of(org.junit.jupiter.params.provider.Arguments.of(target, false),
                org.junit.jupiter.params.provider.Arguments.of(target, true)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("targetsAndCancellation")
    void retainsFailingObjectsOwnDiagnosticsAndSeparateCancellationFlag(
        GaussDBPackageCompileTarget requested, boolean cancel
    ) {
        var monitor = mock(DBRProgressMonitor.class);
        var first = mock(GaussDBPackage.class);
        var second = mock(GaussDBPackage.class);
        var third = mock(GaussDBPackage.class);
        when(first.getName()).thenReturn("same_package");
        when(second.getName()).thenReturn("same_package");
        var failure = new DBException("diagnostics interrupted");
        var visits = new java.util.ArrayList<GaussDBPackage>();
        var result = GaussDBPackageCompileBatch.compile(monitor, List.of(first, second, third), requested,
            (m, log, object, target) -> {
                assertSame(requested, target);
                visits.add(object);
                log.error(new GaussDBPackageCompileError(target, object == first ? "first error" : "second error", 2));
                if (object == second) {
                    log.error(new GaussDBPackageCompileError(target, "second additional error", 3));
                    when(monitor.isCanceled()).thenReturn(cancel);
                    throw failure;
                }
            });
        assertEquals(List.of(first, second), visits);
        assertEquals(1, result.completed());
        assertEquals(3, result.total());
        assertSame(second, result.stoppedAt());
        assertSame(failure, result.failure());
        assertEquals(cancel, result.canceled());
        assertTrue(result.interrupted());
        assertEquals(3, result.diagnostics().size());
        assertSame(first, result.diagnostics().get(0).object());
        assertSame(second, result.diagnostics().get(1).object());
        assertSame(second, result.diagnostics().get(2).object());
        assertEquals(List.of("first error", "second error", "second additional error"),
            result.diagnostics().stream().map(d -> d.error().getMessage()).toList());
        verify(monitor).worked(1);
        verifyNoInteractions(third);
    }

    @Test
    void cancellationBetweenObjectsKeepsCompletedCountAndDoesNotStartNext() {
        var monitor = mock(DBRProgressMonitor.class);
        var canceled = new java.util.concurrent.atomic.AtomicBoolean();
        when(monitor.isCanceled()).thenAnswer(call -> canceled.get());
        doAnswer(call -> { canceled.set(true); return null; }).when(monitor).worked(1);
        var first = mock(GaussDBPackage.class);
        var second = mock(GaussDBPackage.class);
        var visits = new java.util.ArrayList<GaussDBPackage>();
        var result = GaussDBPackageCompileBatch.compile(monitor, List.of(first, second),
            GaussDBPackageCompileTarget.ALL, (m, log, object, target) -> visits.add(object));
        assertEquals(List.of(first), visits);
        assertEquals(1, result.completed());
        assertEquals(2, result.total());
        assertSame(second, result.stoppedAt());
        assertNull(result.failure());
        assertTrue(result.canceled());
        verifyNoInteractions(second);
    }

    @Test
    void resultDiagnosticListIsAnIndependentImmutableSnapshot() {
        var source = new java.util.ArrayList<GaussDBPackageCompileBatch.Diagnostic>();
        var diagnostic = new GaussDBPackageCompileBatch.Diagnostic(mock(GaussDBPackage.class),
            new GaussDBPackageCompileError(GaussDBPackageCompileTarget.BODY, "bad", 2));
        source.add(diagnostic);
        var result = new GaussDBPackageCompileBatch.Result(source, 1, 1, null, null, false);
        source.clear();
        assertEquals(List.of(diagnostic), result.diagnostics());
        assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().clear());
    }

    @Test
    void emptyBatchDoesNotInvokeCompiler() {
        var result = GaussDBPackageCompileBatch.compile(mock(DBRProgressMonitor.class), List.of(),
            GaussDBPackageCompileTarget.ALL, (m, log, pkg, target) -> fail("Empty batch must not execute SQL"));
        assertEquals(0, result.completed());
        assertFalse(result.interrupted());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void allTargetsPreserveObjectOrderAndRequestedTarget() {
        for (var target : GaussDBPackageCompileTarget.values()) {
            var first = mock(GaussDBPackage.class);
            var second = mock(GaussDBPackage.class);
            var visited = new java.util.ArrayList<GaussDBPackage>();
            var result = GaussDBPackageCompileBatch.compile(mock(DBRProgressMonitor.class), List.of(first, second),
                target, (m, log, pkg, requested) -> {
                    assertSame(target, requested);
                    visited.add(pkg);
                });
            assertEquals(List.of(first, second), visited);
            assertEquals(2, result.completed());
            assertFalse(result.interrupted());
        }
    }

    @Test
    void firstFailureStopsBeforeAnyLaterPackage() {
        var first = mock(GaussDBPackage.class);
        var second = mock(GaussDBPackage.class);
        var failure = new DBException("permission denied");
        var result = GaussDBPackageCompileBatch.compile(mock(DBRProgressMonitor.class), List.of(first, second),
            GaussDBPackageCompileTarget.BODY, (m, log, pkg, target) -> {
                assertSame(first, pkg);
                throw failure;
            });
        assertSame(first, result.stoppedAt());
        assertSame(failure, result.failure());
        assertEquals(0, result.completed());
        assertTrue(result.interrupted());
        verifyNoInteractions(second);
    }

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
