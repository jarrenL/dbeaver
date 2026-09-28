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
package org.jkiss.dbeaver.ext.gaussdb.model.data;

import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBytes;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;
import org.junit.jupiter.api.Test;
import java.sql.Types;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBBinaryValueHandlerTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void lengthOverloadFallbackRestartsFile(boolean useInt, @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
        throws Exception {
        byte[] expected = new byte[257];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) i;
        }
        var file = directory.resolve("overloads.bin");
        java.nio.file.Files.write(file, expected);
        var storage = new org.jkiss.dbeaver.model.data.storage.TemporaryContentStorage(
            mock(org.jkiss.dbeaver.model.app.DBPPlatform.class), file, "UTF-8", false);
        var content = new org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBLOB(mock(DBCExecutionContext.class), null);
        content.updateContents(new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(), storage);
        var streams = new java.util.ArrayList<java.io.InputStream>();
        var received = new java.util.ArrayList<byte[]>();
        doAnswer(call -> {
            java.io.InputStream stream = call.getArgument(1);
            streams.add(stream);
            stream.readNBytes(3);
            throw new AbstractMethodError("synthetic unavailable overload");
        }).when(statement).setBinaryStream(eq(1), any(java.io.InputStream.class));
        doAnswer(call -> {
            java.io.InputStream stream = call.getArgument(1);
            streams.add(stream);
            if (useInt) {
                stream.readNBytes(5);
                throw new AbstractMethodError("synthetic unavailable long overload");
            }
            received.add(stream.readAllBytes());
            return null;
        }).when(statement).setBinaryStream(eq(1), any(java.io.InputStream.class), eq(257L));
        doAnswer(call -> {
            java.io.InputStream stream = call.getArgument(1);
            streams.add(stream);
            received.add(stream.readAllBytes());
            return null;
        }).when(statement).setBinaryStream(eq(1), any(java.io.InputStream.class), eq(257));
        try {
            GaussDBBinaryValueHandler.INSTANCE.bindValueObject(session, statement, type, 0, content);
            assertEquals(1, received.size());
            assertArrayEquals(expected, received.getFirst());
            verify(statement).setBinaryStream(eq(1), any(java.io.InputStream.class));
            verify(statement).setBinaryStream(eq(1), any(java.io.InputStream.class), eq(257L));
            if (useInt) {
                verify(statement).setBinaryStream(eq(1), any(java.io.InputStream.class), eq(257));
            }
            verifyNoMoreInteractions(statement);
        } finally {
            content.release();
        }
        for (var stream : streams) {
            assertThrows(java.io.IOException.class, stream::read);
        }
        assertArrayEquals(expected, java.nio.file.Files.readAllBytes(file));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"256,0", "256,7", "65537,0", "65537,7"})
    void unsupportedStreamFallbackMustBindEntireFile(
        int length, int consumed, @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory
    ) throws Exception {
        byte[] expected = new byte[length];
        for (int i = 0; i < length; i++) {
            expected[i] = (byte) i;
        }
        var file = directory.resolve("fallback.bin");
        java.nio.file.Files.write(file, expected);
        var storage = new org.jkiss.dbeaver.model.data.storage.TemporaryContentStorage(
            mock(org.jkiss.dbeaver.model.app.DBPPlatform.class), file, "UTF-8", false);
        var content = new org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBLOB(mock(DBCExecutionContext.class), null);
        content.updateContents(new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(), storage);
        when(session.getProgressMonitor()).thenReturn(mock(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor.class));
        var streams = new java.util.ArrayList<java.io.InputStream>();
        doAnswer(invocation -> {
            java.io.InputStream stream = invocation.getArgument(1);
            streams.add(stream);
            assertEquals(consumed, stream.readNBytes(consumed).length);
            throw new java.sql.SQLFeatureNotSupportedException("synthetic unsupported streaming");
        }).when(statement).setBinaryStream(eq(1), any(java.io.InputStream.class));
        try {
            GaussDBBinaryValueHandler.INSTANCE.bindValueObject(session, statement, type, 0, content);
            verify(statement).setBinaryStream(eq(1), any(java.io.InputStream.class));
            verify(statement).setBytes(1, expected);
            verifyNoMoreInteractions(statement);
        } finally {
            content.release();
        }
        assertThrows(java.io.IOException.class, () -> streams.getFirst().read());
        assertArrayEquals(expected, java.nio.file.Files.readAllBytes(file));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,false", "256,false", "65537,false", "1,true", "256,true", "65537,true"})
    void nonemptyFileBindsCompleteBytesAndCanRetryAfterDriverFailure(
        int length, boolean failFirst, @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory
    ) throws Exception {
        when(session.getExecutionContext()).thenReturn(mock(org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext.class));
        byte[] expected = new byte[length];
        for (int i = 0; i < length; i++) {
            expected[i] = (byte) i;
        }
        var file = directory.resolve("中文 数据.bin");
        java.nio.file.Files.write(file, expected);
        var storage = new org.jkiss.dbeaver.model.data.storage.TemporaryContentStorage(
            mock(org.jkiss.dbeaver.model.app.DBPPlatform.class), file, "UTF-8", false);
        var content = new org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBLOB(mock(DBCExecutionContext.class), null);
        content.updateContents(new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(), storage);
        var streams = new java.util.ArrayList<java.io.InputStream>();
        var failure = new java.sql.SQLException("synthetic stream binding failure", "08006");
        doAnswer(invocation -> {
            java.io.InputStream stream = invocation.getArgument(1);
            streams.add(stream);
            assertArrayEquals(expected, stream.readAllBytes());
            if (failFirst && streams.size() == 1) {
                throw failure;
            }
            return null;
        }).when(statement).setBinaryStream(eq(3), any(java.io.InputStream.class));
        try {
            if (failFirst) {
                var error = assertThrows(DBCException.class,
                    () -> GaussDBBinaryValueHandler.INSTANCE.bindValueObject(session, statement, type, 2, content));
                assertSame(failure, error.getCause());
            }
            GaussDBBinaryValueHandler.INSTANCE.bindValueObject(session, statement, type, 2, content);
            verify(statement, times(failFirst ? 2 : 1)).setBinaryStream(eq(3), any(java.io.InputStream.class));
            verifyNoMoreInteractions(statement);
            if (failFirst) {
                assertThrows(java.io.IOException.class, () -> streams.getFirst().read());
            }
        } finally {
            content.release();
        }
        assertThrows(java.io.IOException.class, () -> streams.getLast().read());
        assertArrayEquals(expected, java.nio.file.Files.readAllBytes(file));
    }

    private final JDBCSession session = mock(JDBCSession.class);
    private final JDBCPreparedStatement statement = mock(JDBCPreparedStatement.class);
    private final DBSTypedObject type = mock(DBSTypedObject.class);

    private void bind(byte[] bytes) throws Exception {
        when(type.getTypeID()).thenReturn(Types.BINARY);
        when(type.getTypeName()).thenReturn("bytea");
        GaussDBBinaryValueHandler.INSTANCE.bindValueObject(session, statement, type, 2,
            new JDBCContentBytes(mock(DBCExecutionContext.class), bytes));
    }

    @Test void emptyUsesHexParameterWithOneBasedIndex() throws Exception {
        bind(new byte[0]);
        verify(statement).setObject(3, "\\x", Types.OTHER);
        verifyNoMoreInteractions(statement);
    }

    @Test void nonemptyKeepsBinaryBinding() throws Exception {
        byte[] bytes = {0, (byte) 255};
        bind(bytes);
        verify(statement).setBytes(3, bytes);
        verifyNoMoreInteractions(statement);
    }

    @Test void sqlNullIsNotEmpty() throws Exception {
        bind(null);
        verify(statement).setNull(3, Types.BINARY, "bytea");
        verifyNoMoreInteractions(statement);
    }

    @Test void bindingFailureIsNotSwallowed() throws Exception {
        doThrow(new java.sql.SQLException("synthetic", "08006")).when(statement).setObject(3, "\\x", Types.OTHER);
        assertThrows(DBCException.class, () -> bind(new byte[0]));
    }

    @Test void providerChoosesGaussByteaHandler() {
        when(type.getTypeName()).thenReturn("BYTEA");
        assertSame(GaussDBBinaryValueHandler.INSTANCE, new GaussDBValueHandlerProvider().getValueHandler(
            mock(org.jkiss.dbeaver.model.DBPDataSource.class), mock(org.jkiss.dbeaver.model.data.DBDFormatSettings.class), type));
    }

    @Test void emptyBlobUsesHexWithoutOpeningStream() throws Exception {
        var blob = mock(java.sql.Blob.class);
        when(blob.length()).thenReturn(0L);
        var content = new org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBLOB(mock(DBCExecutionContext.class), blob);
        GaussDBBinaryValueHandler.INSTANCE.bindValueObject(session, statement, type, 0, content);
        verify(statement).setObject(1, "\\x", Types.OTHER);
        verify(blob, never()).getBinaryStream();
        verifyNoMoreInteractions(statement);
    }

    @Test void unknownLengthIsNotTreatedAsEmpty() throws Exception {
        var content = mock(org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBLOB.class);
        when(content.isNull()).thenReturn(false);
        when(content.getContentLength()).thenReturn(-1L);
        GaussDBBinaryValueHandler.INSTANCE.bindValueObject(session, statement, type, 0, content);
        verify(content).bindParameter(session, statement, type, 1);
        verifyNoInteractions(statement);
    }

    @Test void nonemptyBlobKeepsOriginalBinding() throws Exception {
        var content = mock(org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBLOB.class);
        when(content.getContentLength()).thenReturn(1024L);
        GaussDBBinaryValueHandler.INSTANCE.bindValueObject(session, statement, type, 0, content);
        verify(content).bindParameter(session, statement, type, 1);
        verifyNoInteractions(statement);
    }

    @Test void lengthReadFailureDoesNotBindNullOrEmpty() throws Exception {
        var content = mock(org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBLOB.class);
        when(content.getContentLength()).thenThrow(new DBCException("synthetic length failure"));
        assertThrows(DBCException.class, () -> GaussDBBinaryValueHandler.INSTANCE.bindValueObject(session, statement, type, 0, content));
        verifyNoInteractions(statement);
    }

    @Test void nullBlobDoesNotReadLength() throws Exception {
        when(type.getTypeID()).thenReturn(Types.BINARY);
        when(type.getTypeName()).thenReturn("bytea");
        var content = mock(org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBLOB.class);
        when(content.isNull()).thenReturn(true);
        GaussDBBinaryValueHandler.INSTANCE.bindValueObject(session, statement, type, 0, content);
        verify(statement).setNull(1, Types.BINARY, "bytea");
        verify(content, never()).getContentLength();
    }

    @Test void actualEmptyFileRemainsNonNull(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var file = java.nio.file.Files.createFile(directory.resolve("empty.bin"));
        var storage = new org.jkiss.dbeaver.model.data.storage.TemporaryContentStorage(
            mock(org.jkiss.dbeaver.model.app.DBPPlatform.class), file, "UTF-8", false);
        var content = new org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBLOB(mock(DBCExecutionContext.class), null);
        content.updateContents(new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(), storage);
        assertFalse(content.isNull());
        GaussDBBinaryValueHandler.INSTANCE.bindValueObject(session, statement, type, 0, content);
        verify(statement).setObject(1, "\\x", Types.OTHER);
        verifyNoMoreInteractions(statement);
        assertTrue(java.nio.file.Files.exists(file), "Binding must not remove the imported file");
        assertEquals(0, java.nio.file.Files.size(file));
    }

    @Test void missingImportFileIsNotSilentlyEmpty(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var storage = new org.jkiss.dbeaver.model.data.storage.TemporaryContentStorage(
            mock(org.jkiss.dbeaver.model.app.DBPPlatform.class), directory.resolve("missing.bin"), "UTF-8", false);
        var content = new org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBLOB(mock(DBCExecutionContext.class), null);
        content.updateContents(new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(), storage);
        assertThrows(DBCException.class, () -> GaussDBBinaryValueHandler.INSTANCE.bindValueObject(session, statement, type, 0, content));
        verifyNoInteractions(statement);
    }
}
