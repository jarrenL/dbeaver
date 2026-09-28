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
package org.jkiss.dbeaver.model.data.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BytesContentStorageTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 257, 65537})
    void readsCompleteChunkedContentAndClosesSource(int size) throws Exception {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) i;
        }
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var stream = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(length, 7));
            }
            @Override public void close() { closed.set(true); }
        };
        var storage = BytesContentStorage.createFromStream(stream, size, "UTF-8");
        assertTrue(closed.get());
        assertEquals(size, storage.getContentLength());
        assertArrayEquals(bytes, storage.getContentStream().readAllBytes());
        assertArrayEquals(bytes, storage.getContentStream().readAllBytes());
        stream.close();
        assertTrue(closed.get());
        assertArrayEquals(bytes, storage.getContentStream().readAllBytes());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 500})
    void lengthHintDoesNotTruncateOrPadActualContent(long hint) throws Exception {
        var text = "中文\n'quote'\\end";
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        var storage = BytesContentStorage.createFromStream(new ByteArrayInputStream(bytes), hint, "UTF-8");
        assertEquals(bytes.length, storage.getContentLength());
        assertArrayEquals(bytes, storage.getContentStream().readAllBytes());
        assertEquals("UTF-8", storage.getCharset());
        try (var reader = storage.getContentReader()) {
            var target = new java.io.StringWriter();
            reader.transferTo(target);
            assertEquals(text, target.toString());
        }
    }

    @Test
    void readFailurePropagatesAndClosesSource() throws Exception {
        var stream = mock(InputStream.class);
        var failure = new IOException("read failed");
        when(stream.read(any(byte[].class))).thenThrow(failure);
        when(stream.read(any(byte[].class), anyInt(), anyInt())).thenThrow(failure);
        assertSame(failure, assertThrows(IOException.class,
            () -> BytesContentStorage.createFromStream(stream, 8, "UTF-8")));
        verify(stream).close();
    }

    @Test
    void excessiveDeclaredLengthRejectedBeforeReading() throws Exception {
        var stream = mock(InputStream.class);
        assertThrows(IOException.class,
            () -> BytesContentStorage.createFromStream(stream, 2147483648L, "UTF-8"));
        verifyNoInteractions(stream);
    }

    @Test
    void releasingOriginalKeepsClonedStorageReadable() throws Exception {
        var storage = new BytesContentStorage(new byte[] {0, -1, 127}, "UTF-8");
        var clone = storage.cloneStorage(mock(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor.class));
        storage.release();
        assertArrayEquals(new byte[] {0, -1, 127}, clone.getContentStream().readAllBytes());
        assertEquals("UTF-8", clone.getCharset());
    }
}
