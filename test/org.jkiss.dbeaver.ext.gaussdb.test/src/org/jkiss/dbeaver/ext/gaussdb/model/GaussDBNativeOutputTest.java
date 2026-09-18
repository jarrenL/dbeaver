/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource;
import org.jkiss.dbeaver.ext.postgresql.tasks.PostgreDatabaseRestoreHandler;
import org.jkiss.dbeaver.ext.postgresql.tasks.PostgreDatabaseRestoreSettings;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class GaussDBNativeOutputTest {
    @Test
    void restoreDiscardsUnusedStdoutButPreservesStderr() {
        ProcessBuilder builder = configuredBuilder();
        assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectOutput());
        assertEquals(ProcessBuilder.Redirect.PIPE, builder.redirectError());
        assertFalse(builder.redirectErrorStream());
    }

    @Test
    void largeSyntheticStdoutDoesNotBlockRestoreProcess() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")) && Files.isExecutable(Path.of("/usr/bin/head")));
        ProcessBuilder builder = configuredBuilder();
        builder.command("/bin/sh", "-c", "/usr/bin/head -c 2097152 /dev/zero; printf diagnostic >&2");
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Unused stdout must not fill a pipe");
            assertEquals(0, process.exitValue());
            assertEquals("diagnostic", new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }

    private ProcessBuilder configuredBuilder() {
        var settings = mock(PostgreDatabaseRestoreSettings.class);
        var container = mock(DBPDataSourceContainer.class);
        when(settings.getToolUserPassword()).thenReturn("synthetic-test-only");
        when(settings.getDataSourceContainer()).thenReturn(container);
        when(container.getDataSource()).thenReturn(mock(PostgreDataSource.class));
        var builder = new ProcessBuilder();
        new RestoreHandler().configure(settings, builder);
        return builder;
    }

    private static class RestoreHandler extends PostgreDatabaseRestoreHandler {
        void configure(PostgreDatabaseRestoreSettings settings, ProcessBuilder builder) {
            setupProcessParameters(new VoidProgressMonitor(), settings, null, builder);
        }
    }
}
