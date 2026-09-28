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

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StringContentStorageTest {
    @ParameterizedTest
    @ValueSource(strings = {"known-empty", "unknown-empty", "known-chunked", "unknown-chunked"})
    void bothFactoriesPreserveFullTextIncludingSplitSurrogatePairs(String mode) throws Exception {
        var expected = mode.endsWith("empty") ? "" : "中文𠀀\r\n'quoted'\\end".repeat(1500);
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var reader = new StringReader(expected) {
            @Override public int read(char[] target, int offset, int length) throws IOException {
                return super.read(target, offset, Math.min(length, 1));
            }
            @Override public void close() {
                closed.set(true);
                super.close();
            }
        };
        var storage = mode.startsWith("known")
            ? StringContentStorage.createFromReader(reader, expected.length())
            : StringContentStorage.createFromReader(reader);
        assertFalse(closed.get(), "Reader remains owned by the caller");
        reader.close();
        assertEquals(expected, storage.getCachedValue());
        assertEquals(expected.length(), storage.getContentLength(), "Length uses Java UTF-16 units");
        try (var cached = storage.getContentReader()) {
            var target = new java.io.StringWriter();
            cached.transferTo(target);
            assertEquals(expected, target.toString());
        }
        assertArrayEquals(expected.getBytes(Charset.forName(storage.getCharset())),
            storage.getContentStream().readAllBytes());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 500})
    void declaredSizeIsOnlyCapacityHintAndDoesNotTruncate(long hint) throws Exception {
        var expected = "中文𠀀\n";
        var storage = StringContentStorage.createFromReader(new StringReader(expected), hint);
        assertEquals(expected, storage.getCachedValue());
        assertEquals(expected.length(), storage.getContentLength());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failuresPropagateAndCallerRetainsReaderOwnership(boolean knownLength) throws Exception {
        var reader = mock(Reader.class);
        var failure = new IOException("reader failure");
        when(reader.read(any(char[].class))).thenThrow(failure);
        assertSame(failure, assertThrows(IOException.class, () -> {
            if (knownLength) {
                StringContentStorage.createFromReader(reader, 12);
            } else {
                StringContentStorage.createFromReader(reader);
            }
        }));
        verify(reader, never()).close();
    }

    @Test
    void hugeDeclaredSizeRejectedBeforeReaderAccess() throws Exception {
        var reader = mock(Reader.class);
        assertThrows(IOException.class,
            () -> StringContentStorage.createFromReader(reader, Integer.MAX_VALUE));
        verifyNoInteractions(reader);
    }

    @Test
    void releasingSourceDoesNotInvalidateClone() throws Exception {
        var storage = new StringContentStorage("中文𠀀");
        var clone = storage.cloneStorage(mock(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor.class));
        storage.release();
        assertEquals("中文𠀀", ((StringContentStorage) clone).getCachedValue());
        assertEquals(4, clone.getContentLength());
    }
}
