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

class JDBCContentBLOBReadTest {
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
    @ParameterizedTest
    @ValueSource(strings = {"success", "read", "first-close", "second-close"})
    void memoryReadDoesNotPublishBeforeAllStreamClosesSucceed(String stage) throws Exception {
        var blob = mock(Blob.class);
        var original = new byte[] {0, -1, 7, 127};
        var replacement = new byte[] {4, 3, 2, 1};
        when(blob.length()).thenReturn(4L);
        var failure = new IOException("memory stream failure");
        var closes = new java.util.concurrent.atomic.AtomicInteger();
        var stream = new FilterInputStream(new ByteArrayInputStream(original)) {
            @Override public int read(byte[] target, int offset, int length) throws IOException {
                if (stage.equals("read")) throw failure;
                return super.read(target, offset, length);
            }
            @Override public void close() throws IOException {
                int count = closes.incrementAndGet();
                super.close();
                if (stage.equals("first-close") && count == 1 || stage.equals("second-close") && count == 2) {
                    throw failure;
                }
            }
        };
        when(blob.getBinaryStream()).thenReturn(stream);
        var monitor = mock(DBRProgressMonitor.class);
        var content = new JDBCContentBLOB(mock(DBCExecutionContext.class), blob) {
            @Override protected String getDefaultEncoding() { return "UTF-8"; }
        };
        if (!stage.equals("success")) {
            var thrown = assertThrows(DBException.class, () -> content.getContents(monitor));
            assertSame(failure, thrown.getCause().getCause());
            verify(blob, never()).free();
            when(blob.getBinaryStream()).thenReturn(new ByteArrayInputStream(replacement));
        }
        var storage = content.getContents(monitor);
        assertArrayEquals(stage.equals("success") ? original : replacement, storage.getContentStream().readAllBytes());
        assertTrue(closes.get() > 0);
        verify(blob, times(stage.equals("success") ? 1 : 2)).getBinaryStream();
        verify(blob).free();
        assertSame(storage, content.getContents(monitor));
        content.release();
        verify(blob).free();
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "read", "close", "cancel"})
    void diskCopyPreservesBytesAndCleansFailureBeforeRetry(String stage) throws Exception {
        when(platform.getPreferenceStore().getInt(ModelPreferences.MEMORY_CONTENT_MAX_SIZE)).thenReturn(0);
        when(platform.getTempFolder(any(), anyString())).thenReturn(directory);
        var blob = mock(Blob.class);
        var expected = new byte[257];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) i;
        when(blob.length()).thenReturn((long) expected.length);
        var monitor = mock(DBRProgressMonitor.class);
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var failure = new IOException("injected stream failure");
        var stream = new FilterInputStream(new ByteArrayInputStream(expected)) {
            int calls;
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (calls++ > 0 && stage.equals("read")) throw failure;
                int count = super.read(bytes, offset, Math.min(7, length));
                if (stage.equals("cancel")) when(monitor.isCanceled()).thenReturn(true);
                return count;
            }
            @Override public void close() throws IOException {
                closed.set(true);
                super.close();
                if (stage.equals("close")) throw failure;
            }
        };
        when(blob.getBinaryStream()).thenReturn(stream);
        var content = new JDBCContentBLOB(mock(DBCExecutionContext.class), blob) {
            @Override protected String getDefaultEncoding() { return "UTF-8"; }
        };
        if (!stage.equals("success")) {
            var thrown = assertThrows(DBException.class, () -> content.getContents(monitor));
            if (stage.equals("cancel")) assertInstanceOf(InterruptedIOException.class, thrown.getCause().getCause());
            else assertSame(failure, thrown.getCause().getCause());
            assertTrue(closed.get());
            verify(blob, never()).free();
            try (var files = java.nio.file.Files.list(directory)) { assertEquals(0, files.count()); }
            when(monitor.isCanceled()).thenReturn(false);
            when(blob.getBinaryStream()).thenReturn(new ByteArrayInputStream(expected));
        }
        var storage = content.getContents(monitor);
        var file = ((org.jkiss.dbeaver.model.data.DBDContentStorageLocal) storage).getDataFile();
        assertArrayEquals(expected, java.nio.file.Files.readAllBytes(file));
        assertEquals(expected.length, storage.getContentLength());
        assertTrue(closed.get());
        verify(blob).free();
        assertSame(storage, content.getContents(monitor));
        content.release();
        assertFalse(java.nio.file.Files.exists(file));
        try (var files = java.nio.file.Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
