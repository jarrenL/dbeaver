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
    private static class UncanceledMonitor extends VoidProgressMonitor {
        @Override public boolean isCanceled() {
            return false;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void canceledUpdateDoesNotOpenStorageOrClearOriginal(boolean clearValue) throws Exception {
        var original = new byte[] {9, 8, 7};
        var content = new JDBCContentBytes(mock(DBCExecutionContext.class), original);
        var storage = mock(DBDContentStorage.class);
        var monitor = mock(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor.class);
        when(storage.getContentStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[] {1}));
        when(storage.getContentLength()).thenReturn(1L);
        when(monitor.isCanceled()).thenReturn(true);
        assertThrows(DBException.class, () -> content.updateContents(monitor, clearValue ? null : storage));
        verifyNoInteractions(storage);
        assertArrayEquals(original, content.getContentStream().readAllBytes());
        when(monitor.isCanceled()).thenReturn(false);
        when(storage.getContentStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[] {1}));
        when(storage.getContentLength()).thenReturn(1L);
        content.updateContents(monitor, clearValue ? null : storage);
        if (clearValue) {
            assertTrue(content.isNull());
        } else {
            assertArrayEquals(new byte[] {1}, content.getContentStream().readAllBytes());
        }
        content.resetContents();
        assertArrayEquals(original, content.getContentStream().readAllBytes());
    }

    @Test
    void cancellationAtEndOfReadDoesNotPublishAndAllowsRetry() throws Exception {
        var original = new byte[] {9, 8, 7};
        var content = new JDBCContentBytes(mock(DBCExecutionContext.class), original);
        var monitor = mock(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor.class);
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var stream = new java.io.ByteArrayInputStream(new byte[] {1}) {
            @Override public synchronized int read() {
                when(monitor.isCanceled()).thenReturn(true);
                return super.read();
            }
            @Override public void close() { closed.set(true); }
        };
        var storage = mock(DBDContentStorage.class);
        when(storage.getContentStream()).thenReturn(stream);
        when(storage.getContentLength()).thenReturn(1L);
        assertThrows(DBException.class, () -> content.updateContents(monitor, storage));
        assertTrue(closed.get());
        assertArrayEquals(original, content.getContentStream().readAllBytes());
        when(monitor.isCanceled()).thenReturn(false);
        when(storage.getContentStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[] {1}));
        content.updateContents(monitor, storage);
        assertArrayEquals(new byte[] {1}, content.getContentStream().readAllBytes());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 256})
    void streamLongerThanDeclaredLengthFailsWithoutSilentTruncation(int declaredLength) throws Exception {
        var original = new byte[] {9, 8, 7};
        var content = new JDBCContentBytes(mock(DBCExecutionContext.class), original);
        var actual = new byte[declaredLength + 1];
        for (int i = 0; i < actual.length; i++) {
            actual[i] = (byte) i;
        }
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var stream = new java.io.ByteArrayInputStream(actual) {
            @Override public void close() { closed.set(true); }
        };
        var storage = mock(DBDContentStorage.class);
        when(storage.getContentStream()).thenReturn(stream);
        when(storage.getContentLength()).thenReturn((long) declaredLength);
        var failure = assertThrows(DBException.class,
            () -> content.updateContents(new UncanceledMonitor(), storage));
        assertInstanceOf(java.io.IOException.class, failure.getCause());
        assertTrue(closed.get());
        assertArrayEquals(original, content.getContentStream().readAllBytes());
        when(storage.getContentStream()).thenReturn(new java.io.ByteArrayInputStream(actual));
        when(storage.getContentLength()).thenReturn((long) actual.length);
        content.updateContents(new UncanceledMonitor(), storage);
        assertArrayEquals(actual, content.getContentStream().readAllBytes());
        content.resetContents();
        assertArrayEquals(original, content.getContentStream().readAllBytes());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 128, 257})
    void readFailureClosesStreamPreservesValueAndAllowsRetry(int failAfter) throws Exception {
        var original = new byte[] {9, 8, 7};
        var content = new JDBCContentBytes(mock(DBCExecutionContext.class), original);
        var expected = new byte[257];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) i;
        }
        var failure = new java.io.IOException("Injected read failure");
        var consumed = new java.util.concurrent.atomic.AtomicInteger();
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var stream = new java.io.InputStream() {
            @Override
            public int read() throws java.io.IOException {
                if (consumed.get() >= failAfter) {
                    throw failure;
                }
                return Byte.toUnsignedInt(expected[consumed.getAndIncrement()]);
            }

            @Override
            public int read(byte[] target, int offset, int length) throws java.io.IOException {
                java.util.Objects.checkFromIndexSize(offset, length, target.length);
                if (length == 0) {
                    return 0;
                }
                if (consumed.get() >= failAfter) {
                    throw failure;
                }
                int count = Math.min(length, failAfter - consumed.get());
                System.arraycopy(expected, consumed.get(), target, offset, count);
                consumed.addAndGet(count);
                return count;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        var storage = mock(DBDContentStorage.class);
        when(storage.getContentStream()).thenReturn(stream);
        when(storage.getContentLength()).thenReturn((long) expected.length);
        var thrown = assertThrows(DBException.class,
            () -> content.updateContents(new UncanceledMonitor(), storage));
        assertSame(failure, thrown.getCause());
        assertEquals(failAfter, consumed.get());
        assertTrue(closed.get());
        assertArrayEquals(original, content.getContentStream().readAllBytes());

        when(storage.getContentStream()).thenReturn(new java.io.ByteArrayInputStream(expected));
        content.updateContents(new UncanceledMonitor(), storage);
        assertArrayEquals(expected, content.getContentStream().readAllBytes());
        content.resetContents();
        assertArrayEquals(original, content.getContentStream().readAllBytes());
    }

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
        content.updateContents(new UncanceledMonitor(), storage);
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
        assertThrows(DBException.class, () -> content.updateContents(new UncanceledMonitor(), storage));
        assertArrayEquals(original, content.getContentStream().readAllBytes());
        when(storage.getContentStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}));
        content.updateContents(new UncanceledMonitor(), storage);
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
        assertThrows(DBException.class, () -> content.updateContents(new UncanceledMonitor(), storage));
        verify(stream).close();
        verify(stream, never()).readNBytes(anyInt());
        assertArrayEquals(original, content.getContentStream().readAllBytes());
    }

}
