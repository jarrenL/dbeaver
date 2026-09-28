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
import org.jkiss.dbeaver.model.data.DBDContentStorage;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class JDBCContentBytesReadTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 64})
    void binaryValueReadsCompleteChunkedStreams(int chunk) throws Exception {
        var content = new JDBCContentBytes(mock(DBCExecutionContext.class), new byte[] {9});
        var expected = new byte[257];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) i;
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var stream = new java.io.ByteArrayInputStream(expected) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(chunk, len));
            }
            @Override public void close() { closed.set(true); }
        };
        var storage = mock(DBDContentStorage.class);
        when(storage.getContentStream()).thenReturn(stream);
        when(storage.getContentLength()).thenReturn((long) expected.length);
        content.updateContents(new VoidProgressMonitor(), storage);
        assertTrue(closed.get());
        assertArrayEquals(expected, content.getContentStream().readAllBytes());
    }

    @Test
    void truncatedBinaryStreamDoesNotReplaceOriginalValue() throws Exception {
        var original = new byte[] {9, 8, 7};
        var content = new JDBCContentBytes(mock(DBCExecutionContext.class), original);
        var storage = mock(DBDContentStorage.class);
        when(storage.getContentStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[] {1}));
        when(storage.getContentLength()).thenReturn(3L);
        assertThrows(DBException.class, () -> content.updateContents(new VoidProgressMonitor(), storage));
        assertArrayEquals(original, content.getContentStream().readAllBytes());
        when(storage.getContentStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}));
        content.updateContents(new VoidProgressMonitor(), storage);
        assertArrayEquals(new byte[] {1, 2, 3}, content.getContentStream().readAllBytes());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, 2147483648L})
    void unsupportedByteArrayLengthsFailWithoutReplacingValue(long length) throws Exception {
        var original = new byte[] {9, 8, 7};
        var content = new JDBCContentBytes(mock(DBCExecutionContext.class), original);
        var stream = mock(java.io.InputStream.class);
        var storage = mock(DBDContentStorage.class);
        when(storage.getContentStream()).thenReturn(stream);
        when(storage.getContentLength()).thenReturn(length);
        assertThrows(DBException.class, () -> content.updateContents(new VoidProgressMonitor(), storage));
        verify(stream).close();
        verify(stream, never()).readNBytes(anyInt());
        assertArrayEquals(original, content.getContentStream().readAllBytes());
    }

}
