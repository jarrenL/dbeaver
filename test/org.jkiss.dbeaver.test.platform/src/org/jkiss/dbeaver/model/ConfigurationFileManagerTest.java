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

import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.registry.DataSourceConfigurationManagerNIO;
import org.jkiss.dbeaver.registry.DataSourceConfigurationManager;
import org.jkiss.dbeaver.registry.DataSourceRegistry;
import org.jkiss.dbeaver.registry.DataSourceSerializerModern;
import org.jkiss.dbeaver.model.impl.app.DefaultValueEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigurationFileManagerTest {
    @TempDir
    Path directory;
    private Path metadata;
    private DataSourceConfigurationManagerNIO manager;
    private static final String CONFIG = DBPDataSourceRegistry.MODERN_CONFIG_FILE_NAME;

    @BeforeEach
    void initialize() throws Exception {
        metadata = Files.createDirectory(directory.resolve("metadata"));
        var project = mock(DBPProject.class);
        when(project.getAbsolutePath()).thenReturn(directory);
        when(project.getMetadataFolder(anyBoolean())).thenReturn(metadata);
        manager = new DataSourceConfigurationManagerNIO(project);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] read(String name) throws Exception {
        try (var stream = manager.readConfiguration(name, null)) {
            return stream == null ? null : stream.readAllBytes();
        }
    }

    @Test
    void writeOverwriteAndBackupPreserveUnicodeBytes() throws Exception {
        byte[] initial = bytes("{\"name\":\"中文𠀀\"}");
        manager.writeConfiguration(CONFIG, initial);
        assertArrayEquals(initial, read(CONFIG));
        byte[] replacement = bytes("{\"name\":\"更新\"}");
        manager.writeConfiguration(CONFIG, replacement);
        assertArrayEquals(replacement, read(CONFIG));
        assertArrayEquals(initial, Files.readAllBytes(metadata.resolve("." + CONFIG + ".bak")));
        assertFalse(manager.isReadOnly());
        assertFalse(manager.isSecure());
    }

    @Test
    void missingFileReturnsNull() throws Exception {
        assertNull(read("missing.json"));
    }

    @Test
    void legacyFileCanBeReadFromProjectRoot() throws Exception {
        String name = DBPDataSourceRegistry.LEGACY_CONFIG_FILE_NAME;
        Files.write(directory.resolve(name), bytes("legacy-fixture"));
        assertArrayEquals(bytes("legacy-fixture"), read(name));
    }

    @Test
    void metadataFileTakesPrecedenceOverProjectRootCopy() throws Exception {
        Files.write(directory.resolve(CONFIG), bytes("root-copy"));
        Files.write(metadata.resolve(CONFIG), bytes("metadata-copy"));
        assertArrayEquals(bytes("metadata-copy"), read(CONFIG));
        assertEquals("root-copy", Files.readString(directory.resolve(CONFIG)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nullOrEmptyWriteRemovesOnlyRequestedFileAndKeepsBackup(boolean empty) throws Exception {
        manager.writeConfiguration(CONFIG, bytes("old-data"));
        Files.writeString(metadata.resolve("unrelated.txt"), "keep");
        manager.writeConfiguration(CONFIG, empty ? new byte[0] : null);
        assertFalse(Files.exists(metadata.resolve(CONFIG)));
        assertEquals("old-data", Files.readString(metadata.resolve("." + CONFIG + ".bak")));
        assertEquals("keep", Files.readString(metadata.resolve("unrelated.txt")));
        manager.writeConfiguration(CONFIG, empty ? new byte[0] : null);
    }

    @Test
    void discoversModernStoresAndIgnoresLegacyBackupAndDirectories() throws Exception {
        Files.writeString(metadata.resolve(CONFIG), "{}");
        String extra = DBPDataSourceRegistry.MODERN_CONFIG_FILE_PREFIX + "-extra"
            + DBPDataSourceRegistry.MODERN_CONFIG_FILE_EXT;
        Files.writeString(metadata.resolve(extra), "{}");
        Files.writeString(metadata.resolve("." + CONFIG + ".bak"), "{}");
        Files.createDirectory(metadata.resolve(DBPDataSourceRegistry.MODERN_CONFIG_FILE_PREFIX + "-directory.json"));
        Files.writeString(directory.resolve(DBPDataSourceRegistry.LEGACY_CONFIG_FILE_NAME), "legacy");
        var stores = manager.getConfigurationStorages();
        assertEquals(Set.of(CONFIG, extra), stores.stream().map(DBPDataSourceConfigurationStorage::getStorageName)
            .collect(Collectors.toSet()));
        assertEquals(1, stores.stream().filter(DBPDataSourceConfigurationStorage::isDefault).count());
    }

    @Test
    void discoversLegacyWhenNoModernFileExists() throws Exception {
        Files.writeString(directory.resolve(DBPDataSourceRegistry.LEGACY_CONFIG_FILE_NAME), "legacy");
        var stores = manager.getConfigurationStorages();
        assertEquals(1, stores.size());
        assertEquals(DBPDataSourceRegistry.LEGACY_CONFIG_FILE_NAME, stores.getFirst().getStorageName());
        assertTrue(stores.getFirst().isDefault());
    }

    @Test
    void emptyProjectOffersDefaultStorageWithoutCreatingFile() {
        var stores = manager.getConfigurationStorages();
        assertEquals(1, stores.size());
        assertEquals(CONFIG, stores.getFirst().getStorageName());
        assertTrue(stores.getFirst().isDefault());
        assertFalse(Files.exists(metadata.resolve(CONFIG)));
    }

    @Test
    void deletionFailureIsReportedAndDoesNotDeleteDirectoryChildren() throws Exception {
        Path invalidTarget = Files.createDirectory(metadata.resolve(CONFIG));
        Files.writeString(invalidTarget.resolve("sentinel.txt"), "keep");
        assertThrows(IOException.class, () -> manager.writeConfiguration(CONFIG, null));
        assertEquals("keep", Files.readString(invalidTarget.resolve("sentinel.txt")));
    }

    @Test
    void writeFailureIsReportedWithoutModifyingDirectoryChildren() throws Exception {
        Path invalidTarget = Files.createDirectory(metadata.resolve(CONFIG));
        Files.writeString(invalidTarget.resolve("sentinel.txt"), "keep");
        assertThrows(IOException.class, () -> manager.writeConfiguration(CONFIG, bytes("new-data")));
        assertEquals("keep", Files.readString(invalidTarget.resolve("sentinel.txt")));
    }

    @Test
    void subsequentSameDayWritesKeepFirstBackup() throws Exception {
        manager.writeConfiguration(CONFIG, bytes("first"));
        manager.writeConfiguration(CONFIG, bytes("second"));
        manager.writeConfiguration(CONFIG, bytes("third"));
        assertEquals("third", Files.readString(metadata.resolve(CONFIG)));
        assertEquals("first", Files.readString(metadata.resolve("." + CONFIG + ".bak")));
    }

    @Test
    void olderBackupIsReplacedWithContentBeforeCurrentWrite() throws Exception {
        manager.writeConfiguration(CONFIG, bytes("first"));
        manager.writeConfiguration(CONFIG, bytes("second"));
        Path backup = metadata.resolve("." + CONFIG + ".bak");
        Files.setLastModifiedTime(backup, FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS)));
        manager.writeConfiguration(CONFIG, bytes("third"));
        assertEquals("second", Files.readString(backup));
        assertEquals("third", Files.readString(metadata.resolve(CONFIG)));
    }

    @Test
    void dotPrefixedFileUsesSingleLeadingDotForBackup() throws Exception {
        String name = ".fixture-credentials";
        manager.writeConfiguration(name, bytes("first"));
        manager.writeConfiguration(name, bytes("second"));
        assertEquals("first", Files.readString(metadata.resolve(name + ".bak")));
        assertFalse(Files.exists(metadata.resolve("." + name + ".bak")));
    }

    @Test
    void deletedConfigurationCanBeRestoredFromRetainedBackup() throws Exception {
        manager.writeConfiguration(CONFIG, bytes("中文恢复数据"));
        manager.writeConfiguration(CONFIG, null);
        Path backup = metadata.resolve("." + CONFIG + ".bak");
        byte[] retained = Files.readAllBytes(backup);
        manager.writeConfiguration(CONFIG, retained);
        assertArrayEquals(retained, read(CONFIG));
        assertArrayEquals(retained, Files.readAllBytes(backup));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void productionSerializerWritesAndReadsRealFilesAndRetainsBackupOnRemoval(boolean encrypted) throws Exception {
        var serializer = serializerWithFixtureKey();
        String name = "fixture-credentials.json";
        String content = "{\"user\":\"中文𠀀\",\"password\":\"synthetic-credential-only\"}";
        var save = DataSourceSerializerModern.class.getDeclaredMethod("saveConfigFile",
            DataSourceConfigurationManager.class, String.class, String.class, boolean.class);
        save.setAccessible(true);
        save.invoke(serializer, manager, name, content, encrypted);
        byte[] stored = Files.readAllBytes(metadata.resolve(name));
        if (encrypted) {
            assertFalse(new String(stored, StandardCharsets.UTF_8).contains("synthetic-credential-only"));
        } else {
            assertArrayEquals(bytes(content), stored);
        }
        var load = DataSourceSerializerModern.class.getDeclaredMethod("loadConfigFile", InputStream.class, boolean.class);
        load.setAccessible(true);
        try (var stream = manager.readConfiguration(name, null)) {
            assertEquals(content, load.invoke(serializerWithFixtureKey(), stream, encrypted));
        }
        save.invoke(serializer, manager, name, null, encrypted);
        assertNull(read(name));
        Path backup = metadata.resolve("." + name + ".bak");
        assertArrayEquals(stored, Files.readAllBytes(backup));
        try (var stream = Files.newInputStream(backup)) {
            assertEquals(content, load.invoke(serializerWithFixtureKey(), stream, encrypted));
        }
    }

    private DataSourceSerializerModern<?> serializerWithFixtureKey() throws Exception {
        var serializer = mock(DataSourceSerializerModern.class);
        var registry = mock(DataSourceRegistry.class);
        var project = mock(DBPProject.class);
        when(registry.getProject()).thenReturn(project);
        when(project.getValueEncryptor()).thenReturn(new DefaultValueEncryptor(
            DefaultValueEncryptor.makeSecretKeyFromPassword("synthetic-key-03")));
        var field = DataSourceSerializerModern.class.getDeclaredField("registry");
        field.setAccessible(true);
        field.set(serializer, registry);
        return serializer;
    }
}
