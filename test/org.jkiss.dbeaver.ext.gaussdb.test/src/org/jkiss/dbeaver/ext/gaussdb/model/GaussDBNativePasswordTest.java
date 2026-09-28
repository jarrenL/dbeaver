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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(strings = {"a\nb", "a\rb", "a\0b"})
    void rejectsMultilinePasswordWithoutWritingPartialCredentials(String password) throws Exception {
        var process = mock(Process.class);
        assertThrows(IOException.class, () -> new Restore().authenticate(settings(true, password), process));
        verify(process).destroy(); verify(process, never()).getOutputStream();
    }

    @ParameterizedTest
    @ValueSource(strings = {"write", "flush", "close"})
    void passwordPipeFailureDestroysProcessAndPreservesCause(String stage) throws Exception {
        var process = mock(Process.class);
        var pipe = mock(java.io.OutputStream.class);
        when(process.getOutputStream()).thenReturn(pipe);
        IOException failure = new IOException("synthetic pipe " + stage + " failure");
        switch (stage) {
            case "write" -> doThrow(failure).when(pipe).write(any(byte[].class));
            case "flush" -> doThrow(failure).when(pipe).flush();
            case "close" -> doThrow(failure).when(pipe).close();
            default -> throw new AssertionError(stage);
        }
        var thrown = assertThrows(IOException.class,
            () -> new Restore().authenticate(settings(true, "Synthetic!秘密"), process));
        assertSame(failure, thrown.getCause());
        assertFalse(String.valueOf(thrown.getMessage()).contains("Synthetic!秘密"));
        verify(process).destroy();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void specialPathsAndDatabaseNamesRemainSingleArguments(boolean plain) throws Exception {
        var settings = settings(true, "Synthetic!secret");
        var format = plain ? PostgreBackupRestoreSettings.ExportFormat.PLAIN : PostgreBackupRestoreSettings.ExportFormat.CUSTOM;
        when(settings.getFormat()).thenReturn(format);
        var server = ((PostgreDataSource) settings.getDataSourceContainer().getDataSource()).getServerType();
        when(server.getNativeToolName("psql")).thenReturn("gsql");
        Path homePath = Files.createDirectory(directory.resolve("客户端 space"));
        Path binary = Files.createFile(homePath.resolve(plain ? "gsql" : "gs_restore"));
        var home = mock(DBPNativeClientLocation.class);
        when(home.getPath()).thenReturn(homePath.toFile());
        when(settings.getClientHome()).thenReturn(home);
        String input = directory.resolve("备份 space;literal$(text).dump").toString();
        when(settings.getInputFile()).thenReturn(input);
        when(settings.getToolUserName()).thenReturn("user space");
        var database = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreDatabase.class);
        when(database.toString()).thenReturn("数据库 space;literal");
        var info = new PostgreDatabaseRestoreInfo(database);
        when(settings.getRestoreInfo()).thenReturn(info);
        var command = new Restore().command(settings, info);
        assertEquals(binary.toAbsolutePath().toString(), command.getFirst());
        assertEquals(1, command.stream().filter(s -> s.equals("--username=user space")).count());
        assertEquals(1, command.stream().filter(s -> s.equals("--dbname=数据库 space;literal")).count());
        assertEquals(plain ? "--file=" + input : input, command.getLast());
        assertFalse(command.toString().contains("Synthetic!secret"));
        assertTrue(command.contains("--pipeline"));
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
        java.util.List<String> command(PostgreDatabaseRestoreSettings settings, PostgreDatabaseRestoreInfo info) throws IOException {
            return getCommandLine(settings, info);
        }
        void configure(PostgreDatabaseRestoreSettings settings, ProcessBuilder builder) {
            setupProcessParameters(new VoidProgressMonitor(), settings, null, builder);
        }
        void authenticate(PostgreDatabaseRestoreSettings settings, Process process) throws IOException {
            writeNativePassword(new VoidProgressMonitor(), settings, process);
        }
    }
}
