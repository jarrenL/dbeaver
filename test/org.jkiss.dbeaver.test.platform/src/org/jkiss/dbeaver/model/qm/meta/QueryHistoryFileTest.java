/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.model.qm.meta;

import org.jkiss.dbeaver.model.qm.QMEventAction;
import org.jkiss.dbeaver.model.qm.QMEventFilter;
import org.jkiss.dbeaver.model.qm.QMMetaEvent;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.runtime.qm.QMLogFileWriter;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.model.qm.QMConstants;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryHistoryFileTest extends DBeaverUnitTest {
    @TempDir
    Path directory;

    private void field(QMLogFileWriter target, String name, Object value) throws Exception {
        var field = QMLogFileWriter.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private QMLogFileWriter writer(Writer output) throws Exception {
        // Exercise production event formatting and IO without changing global workspace preferences.
        var writer = mock(QMLogFileWriter.class, CALLS_REAL_METHODS);
        field(writer, "enabled", true);
        field(writer, "logWriter", output);
        field(writer, "lineSeparator", "\n");
        field(writer, "eventFilter", (QMEventFilter) event -> true);
        return writer;
    }

    private QMMetaEvent event(String sql, boolean error) {
        var execution = mock(QMMStatementExecuteInfo.class);
        var statement = mock(QMMStatementInfo.class);
        when(statement.getPurpose()).thenReturn(DBCExecutionPurpose.USER);
        when(execution.getStatement()).thenReturn(statement);
        when(execution.getQueryString()).thenReturn(sql);
        when(execution.getOpenTime()).thenReturn(1000L);
        when(execution.getCloseTime()).thenReturn(2000L);
        when(execution.getUpdateRowCount()).thenReturn(3L);
        when(execution.hasError()).thenReturn(error);
        when(execution.getErrorMessage()).thenReturn("fixture error");
        return new QMMetaEvent(execution, QMEventAction.END, 2000L, "fixture");
    }

    @Test
    void changingLogDirectoryClosesOldOutputAndWritesToNewFile() throws Exception {
        var oldOutput = mock(Writer.class);
        reconfigure(oldOutput, directory, true, writer -> {
            verify(oldOutput, times(1)).close();
            writer.metaInfoChanged(new VoidProgressMonitor(), List.of(event("SELECT 'new directory 中文'", false)));
            try (var files = Files.list(directory)) {
                var log = files.filter(path -> path.getFileName().toString().startsWith("dbeaver_sql_")).findFirst().orElseThrow();
                assertTrue(Files.readString(log).contains("SELECT 'new directory 中文'"));
            }
            verify(oldOutput, never()).write(anyString());
        });
    }

    @Test
    void failedDirectoryChangeDoesNotContinueWritingToOldDestination() throws Exception {
        Path notDirectory = Files.createFile(directory.resolve("not-directory"));
        var oldOutput = mock(Writer.class);
        reconfigure(oldOutput, notDirectory, true, writer -> {
            verify(oldOutput, times(1)).close();
            writer.metaInfoChanged(new VoidProgressMonitor(), List.of(event("SELECT 1", false)));
            verify(oldOutput, never()).write(anyString());
        });
    }

    @Test
    void disablingLogPreferenceClosesOutputAndDropsLaterEvents() throws Exception {
        var oldOutput = mock(Writer.class);
        reconfigure(oldOutput, directory, false, writer -> {
            verify(oldOutput, times(1)).close();
            writer.metaInfoChanged(new VoidProgressMonitor(), List.of(event("SELECT 1", false)));
            verify(oldOutput, never()).write(anyString());
        });
    }

    @FunctionalInterface
    private interface WriterAssertion {
        void verifyWriter(QMLogFileWriter writer) throws Exception;
    }

    private void reconfigure(Writer oldOutput, Path target, boolean enabled, WriterAssertion assertion) throws Exception {
        var preferences = DBWorkbench.getPlatform().getPreferenceStore();
        var settings = java.util.Map.of(
            QMConstants.PROP_STORE_LOG_FILE, Boolean.toString(enabled),
            QMConstants.PROP_HISTORY_DAYS, "7",
            QMConstants.PROP_LOG_DIRECTORY, target.toString(),
            QMConstants.PROP_OBJECT_TYPES, "query",
            QMConstants.PROP_QUERY_TYPES, "USER");
        var previous = new java.util.HashMap<String, String>();
        var defaults = new java.util.HashSet<String>();
        for (String key : settings.keySet()) {
            previous.put(key, preferences.getString(key));
            if (preferences.isDefault(key)) {
                defaults.add(key);
            }
        }
        try {
            settings.forEach(preferences::setValue);
            assertEquals(enabled, preferences.getBoolean(QMConstants.PROP_STORE_LOG_FILE));
            assertEquals(target.toString(), preferences.getString(QMConstants.PROP_LOG_DIRECTORY));
            var writer = writer(oldOutput);
            try {
                var initialize = QMLogFileWriter.class.getDeclaredMethod("initLogFile");
                initialize.setAccessible(true);
                initialize.invoke(writer);
                assertion.verifyWriter(writer);
            } finally {
                writer.dispose();
            }
        } finally {
            previous.forEach(preferences::setValue);
            defaults.forEach(preferences::setToDefault);
        }
    }

    @Test
    void productionEventsAreFlushedToDiskAndCanBeAppendedAfterReopen() throws Exception {
        Path file = directory.resolve("history.log");
        try (var output = Files.newBufferedWriter(file)) {
            writer(output).metaInfoChanged(new VoidProgressMonitor(), List.of(event("SELECT '中文'", false)));
            String text = Files.readString(file);
            assertTrue(text.contains("SELECT '中文'"));
            assertTrue(text.contains("SUCCESS [3]"));
        }
        try (var output = Files.newBufferedWriter(file, StandardOpenOption.APPEND)) {
            writer(output).metaInfoChanged(new VoidProgressMonitor(), List.of(event("SELECT missing", true)));
        }
        String text = Files.readString(file);
        assertTrue(text.contains("SELECT '中文'"));
        assertTrue(text.contains("SELECT missing"));
        assertTrue(text.contains("fixture error"));
        assertEquals(2, text.lines().filter(line -> line.startsWith("!ENTRY ")).count());
    }

    @Test
    void ioFailureClosesWriterAndStopsSubsequentWrites() throws Exception {
        var output = mock(Writer.class);
        doThrow(new IOException("synthetic write failure")).when(output).write(anyString());
        var writer = writer(output);
        writer.metaInfoChanged(new VoidProgressMonitor(), List.of(event("SELECT 1", false)));
        writer.metaInfoChanged(new VoidProgressMonitor(), List.of(event("SELECT 2", false)));
        verify(output, times(1)).write(anyString());
        verify(output, times(1)).close();
        verify(output, never()).flush();
    }

    @Test
    void disposeClosesOutputExactlyOnceAndPreventsFurtherWrites() throws Exception {
        var output = mock(Writer.class);
        var writer = writer(output);
        writer.dispose();
        writer.dispose();
        writer.metaInfoChanged(new VoidProgressMonitor(), List.of(event("SELECT 1", false)));
        verify(output, times(1)).close();
        verify(output, never()).write(anyString());
    }

    @Test
    void retentionDeletesOnlyMatchingLogsStrictlyOlderThanBoundary() throws Exception {
        var format = DateTimeFormatter.ofPattern("'dbeaver_sql_'yyyyMMdd'.log'");
        Path old = Files.createFile(directory.resolve(format.format(LocalDate.now().minusDays(8))));
        Path boundary = Files.createFile(directory.resolve(format.format(LocalDate.now().minusDays(7))));
        Path current = Files.createFile(directory.resolve(format.format(LocalDate.now())));
        Path unrelated = Files.createFile(directory.resolve("customer.sql"));
        Path malformed = Files.createFile(directory.resolve("dbeaver_sql_invalid.log"));
        var purge = QMLogFileWriter.class.getDeclaredMethod("purgeOldLogs", Path.class, int.class);
        purge.setAccessible(true);
        purge.invoke(null, directory, 7);
        assertFalse(Files.exists(old));
        for (Path retained : List.of(boundary, current, unrelated, malformed)) {
            assertTrue(Files.exists(retained), retained.getFileName().toString());
        }
    }

    @Test
    void retentionDoesNotDeleteDirectoriesOrSymbolicLinksNamedLikeLogs() throws Exception {
        var format = DateTimeFormatter.ofPattern("'dbeaver_sql_'yyyyMMdd'.log'");
        Path folder = Files.createDirectory(directory.resolve(format.format(LocalDate.now().minusDays(9))));
        Path target = Files.writeString(directory.resolve("customer.sql"), "SELECT 'keep';");
        Path link = Files.createSymbolicLink(directory.resolve(format.format(LocalDate.now().minusDays(10))), target);
        var purge = QMLogFileWriter.class.getDeclaredMethod("purgeOldLogs", Path.class, int.class);
        purge.setAccessible(true);
        purge.invoke(null, directory, 7);
        assertTrue(Files.isDirectory(folder), "Log retention must not delete directories");
        assertTrue(Files.isSymbolicLink(link), "Log retention must not remove symbolic links");
        assertEquals("SELECT 'keep';", Files.readString(target));
    }

    @Test
    void flushFailureClosesOutputAndDoesNotRetryLaterEvents() throws Exception {
        var output = mock(Writer.class);
        doThrow(new IOException("synthetic flush failure")).when(output).flush();
        var writer = writer(output);
        writer.metaInfoChanged(new VoidProgressMonitor(), List.of(event("SELECT 1", false)));
        writer.metaInfoChanged(new VoidProgressMonitor(), List.of(event("SELECT 2", false)));
        verify(output, times(1)).write(anyString());
        verify(output, times(1)).flush();
        verify(output, times(1)).close();
        writer.dispose();
        verify(output, times(1)).close();
    }

    @Test
    void closeFailureStillDisablesWriterAndDoesNotRetryDispose() throws Exception {
        var output = mock(Writer.class);
        doThrow(new IOException("synthetic close failure")).when(output).close();
        var writer = writer(output);
        assertDoesNotThrow(writer::dispose);
        assertDoesNotThrow(writer::dispose);
        writer.metaInfoChanged(new VoidProgressMonitor(), List.of(event("SELECT 1", false)));
        verify(output, times(1)).close();
        verify(output, never()).write(anyString());
    }
}
