/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreServerExtension;
import org.jkiss.dbeaver.ext.postgresql.tasks.PostgreBackupAllSettings;
import org.jkiss.dbeaver.ext.postgresql.tasks.PostgreDatabaseBackupAllHandler;
import org.jkiss.dbeaver.ext.postgresql.tasks.PostgreDatabaseBackupAllInfo;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.task.DBTTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBBackupPublicationTest {
    @TempDir
    Path directory;

    @Test
    void failedNativeToolPreservesExistingTargetAndDeletesPrivateDump() throws Exception {
        verifyPublication("failure", true, "CREATE ROLE test PASSWORD 'synthetic-secret';", false);
    }

    @Test
    void canceledNativeToolNeverPublishesOrLeavesPrivateDump() throws Exception {
        verifyPublication("cancel", false, "CREATE ROLE test PASSWORD 'synthetic-secret';", false);
    }

    @Test
    void falseNativeResultDoesNotPublish() throws Exception {
        verifyPublication("false", true, "CREATE ROLE test PASSWORD 'synthetic-secret';", false);
    }

    @Test
    void truncatedDumpPreservesTargetAndDeletesPrivateDump() throws Exception {
        verifyPublication("success", true, "CREATE ROLE test PASSWORD 'unterminated", false);
    }

    @Test
    void successfulDumpIsSanitizedBeforePublication() throws Exception {
        verifyPublication("success", true, "CREATE ROLE test PASSWORD 'synthetic-secret';", true);
    }

    private void verifyPublication(String outcome, boolean existing, String content, boolean publishes) throws Exception {
        Path target = directory.resolve("backup.sql");
        if (existing) {
            Files.writeString(target, "previous backup");
        }
        var settings = mock(PostgreBackupAllSettings.class);
        var info = mock(PostgreDatabaseBackupAllInfo.class);
        var dataSource = mock(PostgreDataSource.class);
        var server = mock(PostgreServerExtension.class);
        var monitor = mock(DBRProgressMonitor.class);
        when(settings.getOutputFile(info)).thenReturn(target.toString());
        when(info.getDataSource()).thenReturn(dataSource);
        when(dataSource.getServerType()).thenReturn(server);
        var handler = new SyntheticHandler(target, content, outcome);
        try {
            if (outcome.equals("failure") || content.contains("unterminated")) {
                assertThrows(IOException.class, () -> handler.executeProcess(monitor, null, settings, info,
                    Log.getLog(GaussDBBackupPublicationTest.class)));
            } else {
                assertEquals(publishes, handler.executeProcess(monitor, null, settings, info,
                    Log.getLog(GaussDBBackupPublicationTest.class)));
            }
            assertNotEquals(target, handler.privateFile);
            assertFalse(Files.exists(handler.privateFile));
            if (publishes) {
                assertEquals("CREATE ROLE test PASSWORD DISABLE;", Files.readString(target));
            } else if (existing) {
                assertEquals("previous backup", Files.readString(target));
            } else {
                assertFalse(Files.exists(target));
            }
        } finally {
            if (handler.privateFile != null) {
                Files.deleteIfExists(handler.privateFile);
            }
        }
    }

    private static class SyntheticHandler extends PostgreDatabaseBackupAllHandler {
        private final Path target;
        private final String content;
        private final String outcome;
        private Path privateFile;

        SyntheticHandler(Path target, String content, String outcome) {
            this.target = target;
            this.content = content;
            this.outcome = outcome;
        }

        @Override
        protected boolean runNativeProcess(DBRProgressMonitor monitor, DBTTask task, PostgreBackupAllSettings settings,
                                           PostgreDatabaseBackupAllInfo arg, Log log) throws IOException {
            privateFile = Path.of(prepareOutputFile(settings, arg));
            Files.writeString(privateFile, content);
            if (outcome.equals("failure")) {
                throw new IOException("synthetic native failure");
            }
            if (outcome.equals("cancel")) {
                when(monitor.isCanceled()).thenReturn(true);
            }
            return !outcome.equals("false");
        }

        @Override
        protected Path resolveOutputPath(DBRProgressMonitor monitor, DBTTask task, PostgreBackupAllSettings settings,
                                         PostgreDatabaseBackupAllInfo arg) {
            return target;
        }
    }
}
