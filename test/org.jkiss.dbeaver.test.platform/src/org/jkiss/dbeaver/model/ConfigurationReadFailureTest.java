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
package org.jkiss.dbeaver.model;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPPlatform;
import org.jkiss.dbeaver.model.app.DBPApplicationWorkbench;
import org.jkiss.dbeaver.model.impl.app.DefaultValueEncryptor;
import org.jkiss.dbeaver.registry.DataSourceConfigurationManager;
import org.jkiss.dbeaver.registry.DataSourceRegistry;
import org.jkiss.dbeaver.registry.DataSourceSerializerModern;
import org.jkiss.dbeaver.registry.DataSourceParseResults;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigurationReadFailureTest {
    private final DataSourceSerializerModern<?> serializer = mock(DataSourceSerializerModern.class);
    private final DataSourceConfigurationManager manager = mock(DataSourceConfigurationManager.class);
    private final DBPDataSourceConfigurationStorage storage = mock(DBPDataSourceConfigurationStorage.class);
    private final DefaultValueEncryptor encryptor = spy(new DefaultValueEncryptor(
        DefaultValueEncryptor.makeSecretKeyFromPassword("synthetic-key-02")));

    private byte[] configure(boolean encrypted) throws Exception {
        var project = mock(DBPProject.class);
        var registry = mock(DataSourceRegistry.class);
        when(registry.getProject()).thenReturn(project);
        when(project.isEncryptedProject()).thenReturn(encrypted);
        when(project.getValueEncryptor()).thenReturn(encryptor);
        when(storage.getStorageName()).thenReturn("fixture.json");
        var field = DataSourceSerializerModern.class.getDeclaredField("registry");
        field.setAccessible(true);
        field.set(serializer, registry);
        byte[] json = "{\"label\":\"中文𠀀\",\"connections\":{}}".getBytes(StandardCharsets.UTF_8);
        return encrypted ? encryptor.encryptValue(json) : json;
    }

    private Object read(InputStream stream) throws Exception {
        when(manager.readConfiguration("fixture.json", null)).thenReturn(stream);
        var method = DataSourceSerializerModern.class.getDeclaredMethod("readConfiguration",
            DBPDataSourceConfigurationStorage.class, DataSourceConfigurationManager.class, Collection.class);
        method.setAccessible(true);
        try {
            return method.invoke(serializer, storage, manager, null);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completePlainAndEncryptedConfigurationReadAndClose(boolean encrypted) throws Exception {
        var stream = new FixtureStream(configure(encrypted), false, false, false);
        assertEquals(Map.of("label", "中文𠀀", "connections", Map.of()), read(stream));
        assertTrue(stream.closed);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validPrefixFollowedByIoFailureMustNotBeAccepted(boolean encrypted) throws Exception {
        var stream = new FixtureStream(configure(encrypted), false, true, false);
        DBException error = assertThrows(DBException.class, () -> read(stream));
        assertSame(stream.readFailure, error.getCause());
        assertTrue(stream.closed);
        verify(encryptor, never()).decryptValue(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void immediateIoFailureKeepsOriginalCauseAndClosesStream(boolean encrypted) throws Exception {
        var stream = new FixtureStream(configure(encrypted), true, false, false);
        DBException error = assertThrows(DBException.class, () -> read(stream));
        assertSame(stream.readFailure, error.getCause());
        assertTrue(stream.closed);
        verify(encryptor, never()).decryptValue(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeFailureIsNotReportedAsSuccessfulRead(boolean encrypted) throws Exception {
        var stream = new FixtureStream(configure(encrypted), false, false, true);
        DBException error = assertThrows(DBException.class, () -> read(stream));
        assertSame(stream.closeFailure, error.getCause());
        assertTrue(stream.closed);
    }

    @Test
    void missingFileIsDifferentFromFailedRead() throws Exception {
        configure(false);
        assertNull(read(null));
        verify(encryptor, never()).decryptValue(any());
    }

    @Test
    void readAndCloseFailurePreservePrimaryAndSuppressedCauses() throws Exception {
        var stream = new FixtureStream(configure(false), true, false, true);
        DBException error = assertThrows(DBException.class, () -> read(stream));
        assertSame(stream.readFailure, error.getCause());
        assertArrayEquals(new Throwable[] {stream.closeFailure}, error.getCause().getSuppressed());
        assertEquals(1, stream.closeCount);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicParserPropagatesConfigurationReadFailureWithoutApplyingResults(boolean encrypted) throws Exception {
        var stream = new FixtureStream(configure(encrypted), true, false, false);
        when(manager.readConfiguration("fixture.json", null)).thenReturn(stream);
        var results = new DataSourceParseResults();
        DBException error = assertThrows(DBException.class, () -> parse(results));
        assertSame(stream.readFailure, error.getCause());
        assertTrue(stream.closed);
        assertEmptyResults(results);
        verify(manager, times(1)).readConfiguration(anyString(), isNull());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicParserDoesNotApplyFoldersWhenCredentialReadOrDecryptFails(boolean damagedCiphertext) throws Exception {
        configure(false);
        when(storage.getStorageSubId()).thenReturn("");
        when(manager.readConfiguration("fixture.json", null)).thenReturn(new ByteArrayInputStream(
            "{\"folders\":{\"fixture-folder\":{}},\"connections\":{}}".getBytes(StandardCharsets.UTF_8)));
        String credentialsName = DBPDataSourceRegistry.CREDENTIALS_CONFIG_FILE_PREFIX
            + DBPDataSourceRegistry.CREDENTIALS_CONFIG_FILE_EXT;
        var stream = new FixtureStream(new byte[] {1}, !damagedCiphertext, false, false);
        when(manager.readConfiguration(credentialsName, null)).thenReturn(stream);
        var results = new DataSourceParseResults();
        DBException error = assertThrows(DBException.class, () -> parse(results));
        assertEquals("Project secure credentials can not be read", error.getMessage());
        assertTrue(stream.closed);
        assertEmptyResults(results);
    }

    private void parse(DataSourceParseResults results) throws Exception {
        var secrets = DataSourceSerializerModern.class.getDeclaredField("secureProperties");
        secrets.setAccessible(true);
        secrets.set(serializer, new HashMap<>());
        doCallRealMethod().when(serializer).parseDataSources(storage, manager, results, null);
        var platform = mock(DBPPlatform.class, RETURNS_DEEP_STUBS);
        when(platform.getApplication().isHeadlessMode()).thenReturn(true);
        var workbench = mock(DBPApplicationWorkbench.class);
        when(workbench.getPlatform()).thenReturn(platform);
        var field = DBWorkbench.class.getDeclaredField("applicationWorkbench");
        field.setAccessible(true);
        synchronized (DBWorkbench.class) {
            Object previous = field.get(null);
            try {
                field.set(null, workbench);
                serializer.parseDataSources(storage, manager, results, null);
            } finally {
                field.set(null, previous);
            }
        }
    }

    private void assertEmptyResults(DataSourceParseResults results) {
        assertTrue(results.addedDataSources.isEmpty());
        assertTrue(results.updatedDataSources.isEmpty());
        assertTrue(results.removedDataSources.isEmpty());
        assertTrue(results.addedFolders.isEmpty());
        assertTrue(results.updatedFolders.isEmpty());
        assertTrue(results.removedFolders.isEmpty());
        assertTrue(results.updatedProfiles.isEmpty());
        assertTrue(results.removedProfiles.isEmpty());
    }

    private static final class FixtureStream extends InputStream {
        private final ByteArrayInputStream bytes;
        private final boolean immediate;
        private final boolean afterPayload;
        private final boolean failClose;
        private final IOException readFailure = new IOException("synthetic read failure");
        private final IOException closeFailure = new IOException("synthetic close failure");
        private boolean closed;
        private int closeCount;

        private FixtureStream(byte[] bytes, boolean immediate, boolean afterPayload, boolean failClose) {
            this.bytes = new ByteArrayInputStream(bytes);
            this.immediate = immediate;
            this.afterPayload = afterPayload;
            this.failClose = failClose;
        }

        @Override
        public int read() throws IOException {
            if (immediate || afterPayload && bytes.available() == 0) {
                throw readFailure;
            }
            return bytes.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (immediate || afterPayload && bytes.available() == 0) {
                throw readFailure;
            }
            return bytes.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            closeCount++;
            if (failClose) {
                throw closeFailure;
            }
        }
    }
}
