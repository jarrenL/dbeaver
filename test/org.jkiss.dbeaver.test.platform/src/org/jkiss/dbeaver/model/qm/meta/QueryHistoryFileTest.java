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
        when(execution.getQueryString()).thenReturn(sql);
        when(execution.getOpenTime()).thenReturn(1000L);
        when(execution.getCloseTime()).thenReturn(2000L);
        when(execution.getUpdateRowCount()).thenReturn(3L);
        when(execution.hasError()).thenReturn(error);
        when(execution.getErrorMessage()).thenReturn("fixture error");
        return new QMMetaEvent(execution, QMEventAction.END, 2000L, "fixture");
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
}
