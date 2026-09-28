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
    @ValueSource(strings = {"success", "canceled", "exit-failure"})
    @SuppressWarnings("unchecked")
    void publishesOnlySuccessfulUncanceledBackupAndAlwaysCleansStaging(String outcome) throws Exception {
        String binary = outcome.equals("exit-failure") ? "/usr/bin/false" : "/usr/bin/true";
        assumeTrue(Files.isExecutable(Path.of(binary)), "Requires local true/false executables, not a Windows test");
        Path staged = Files.writeString(directory.resolve("staged.dump"), "new bytes");
        Path target = Files.writeString(directory.resolve("previous.dump"), "previous valid backup");
        var settings = mock(PostgreDatabaseBackupSettings.class);
        var info = mock(PostgreDatabaseBackupInfo.class);
        when(settings.getOutputFile(info)).thenReturn(target.toString());
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
        if (outcome.equals("exit-failure")) {
            assertThrows(IOException.class, () -> handler.executeProcess(monitor, task, settings, info, mock(Log.class)));
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
