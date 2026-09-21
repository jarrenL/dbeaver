/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreServerExtension;
import org.jkiss.dbeaver.ext.postgresql.tasks.*;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPNativeClientLocation;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBNativePasswordTest {
    @TempDir Path directory;

    private PostgreDatabaseRestoreSettings settings(boolean pipe, String password) {
        var settings = mock(PostgreDatabaseRestoreSettings.class);
        var container = mock(DBPDataSourceContainer.class);
        var source = mock(PostgreDataSource.class);
        var server = mock(PostgreServerExtension.class);
        when(settings.getDataSourceContainer()).thenReturn(container);
        when(container.getDataSource()).thenReturn(source);
        when(source.getServerType()).thenReturn(server);
        when(server.usesNativePasswordPipe()).thenReturn(pipe);
        when(server.getNativeToolName("pg_restore")).thenReturn(pipe ? "gs_restore" : "pg_restore");
        when(settings.getToolUserPassword()).thenReturn(password);
        when(settings.getFormat()).thenReturn(PostgreBackupRestoreSettings.ExportFormat.CUSTOM);
        when(settings.getInputFile()).thenReturn(directory.resolve("input.dump").toString());
        when(container.getActualConnectionConfiguration()).thenReturn(new DBPConnectionConfiguration());
        return settings;
    }

    @Test void gaussPasswordOnlyGoesToPipeAndNotEnvironmentOrArguments() throws Exception {
        var settings = settings(true, "Synthetic!秘密");
        var builder = new ProcessBuilder("gs_restore");
        builder.environment().put("PGPASSWORD", "inherited-secret");
        var handler = new Restore(); handler.configure(settings, builder);
        assertFalse(builder.environment().containsKey("PGPASSWORD"));
        var process = mock(Process.class);
        var input = new TrackedOutput();
        when(process.getOutputStream()).thenReturn(input);
        handler.authenticate(settings, process);
        assertEquals("Synthetic!秘密\n", input.toString(StandardCharsets.UTF_8));
        assertTrue(input.closed);
        assertEquals(java.util.List.of("gs_restore"), builder.command());
    }

    @Test void postgresqlKeepsEnvironmentAuthenticationAndNeverWritesPipe() throws Exception {
        var settings = settings(false, "Synthetic!password");
        var builder = new ProcessBuilder("pg_restore");
        var handler = new Restore(); handler.configure(settings, builder);
        assertEquals("Synthetic!password", builder.environment().get("PGPASSWORD"));
        var process = mock(Process.class); handler.authenticate(settings, process);
        verifyNoInteractions(process);
    }

    @Test void rejectsMultilinePasswordWithoutWritingPartialCredentials() throws Exception {
        var process = mock(Process.class);
        assertThrows(IOException.class, () -> new Restore().authenticate(settings(true, "a\nb"), process));
        verify(process).destroy(); verify(process, never()).getOutputStream();
    }

    @Test void pipelineOptionIsGaussOnly() throws Exception {
        for (boolean pipe : new boolean[]{true, false}) {
            var settings = settings(pipe, "Synthetic!password");
            String binary = pipe ? "gs_restore" : "pg_restore";
            Files.createFile(directory.resolve(binary));
            var home = mock(DBPNativeClientLocation.class);
            when(home.getPath()).thenReturn(directory.toFile()); when(settings.getClientHome()).thenReturn(home);
            var command = new ArrayList<String>(); new Restore().fillProcessParameters(settings, null, command);
            assertEquals(pipe, command.contains("--pipeline"));
            assertFalse(command.toString().contains("Synthetic!password"));
        }
    }

    @Test void scriptKeepsPipeOpenForSqlAfterPassword() throws Exception {
        var restoreSettings = settings(true, "Synthetic!password");
        var container = restoreSettings.getDataSourceContainer();
        var scriptSettings = mock(PostgreScriptExecuteSettings.class);
        when(scriptSettings.getToolUserPassword()).thenReturn("Synthetic!password");
        when(scriptSettings.getDataSourceContainer()).thenReturn(container);
        var process = mock(Process.class); var input = new TrackedOutput();
        when(process.getOutputStream()).thenReturn(input);
        new Script().authenticate(scriptSettings, process);
        assertFalse(input.closed);
        input.write("SELECT 1;".getBytes(StandardCharsets.UTF_8));
        assertEquals("Synthetic!password\nSELECT 1;", input.toString(StandardCharsets.UTF_8));
    }

    private static class TrackedOutput extends ByteArrayOutputStream {
        boolean closed;
        @Override public void close() { closed = true; }
    }

    private static class Script extends PostgreScriptExecuteHandler {
        void authenticate(PostgreScriptExecuteSettings settings, Process process) throws IOException {
            writeNativePassword(new VoidProgressMonitor(), settings, process);
        }
    }

    private static class Restore extends PostgreDatabaseRestoreHandler {
        void configure(PostgreDatabaseRestoreSettings settings, ProcessBuilder builder) {
            setupProcessParameters(new VoidProgressMonitor(), settings, null, builder);
        }
        void authenticate(PostgreDatabaseRestoreSettings settings, Process process) throws IOException {
            writeNativePassword(new VoidProgressMonitor(), settings, process);
        }
    }
}
