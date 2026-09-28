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

package org.jkiss.dbeaver.model.impl.jdbc.data;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.app.DBPApplicationWorkbench;
import org.jkiss.dbeaver.model.app.DBPPlatform;
import org.jkiss.dbeaver.model.data.DBDContentCached;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JDBCContentCLOBReadTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private DBPPlatform platform;
    private java.lang.reflect.Field workbenchField;
    private Object originalWorkbench;
    @BeforeEach
    void prepareWorkbench() throws Exception {
        workbenchField = DBWorkbench.class.getDeclaredField("applicationWorkbench");
        workbenchField.setAccessible(true);
        originalWorkbench = workbenchField.get(null);
        var workbench = mock(DBPApplicationWorkbench.class);
        platform = mock(DBPPlatform.class, RETURNS_DEEP_STUBS);
        when(workbench.getPlatform()).thenReturn(platform);
        when(platform.getPreferenceStore().getInt(ModelPreferences.MEMORY_CONTENT_MAX_SIZE)).thenReturn(1024);
        workbenchField.set(null, workbench);
    }
    @AfterEach
    void restoreWorkbench() throws Exception {
        workbenchField.set(null, originalWorkbench);
    }
    private static class TrackedReader extends StringReader {
        boolean closed;
        TrackedReader(String value) { super(value); }
        @Override public void close() { closed = true; super.close(); }
    }
    @ParameterizedTest
    @ValueSource(strings = {"success", "read", "close", "both", "cancel"})
    void diskStorageClosesReaderCleansFailedAttemptAndRetries(String stage) throws Exception {
        when(platform.getPreferenceStore().getInt(ModelPreferences.MEMORY_CONTENT_MAX_SIZE)).thenReturn(0);
        when(platform.getTempFolder(any(), anyString())).thenReturn(directory);
        var clob = mock(Clob.class);
        var text = "中文𠀀 large content\n".repeat(2000);
        when(clob.length()).thenReturn((long) text.length());
        var monitor = mock(DBRProgressMonitor.class);
        var reader = mock(Reader.class);
        var backing = new StringReader(text);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var readFailure = new IOException("disk reader failed");
        var closeFailure = new IOException("disk reader close failed");
        when(reader.read(any(char[].class))).thenAnswer(invocation -> {
            if (calls.getAndIncrement() > 0 && (stage.equals("read") || stage.equals("both"))) throw readFailure;
            var buffer = (char[]) invocation.getArgument(0);
            int count = backing.read(buffer, 0, Math.min(buffer.length, 7));
            if (stage.equals("cancel")) when(monitor.isCanceled()).thenReturn(true);
            return count;
        });
        if (stage.equals("close") || stage.equals("both")) doThrow(closeFailure).when(reader).close();
        when(clob.getCharacterStream()).thenReturn(reader);
        var content = new JDBCContentCLOB(mock(DBCExecutionContext.class), clob) {
            @Override protected String getDefaultEncoding() { return "UTF-8"; }
        };
        if (!stage.equals("success")) {
            var failure = assertThrows(DBException.class, () -> content.getContents(monitor));
            if (!stage.equals("cancel")) {
                assertSame(stage.equals("close") ? closeFailure : readFailure, failure.getCause().getCause());
            } else {
                assertInstanceOf(InterruptedIOException.class, failure.getCause().getCause());
            }
            if (stage.equals("both")) assertArrayEquals(new Throwable[] {closeFailure}, readFailure.getSuppressed());
            verify(reader).close();
            verify(clob, never()).free();
            try (var files = java.nio.file.Files.list(directory)) { assertEquals(0, files.count()); }
            when(monitor.isCanceled()).thenReturn(false);
            doReturn(new TrackedReader(text)).when(clob).getCharacterStream();
        }
        var storage = content.getContents(monitor);
        var file = ((org.jkiss.dbeaver.model.data.DBDContentStorageLocal) storage).getDataFile();
        assertEquals(text, java.nio.file.Files.readString(file));
        assertEquals(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, storage.getContentLength());
        if (stage.equals("success")) verify(reader).close();
        verify(clob).free();
        content.release();
        assertFalse(java.nio.file.Files.exists(file));
        try (var files = java.nio.file.Files.list(directory)) { assertEquals(0, files.count()); }
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void substringAndFallbackBothCacheCompleteText(boolean fallback) throws Exception {
        var clob = mock(Clob.class);
        var expected = "中文𠀀";
        when(clob.length()).thenReturn((long) expected.length());
        var reader = new TrackedReader(expected);
        if (fallback) {
            when(clob.getSubString(1, expected.length())).thenThrow(new SQLException("substring unsupported"));
            when(clob.getCharacterStream()).thenReturn(reader);
            doAnswer(call -> { assertTrue(reader.closed); return null; }).when(clob).free();
        } else {
            when(clob.getSubString(1, expected.length())).thenReturn(expected);
        }
        var content = new JDBCContentCLOB(mock(DBCExecutionContext.class), clob);
        var monitor = mock(DBRProgressMonitor.class);
        var storage = content.getContents(monitor);
        if (fallback) assertTrue(reader.closed);
        assertEquals(expected, ((DBDContentCached) storage).getCachedValue());
        assertSame(storage, content.getContents(monitor));
        content.release();
        verify(clob).free();
        if (!fallback) verify(clob, never()).getCharacterStream();
    }
    @ParameterizedTest
    @ValueSource(strings = {"read", "close", "both", "open"})
    void fallbackFailurePreservesActualCauseAndAllowsRetry(String stage) throws Exception {
        var clob = mock(Clob.class);
        when(clob.length()).thenReturn(4L);
        var substringFailure = new SQLException("substring unsupported");
        when(clob.getSubString(1, 4)).thenThrow(substringFailure);
        var reader = mock(Reader.class);
        var readFailure = new IOException("read failed");
        var closeFailure = new IOException("close failed");
        var openFailure = new SQLException("stream unavailable");
        if (stage.equals("open")) {
            when(clob.getCharacterStream()).thenThrow(openFailure);
        } else {
            when(clob.getCharacterStream()).thenReturn(reader);
            if (stage.equals("close")) when(reader.read(any(char[].class))).thenReturn(-1);
            else when(reader.read(any(char[].class))).thenThrow(readFailure);
            if (!stage.equals("read")) doThrow(closeFailure).when(reader).close();
        }
        var content = new JDBCContentCLOB(mock(DBCExecutionContext.class), clob);
        var monitor = mock(DBRProgressMonitor.class);
        var thrown = assertThrows(DBException.class, () -> content.getContents(monitor));
        Throwable expected = stage.equals("open") ? openFailure : stage.equals("close") ? closeFailure : readFailure;
        assertSame(expected, thrown.getCause().getCause());
        if (stage.equals("both")) assertArrayEquals(new Throwable[] {closeFailure}, readFailure.getSuppressed());
        if (!stage.equals("open")) verify(reader).close();
        verify(clob, never()).free();
        var retry = new TrackedReader("重试成功");
        doReturn(retry).when(clob).getCharacterStream();
        var storage = content.getContents(monitor);
        assertEquals("重试成功", ((DBDContentCached) storage).getCachedValue());
        assertTrue(retry.closed);
        verify(clob).free();
    }
}
