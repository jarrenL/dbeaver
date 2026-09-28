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

import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.access.DBAAuthProfile;
import org.jkiss.dbeaver.model.impl.app.DefaultValueEncryptor;
import org.jkiss.dbeaver.model.net.DBWNetworkProfileManager;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.registry.DataSourceConfigurationManagerNIO;
import org.jkiss.dbeaver.registry.DataSourceRegistry;
import org.jkiss.dbeaver.registry.DataSourceSerializerModern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigurationNIOPersistenceTest {
    @TempDir Path directory;

    private DataSourceConfigurationManagerNIO manager() throws IOException {
        Path metadata = Files.createDirectories(directory.resolve("元数据"));
        var project = mock(DBPProject.class);
        when(project.getMetadataFolder(false)).thenReturn(metadata);
        when(project.getMetadataFolder(true)).thenReturn(metadata);
        when(project.getAbsolutePath()).thenReturn(directory);
        return new DataSourceConfigurationManagerNIO(project);
    }

    private byte[] read(DataSourceConfigurationManagerNIO manager, String name) throws IOException {
        try (var stream = manager.readConfiguration(name, null)) {
            assertNotNull(stream);
            return stream.readAllBytes();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completeSerializerWritesEncryptedCredentialsAndDeletesOnlyActiveFile(boolean encryptedProject) throws Exception {
        Path metadata = Files.createDirectories(directory.resolve("项目元数据"));
        var project = mock(DBPProject.class);
        when(project.getMetadataFolder(anyBoolean())).thenReturn(metadata);
        when(project.getAbsolutePath()).thenReturn(directory);
        when(project.isEncryptedProject()).thenReturn(encryptedProject);
        var encryptor = new DefaultValueEncryptor(DefaultValueEncryptor.makeSecretKeyFromPassword("fixture-disk-key"));
        when(project.getValueEncryptor()).thenReturn(encryptor);
        var registry = mock(DataSourceRegistry.class);
        when(registry.getProject()).thenReturn(project);
        when(registry.getNetworkProfiles()).thenReturn(mock(DBWNetworkProfileManager.class));
        var profile = new DBAAuthProfile(project);
        profile.setProfileId("disk-profile");
        profile.setProfileName("磁盘配置𠀀");
        profile.setUserName("磁盘用户");
        profile.setUserPassword("fixture-disk-password");
        profile.setSavePassword(true);
        when(registry.getAllAuthProfiles()).thenReturn(List.of(profile));
        var storage = mock(DBPDataSourceConfigurationStorage.class);
        when(storage.getStorageName()).thenReturn("fixture.json");
        when(storage.getStorageSubId()).thenReturn("");
        when(storage.isDefault()).thenReturn(true);
        var manager = new DataSourceConfigurationManagerNIO(project);
        var constructor = DataSourceSerializerModern.class.getDeclaredConstructor(DataSourceRegistry.class);
        constructor.setAccessible(true);
        var saver = (DataSourceSerializerModern<?>) constructor.newInstance(registry);
        saver.saveDataSources(new VoidProgressMonitor(), manager, storage, List.of());
        String credentialName = DBPDataSourceRegistry.CREDENTIALS_CONFIG_FILE_PREFIX
            + DBPDataSourceRegistry.CREDENTIALS_CONFIG_FILE_EXT;
        byte[] config = read(manager, "fixture.json");
        String decodedConfig = new String(encryptedProject ? encryptor.decryptValue(config) : config, StandardCharsets.UTF_8);
        assertTrue(decodedConfig.contains("磁盘配置𠀀"));
        assertFalse(decodedConfig.contains("fixture-disk-password"));
        byte[] encryptedCredentials = read(manager, credentialName);
        assertFalse(new String(encryptedCredentials, StandardCharsets.UTF_8).contains("fixture-disk-password"));
        assertTrue(new String(encryptor.decryptValue(encryptedCredentials), StandardCharsets.UTF_8)
            .contains("fixture-disk-password"));
        profile.setSavePassword(false);
        saver = (DataSourceSerializerModern<?>) constructor.newInstance(registry);
        saver.saveDataSources(new VoidProgressMonitor(), manager, storage, List.of());
        String withoutPassword = new String(encryptor.decryptValue(read(manager, credentialName)), StandardCharsets.UTF_8);
        assertFalse(withoutPassword.contains("fixture-disk-password"));
        assertTrue(withoutPassword.contains("磁盘用户"));
        when(registry.getAllAuthProfiles()).thenReturn(List.of());
        saver = (DataSourceSerializerModern<?>) constructor.newInstance(registry);
        saver.saveDataSources(new VoidProgressMonitor(), manager, storage, List.of());
        assertFalse(Files.exists(metadata.resolve(credentialName)));
        assertNull(manager.readConfiguration(credentialName, null));
        Path backup = metadata.resolve((credentialName.startsWith(".") ? credentialName : "." + credentialName) + ".bak");
        assertArrayEquals(encryptedCredentials, Files.readAllBytes(backup));
        assertTrue(new String(encryptor.decryptValue(Files.readAllBytes(backup)), StandardCharsets.UTF_8)
            .contains("fixture-disk-password"), "Deleting the active file is not secure erasure of its backup");
        assertEquals("fixture-disk-password", profile.getUserPassword());
    }

    @ParameterizedTest
    @ValueSource(strings = {"连接配置.json", ".凭据配置.json"})
    void unicodeContentOverwriteAndDailyBackupPreserveBytes(String name) throws Exception {
        var manager = manager();
        byte[] original = "中文𠀀\"\\\n原配置".getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "较短".getBytes(StandardCharsets.UTF_8);
        manager.writeConfiguration(name, original);
        assertArrayEquals(original, read(manager, name));
        Path backup = directory.resolve("元数据").resolve((name.startsWith(".") ? name : "." + name) + ".bak");
        assertFalse(Files.exists(backup));
        manager.writeConfiguration(name, replacement);
        assertArrayEquals(replacement, read(manager, name));
        assertArrayEquals(original, Files.readAllBytes(backup));
        manager.writeConfiguration(name, new byte[] {0, 1, (byte) 255});
        assertArrayEquals(new byte[] {0, 1, (byte) 255}, read(manager, name));
        assertArrayEquals(original, Files.readAllBytes(backup));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nullAndEmptyWritesDeleteTargetButPreserveBackupAndUnrelatedFiles(boolean empty) throws Exception {
        var manager = manager();
        manager.writeConfiguration("credentials.json", new byte[] {1, 2, 3});
        Path unrelated = directory.resolve("元数据/unrelated.txt");
        Files.writeString(unrelated, "保留");
        manager.writeConfiguration("credentials.json", empty ? new byte[0] : null);
        assertFalse(Files.exists(directory.resolve("元数据/credentials.json")));
        assertNull(manager.readConfiguration("credentials.json", null));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(directory.resolve("元数据/.credentials.json.bak")));
        manager.writeConfiguration("credentials.json", null);
        assertEquals("保留", Files.readString(unrelated));
    }

    @Test
    void nonEmptyDirectoryWriteAndDeleteFailuresRemainVisibleAndCanRetry() throws Exception {
        var manager = manager();
        Path target = Files.createDirectory(directory.resolve("元数据/blocked.json"));
        Path child = target.resolve("keep.txt");
        Files.writeString(child, "原数据");
        assertThrows(IOException.class, () -> manager.writeConfiguration("blocked.json", new byte[] {4}));
        assertThrows(IOException.class, () -> manager.writeConfiguration("blocked.json", null));
        assertEquals("原数据", Files.readString(child));
        Path retained = directory.resolve("保留目录");
        Files.move(target, retained);
        manager.writeConfiguration("blocked.json", new byte[] {4, 5});
        assertArrayEquals(new byte[] {4, 5}, read(manager, "blocked.json"));
        assertEquals("原数据", Files.readString(retained.resolve("keep.txt")));
    }

    @Test
    void metadataLocationTakesPrecedenceAndMissingFileFallsBackToProjectRoot() throws Exception {
        var manager = manager();
        assertNull(manager.readConfiguration("legacy.json", null));
        Files.writeString(directory.resolve("legacy.json"), "旧位置");
        assertEquals("旧位置", new String(read(manager, "legacy.json"), StandardCharsets.UTF_8));
        manager.writeConfiguration("legacy.json", "新位置".getBytes(StandardCharsets.UTF_8));
        assertEquals("新位置", new String(read(manager, "legacy.json"), StandardCharsets.UTF_8));
        assertEquals("旧位置", Files.readString(directory.resolve("legacy.json")));
    }
}
