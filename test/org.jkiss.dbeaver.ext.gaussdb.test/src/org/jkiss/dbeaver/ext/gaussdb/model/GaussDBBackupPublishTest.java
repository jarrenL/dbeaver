/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.postgresql.tasks.*;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.task.DBTTask;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class GaussDBBackupPublishTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"stdout", "stderr", "cancel-wait", "cancel-retry"})
    void processCompletionWaitsForActualAsyncLogReader(String outcome) throws Exception {
        boolean diagnostic = !outcome.equals("stdout");
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var reader = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var output = new java.io.ByteArrayOutputStream();
        var writer = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8) {
            @Override public void print(String value) {
                if (reader.compareAndSet(null, Thread.currentThread())) {
                    entered.countDown();
                    try {
                        if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Log release timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
                super.print(value);
            }
        };
        var settings = mock(PostgreDatabaseBackupSettings.class, RETURNS_DEEP_STUBS);
        when(settings.getLogWriter()).thenReturn(writer);
        var info = mock(PostgreDatabaseBackupInfo.class);
        when(settings.getOutputFile(info)).thenReturn(directory.resolve("unused.dump").toString());
        var child = new java.util.concurrent.atomic.AtomicReference<Process>();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var handler = new PostgreDatabaseBackupHandler() {
            @Override protected boolean isLogInputStream() { return true; }
            @Override protected List<String> getCommandLine(PostgreDatabaseBackupSettings s, PostgreDatabaseBackupInfo a) throws IOException {
                if (attempts.getAndIncrement() > 0) {
                    // Finish the previous reader only after the new executeProcess has reset state.
                    release.countDown();
                    try {
                        reader.get().join(2000);
                        if (reader.get().isAlive()) throw new IOException("Previous reader did not finish");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                    return List.of("/bin/sh", "-c", "exit 0");
                }
                return List.of("/bin/sh", "-c", diagnostic ? "printf 'delayed diagnostic' >&2" : "printf 'normal output'");
            }
            @Override protected void setupProcessParameters(DBRProgressMonitor m, PostgreDatabaseBackupSettings s,
                PostgreDatabaseBackupInfo a, ProcessBuilder b) {
            }
            @Override protected void startProcessHandler(DBRProgressMonitor m, DBTTask t,
                PostgreDatabaseBackupSettings s, PostgreDatabaseBackupInfo a, ProcessBuilder b, Process p, Log log)
                throws IOException, org.jkiss.dbeaver.DBException {
                child.set(p);
                super.startProcessHandler(m, t, s, a, b, p, log);
            }
        };
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var monitor = mock(DBRProgressMonitor.class);
        var canceled = new java.util.concurrent.atomic.AtomicBoolean();
        when(monitor.isCanceled()).thenAnswer(i -> canceled.get());
        try {
            var result = executor.submit(() -> handler.executeProcess(monitor,
                mock(DBTTask.class, RETURNS_DEEP_STUBS), settings, info, mock(Log.class)));
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(child.get().waitFor(2, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> result.get(500, java.util.concurrent.TimeUnit.MILLISECONDS),
                "Process exit alone must not finish the task while logs are still unread");
            if (outcome.startsWith("cancel-")) {
                canceled.set(true);
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(2, java.util.concurrent.TimeUnit.SECONDS));
                assertInstanceOf(InterruptedException.class, failure.getCause());
                if (outcome.equals("cancel-retry")) {
                    canceled.set(false);
                    var retry = executor.submit(() -> handler.executeProcess(monitor,
                        mock(DBTTask.class, RETURNS_DEEP_STUBS), settings, info, mock(Log.class)));
                    assertTrue(retry.get(3, java.util.concurrent.TimeUnit.SECONDS), "Late old diagnostic must not poison retry");
                    assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("delayed diagnostic"));
                }
            } else {
                release.countDown();
                assertEquals(!diagnostic, result.get(2, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8)
                    .contains(diagnostic ? "delayed diagnostic" : "normal output"));
            }
            verify(monitor, times(outcome.equals("cancel-retry") ? 2 : 1)).done();
        } finally {
            release.countDown();
            executor.shutdownNow();
            if (child.get() != null) {
                child.get().destroyForcibly();
                assertTrue(child.get().waitFor(2, java.util.concurrent.TimeUnit.SECONDS));
            }
            if (reader.get() != null) {
                reader.get().join(2000);
                assertFalse(reader.get().isAlive());
            }
            assertTrue(executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));
            writer.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedErrorLogDoesNotPoisonRetryAndKeepsFinalUnterminatedLine(boolean newline) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
        var output = new java.io.ByteArrayOutputStream();
        var settings = mock(PostgreDatabaseBackupSettings.class, RETURNS_DEEP_STUBS);
        when(settings.getLogWriter()).thenReturn(new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8));
        var task = mock(DBTTask.class, RETURNS_DEEP_STUBS);
        var monitor = mock(DBRProgressMonitor.class);
        var attempt = new java.util.concurrent.atomic.AtomicInteger();
        var handler = new PostgreDatabaseBackupHandler() {
            @Override protected boolean isLogInputStream() { return true; }
            @Override protected List<String> getCommandLine(PostgreDatabaseBackupSettings s, PostgreDatabaseBackupInfo a) {
                return List.of("/bin/sh", "-c", attempt.getAndIncrement() == 0
                    ? "printf 'synthetic failure" + (newline ? "\\n" : "") + "' >&2" : "exit 0");
            }
            @Override protected void setupProcessParameters(DBRProgressMonitor m, PostgreDatabaseBackupSettings s,
                PostgreDatabaseBackupInfo a, ProcessBuilder b) {
            }
        };
        var info = mock(PostgreDatabaseBackupInfo.class);
        when(settings.getOutputFile(info)).thenReturn(directory.resolve("unused.dump").toString());
        assertFalse(handler.executeProcess(monitor, task, settings, info, mock(Log.class)), "stderr diagnostic must be retained");
        assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("synthetic failure"));
        assertTrue(handler.executeProcess(monitor, task, settings, info, mock(Log.class)), "Successful retry must not inherit old failure");
        verify(monitor, times(2)).done();
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-command", "during-command", "during-setup"})
    void canceledPreparationMustNotStartNativeProcess(String stage) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/true")));
        var canceled = new java.util.concurrent.atomic.AtomicBoolean(stage.equals("before-command"));
        var monitor = mock(DBRProgressMonitor.class);
        when(monitor.isCanceled()).thenAnswer(i -> canceled.get());
        var commands = new java.util.concurrent.atomic.AtomicInteger();
        var setups = new java.util.concurrent.atomic.AtomicInteger();
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        var task = mock(DBTTask.class, RETURNS_DEEP_STUBS);
        var handler = new PostgreDatabaseBackupHandler() {
            @Override protected List<String> getCommandLine(PostgreDatabaseBackupSettings s, PostgreDatabaseBackupInfo a) {
                commands.incrementAndGet();
                if (stage.equals("during-command")) canceled.set(true);
                return List.of("/usr/bin/true");
            }
            @Override protected void setupProcessParameters(DBRProgressMonitor m, PostgreDatabaseBackupSettings s,
                PostgreDatabaseBackupInfo a, ProcessBuilder b) {
                setups.incrementAndGet();
                if (stage.equals("during-setup")) canceled.set(true);
            }
            @Override protected void startProcessHandler(DBRProgressMonitor m, DBTTask t,
                PostgreDatabaseBackupSettings s, PostgreDatabaseBackupInfo a, ProcessBuilder b, Process p, Log log) {
                started.set(true);
            }
        };
        assertThrows(InterruptedException.class, () -> handler.executeProcess(monitor, task,
            mock(PostgreDatabaseBackupSettings.class), mock(PostgreDatabaseBackupInfo.class), mock(Log.class)));
        assertAll(
            () -> assertFalse(started.get(), "Canceled preparation must not launch a native command"),
            () -> assertEquals(stage.equals("before-command") ? 0 : 1, commands.get()),
            () -> assertEquals(stage.equals("during-setup") ? 1 : 0, setups.get()));
        verify(monitor).done();
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "empty", "nonempty"})
    @SuppressWarnings("unchecked")
    void directoryPublicationDoesNotMergeWithExistingBackup(String state) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/true")));
        Path staged = Files.createDirectory(directory.resolve("staged"));
        Files.writeString(staged.resolve("toc.dat"), "new table of contents");
        Files.writeString(staged.resolve("new.dat"), "new payload");
        Path target = directory.resolve("backup");
        if (!state.equals("absent")) Files.createDirectory(target);
        if (state.equals("nonempty")) {
            Files.writeString(target.resolve("toc.dat"), "old table of contents");
            Files.writeString(target.resolve("old.dat"), "old payload");
        }
        var settings = mock(PostgreDatabaseBackupSettings.class);
        var info = mock(PostgreDatabaseBackupInfo.class);
        when(settings.getOutputFile(info)).thenReturn(target.toString());
        var task = mock(DBTTask.class, RETURNS_DEEP_STUBS);
        when(task.getProject()).thenReturn(null);
        var monitor = mock(DBRProgressMonitor.class);
        var handler = new PostgreDatabaseBackupHandler() {
            @Override protected List<String> getCommandLine(PostgreDatabaseBackupSettings s, PostgreDatabaseBackupInfo a) {
                return List.of("/usr/bin/true");
            }
            @Override protected void setupProcessParameters(DBRProgressMonitor m, PostgreDatabaseBackupSettings s,
                PostgreDatabaseBackupInfo a, ProcessBuilder b) {
            }
            @Override protected void startProcessHandler(DBRProgressMonitor m, DBTTask t,
                PostgreDatabaseBackupSettings s, PostgreDatabaseBackupInfo a, ProcessBuilder b, Process p, Log log) {
            }
        };
        var field = PostgreDatabaseBackupHandler.class.getDeclaredField("localTransferFiles");
        field.setAccessible(true);
        var paths = (Map<PostgreDatabaseBackupInfo, Path>) field.get(handler);
        paths.put(info, staged);
        if (state.equals("nonempty")) {
            assertThrows(IOException.class, () -> handler.executeProcess(monitor, task, settings, info, mock(Log.class)));
            assertEquals("old table of contents", Files.readString(target.resolve("toc.dat")));
            assertEquals("old payload", Files.readString(target.resolve("old.dat")));
            assertFalse(Files.exists(target.resolve("new.dat")));
        } else {
            assertTrue(handler.executeProcess(monitor, task, settings, info, mock(Log.class)));
            assertEquals("new table of contents", Files.readString(target.resolve("toc.dat")));
            assertEquals("new payload", Files.readString(target.resolve("new.dat")));
        }
        assertFalse(Files.exists(staged));
        assertTrue(paths.isEmpty());
        verify(monitor).done();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelStopsEvenTerminationResistantChildAndReportsInterruption(boolean ignoreTerm) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")) && Files.isExecutable(Path.of("/bin/sleep")));
        var child = new java.util.concurrent.atomic.AtomicReference<Process>();
        var canceled = new java.util.concurrent.atomic.AtomicBoolean();
        var monitor = mock(DBRProgressMonitor.class);
        when(monitor.isCanceled()).thenAnswer(i -> canceled.get());
        var task = mock(DBTTask.class, RETURNS_DEEP_STUBS);
        var handler = new PostgreDatabaseBackupHandler() {
            @Override protected List<String> getCommandLine(PostgreDatabaseBackupSettings s, PostgreDatabaseBackupInfo a) {
                return List.of("/bin/sh", "-c", (ignoreTerm ? "trap '' TERM; " : "") + "printf 'ready\\n'; exec /bin/sleep 30");
            }
            @Override protected void setupProcessParameters(DBRProgressMonitor m, PostgreDatabaseBackupSettings s,
                PostgreDatabaseBackupInfo a, ProcessBuilder b) {
            }
            @Override protected void startProcessHandler(DBRProgressMonitor m, DBTTask t,
                PostgreDatabaseBackupSettings s, PostgreDatabaseBackupInfo a, ProcessBuilder b, Process p, Log log) throws IOException {
                child.set(p);
                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                if (!"ready".equals(reader.readLine())) throw new IOException("Synthetic child did not become ready");
                canceled.set(true);
            }
        };
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> {
                try {
                    handler.executeProcess(monitor, task, mock(PostgreDatabaseBackupSettings.class),
                        mock(PostgreDatabaseBackupInfo.class), mock(Log.class));
                    return (Throwable) null;
                } catch (Exception error) {
                    return error;
                }
            });
            Throwable result = future.get(4, java.util.concurrent.TimeUnit.SECONDS);
            assertInstanceOf(InterruptedException.class, result);
            assertNotNull(child.get());
            assertTrue(child.get().waitFor(2, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(child.get().isAlive());
            verify(monitor).done();
        } finally {
            executor.shutdownNow();
            if (child.get() != null) {
                child.get().destroyForcibly();
                assertTrue(child.get().waitFor(2, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertTrue(executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"startup-failure", "interrupted"})
    void failureAfterProcessStartDoesNotLeaveNativeChildRunning(String stage) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sleep")), "Requires local sleep executable");
        var child = new java.util.concurrent.atomic.AtomicReference<Process>();
        var monitor = mock(DBRProgressMonitor.class);
        var task = mock(DBTTask.class, RETURNS_DEEP_STUBS);
        var settings = mock(PostgreDatabaseBackupSettings.class);
        var info = mock(PostgreDatabaseBackupInfo.class);
        IOException original = new IOException("synthetic startup failure");
        var handler = new PostgreDatabaseBackupHandler() {
            @Override protected List<String> getCommandLine(PostgreDatabaseBackupSettings s, PostgreDatabaseBackupInfo a) {
                return List.of("/bin/sleep", "30");
            }
            @Override protected void setupProcessParameters(DBRProgressMonitor m, PostgreDatabaseBackupSettings s,
                PostgreDatabaseBackupInfo a, ProcessBuilder builder) {
            }
            @Override protected void startProcessHandler(DBRProgressMonitor m, DBTTask t,
                PostgreDatabaseBackupSettings s, PostgreDatabaseBackupInfo a, ProcessBuilder b, Process process, Log log) throws IOException {
                child.set(process);
                if (stage.equals("startup-failure")) throw original;
                Thread.currentThread().interrupt();
            }
        };
        try {
            if (stage.equals("startup-failure")) {
                assertSame(original, assertThrows(IOException.class,
                    () -> handler.executeProcess(monitor, task, settings, info, mock(Log.class))));
            } else {
                assertThrows(InterruptedException.class,
                    () -> handler.executeProcess(monitor, task, settings, info, mock(Log.class)));
            }
            assertNotNull(child.get());
            assertTrue(child.get().waitFor(2, java.util.concurrent.TimeUnit.SECONDS), "Failed task must stop its owned native process");
            assertFalse(child.get().isAlive());
            verify(monitor).done();
        } finally {
            Thread.interrupted();
            if (child.get() != null) {
                child.get().destroyForcibly();
                assertTrue(child.get().waitFor(2, java.util.concurrent.TimeUnit.SECONDS), "Test process cleanup failed");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "canceled", "io-failure", "interrupted"})
    void taskLoopStopsOnCancellationOrFailureAndDoesNotNotifySuccess(String outcome) throws Exception {
        var first = mock(PostgreDatabaseBackupInfo.class);
        var second = mock(PostgreDatabaseBackupInfo.class);
        var settings = mock(PostgreDatabaseBackupSettings.class);
        var task = mock(DBTTask.class, RETURNS_DEEP_STUBS);
        var navigation = task.getProject().getNavigatorModel();
        var canceled = new java.util.concurrent.atomic.AtomicBoolean();
        var monitor = mock(DBRProgressMonitor.class);
        when(monitor.isCanceled()).thenAnswer(i -> canceled.get());
        var executed = new java.util.ArrayList<PostgreDatabaseBackupInfo>();
        var notifications = new java.util.ArrayList<String>();
        IOException ioFailure = new IOException("synthetic native failure");
        InterruptedException interruption = new InterruptedException("synthetic interrupt");
        class TaskHandler extends PostgreDatabaseBackupHandler {
            @Override protected boolean isNativeClientHomeRequired() { return false; }
            @Override public java.util.Collection<PostgreDatabaseBackupInfo> getRunInfo(PostgreDatabaseBackupSettings s) {
                return List.of(first, second);
            }
            @Override public boolean executeProcess(DBRProgressMonitor m, DBTTask t,
                PostgreDatabaseBackupSettings s, PostgreDatabaseBackupInfo a, Log log) throws IOException, InterruptedException {
                executed.add(a);
                if (outcome.equals("io-failure")) throw ioFailure;
                if (outcome.equals("interrupted")) throw interruption;
                if (outcome.equals("canceled")) canceled.set(true);
                return !canceled.get();
            }
            @Override protected void notifyToolFinish(String name, long elapsed) { notifications.add(name); }
            boolean runTask() throws org.jkiss.dbeaver.DBException, InterruptedException {
                return doExecute(monitor, task, settings, mock(Log.class));
            }
        }
        var handler = new TaskHandler();
        switch (outcome) {
            case "success" -> assertTrue(handler.runTask());
            case "canceled" -> assertThrows(InterruptedException.class, handler::runTask);
            case "interrupted" -> assertSame(interruption, assertThrows(InterruptedException.class, handler::runTask));
            case "io-failure" -> assertSame(ioFailure, assertThrows(org.jkiss.dbeaver.DBException.class, handler::runTask).getCause());
            default -> throw new AssertionError(outcome);
        }
        assertEquals(outcome.equals("success") ? List.of(first, second) : List.of(first), executed);
        assertEquals(outcome.equals("success") ? 1 : 0, notifications.size());
        verifyNoInteractions(navigation);
        verify(settings, never()).getDatabaseObjects();
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "canceled", "exit-failure", "missing-staging", "target-parent-is-file"})
    @SuppressWarnings("unchecked")
    void publishesOnlySuccessfulUncanceledBackupAndAlwaysCleansStaging(String outcome) throws Exception {
        String binary = outcome.equals("exit-failure") ? "/usr/bin/false" : "/usr/bin/true";
        assumeTrue(Files.isExecutable(Path.of(binary)), "Requires local true/false executables, not a Windows test");
        Path staged = Files.writeString(directory.resolve("staged.dump"), "new bytes");
        Path target = Files.writeString(directory.resolve("previous.dump"), "previous valid backup");
        var settings = mock(PostgreDatabaseBackupSettings.class);
        var info = mock(PostgreDatabaseBackupInfo.class);
        when(settings.getOutputFile(info)).thenReturn(
            outcome.equals("target-parent-is-file") ? target.resolve("child.dump").toString() : target.toString());
        if (outcome.equals("missing-staging")) {
            Files.delete(staged);
        }
        var monitor = mock(DBRProgressMonitor.class);
        when(monitor.isCanceled()).thenReturn(outcome.equals("canceled"));
        var task = mock(DBTTask.class, RETURNS_DEEP_STUBS);
        when(task.getProject()).thenReturn(null);
        when(task.getType().getName()).thenReturn("synthetic backup");
        var handler = new PostgreDatabaseBackupHandler() {
            @Override protected List<String> getCommandLine(PostgreDatabaseBackupSettings s, PostgreDatabaseBackupInfo a) {
                return List.of(binary);
            }
            @Override protected void setupProcessParameters(DBRProgressMonitor m, PostgreDatabaseBackupSettings s,
                PostgreDatabaseBackupInfo a, ProcessBuilder b) {
                // No database credentials or server process in this lifecycle test.
            }
            @Override protected void startProcessHandler(DBRProgressMonitor m, DBTTask t,
                PostgreDatabaseBackupSettings s, PostgreDatabaseBackupInfo a, ProcessBuilder b, Process p, Log log) {
                // The controlled process has no payload or asynchronous log reader.
            }
        };
        var field = PostgreDatabaseBackupHandler.class.getDeclaredField("localTransferFiles");
        field.setAccessible(true);
        var stagedPaths = (Map<PostgreDatabaseBackupInfo, Path>) field.get(handler);
        stagedPaths.put(info, staged);
        if (outcome.equals("exit-failure") || outcome.equals("missing-staging") || outcome.equals("target-parent-is-file")) {
            assertThrows(IOException.class, () -> handler.executeProcess(monitor, task, settings, info, mock(Log.class)));
        } else if (outcome.equals("canceled")) {
            assertThrows(InterruptedException.class, () -> handler.executeProcess(monitor, task, settings, info, mock(Log.class)));
        } else {
            boolean success = handler.executeProcess(monitor, task, settings, info, mock(Log.class));
            assertAll(
                () -> assertEquals(outcome.equals("success"), success),
                () -> assertEquals(outcome.equals("success") ? "new bytes" : "previous valid backup", Files.readString(target)));
        }
        assertEquals(outcome.equals("success") ? "new bytes" : "previous valid backup", Files.readString(target));
        assertFalse(Files.exists(staged));
        assertTrue(stagedPaths.isEmpty());
        verify(monitor).done();
    }
}
