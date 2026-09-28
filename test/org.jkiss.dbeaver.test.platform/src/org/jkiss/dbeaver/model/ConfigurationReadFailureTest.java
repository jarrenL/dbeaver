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
import org.jkiss.dbeaver.model.access.DBAAuthProfile;
import org.jkiss.dbeaver.model.impl.app.DefaultValueEncryptor;
import org.jkiss.dbeaver.model.net.DBWNetworkProfile;
import org.jkiss.dbeaver.model.net.DBWNetworkProfileManager;
import org.jkiss.dbeaver.model.net.DBWHandlerConfiguration;
import org.jkiss.dbeaver.registry.network.NetworkHandlerRegistry;
import org.jkiss.dbeaver.registry.DataSourceConfigurationManager;
import org.jkiss.dbeaver.registry.DataSourceRegistry;
import org.jkiss.dbeaver.registry.DataSourceSerializerModern;
import org.jkiss.dbeaver.registry.DataSourceParseResults;
import org.jkiss.dbeaver.registry.DataSourceParser;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigurationReadFailureTest {
    private final DataSourceSerializerModern<?> serializer = mock(DataSourceSerializerModern.class);
    private final DataSourceConfigurationManager manager = mock(DataSourceConfigurationManager.class);
    private final DBPDataSourceConfigurationStorage storage = mock(DBPDataSourceConfigurationStorage.class);
    private final DataSourceRegistry<?> registry = mock(DataSourceRegistry.class);
    private final DefaultValueEncryptor encryptor = spy(new DefaultValueEncryptor(
        DefaultValueEncryptor.makeSecretKeyFromPassword("synthetic-key-02")));

    private byte[] configure(boolean encrypted) throws Exception {
        var project = mock(DBPProject.class);
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readableConfigurationLoadsFoldersAndAuthProfileWithEncryptedCredentials(boolean encrypted) throws Exception {
        configure(encrypted);
        byte[] config = fixtureConfiguration();
        var configStream = new FixtureStream(encrypted ? encryptor.encryptValue(config) : config, false, false, false);
        when(manager.readConfiguration("fixture.json", null)).thenReturn(configStream);
        when(storage.getStorageSubId()).thenReturn("");
        byte[] credentials = encryptor.encryptValue(
            "{\"profile:fixture-profile\":{\"#connection\":{\"user\":\"中文用户\",\"password\":\"synthetic-password\"}}}"
                .getBytes(StandardCharsets.UTF_8));
        var credentialStream = new FixtureStream(credentials, false, false, false);
        when(manager.readConfiguration(DBPDataSourceRegistry.CREDENTIALS_CONFIG_FILE_PREFIX
            + DBPDataSourceRegistry.CREDENTIALS_CONFIG_FILE_EXT, null)).thenReturn(credentialStream);
        var captured = captureProfiles();
        var results = new DataSourceParseResults();
        parse(results);
        assertEquals("fixture-folder", results.addedFolders.iterator().next().getName());
        assertEquals(1, results.addedFolders.size());
        assertEquals(1, captured.get().size());
        assertEquals("中文用户", captured.get().getFirst().getUserName());
        assertEquals("synthetic-password", captured.get().getFirst().getUserPassword());
        assertEquals("fixture-profile", captured.get().getFirst().getProfileId());
        assertTrue(captured.get().getFirst().getProperties().isEmpty());
        assertEquals(1, configStream.closeCount);
        assertEquals(1, credentialStream.closeCount);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingCredentialFileStillAllowsConfigurationWithoutSavedPassword(boolean encrypted) throws Exception {
        configure(encrypted);
        byte[] config = fixtureConfiguration();
        byte[] contents = encrypted ? encryptor.encryptValue(config) : config;
        when(manager.readConfiguration("fixture.json", null)).thenReturn(new ByteArrayInputStream(contents));
        var captured = captureProfiles();
        var results = new DataSourceParseResults();
        parse(results);
        assertEquals(1, results.addedFolders.size());
        assertEquals(1, captured.get().size());
        assertNull(captured.get().getFirst().getUserPassword());
        assertTrue(captured.get().getFirst().getProperties().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"user\":\"测试用户\",\"password\":\"fixture-password\"}",
        "{\"user\":\"测试用户\",\"password\":\"fixture-password\",\"tenant\":\"中文租户\"}"})
    void secureConfigurationPreservesOptionalAuthProperties(String credentials) throws Exception {
        configure(false);
        when(manager.isSecure()).thenReturn(true);
        String json = "{\"connections\":{},\"auth-profiles\":{\"fixture-profile\":{"
            + "\"name\":\"测试配置\",\"save-password\":true,\"credentials\":" + credentials + "}}}";
        when(manager.readConfiguration("fixture.json", null))
            .thenReturn(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        var captured = captureProfiles();
        parse(new DataSourceParseResults());
        assertEquals(1, captured.get().size());
        var profile = captured.get().getFirst();
        assertEquals(credentials.contains("user") ? "测试用户" : null, profile.getUserName());
        assertEquals(credentials.contains("password") ? "fixture-password" : null, profile.getUserPassword());
        assertEquals(credentials.contains("tenant") ? Map.of("tenant", "中文租户") : Map.of(), profile.getProperties());
        profile.getProperties().put("added", "local");
        assertEquals("local", profile.getProperties().get("added"));
    }

    @Test
    void failedConfigurationCanBeRetriedWithReadableContent() throws Exception {
        configure(false);
        when(manager.readConfiguration("fixture.json", null)).thenReturn(
            new FixtureStream(new byte[0], true, false, false),
            new ByteArrayInputStream(fixtureConfiguration()));
        var results = new DataSourceParseResults();
        assertThrows(DBException.class, () -> parse(results));
        assertEmptyResults(results);
        parse(results);
        assertEquals(1, results.addedFolders.size());
        assertEquals("fixture-folder", results.addedFolders.iterator().next().getName());
    }

    private byte[] fixtureConfiguration() {
        return ("{\"folders\":{\"fixture-folder\":{}},\"connections\":{},"
            + "\"auth-profiles\":{\"fixture-profile\":{\"name\":\"测试配置\",\"save-password\":true}}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private AtomicReference<List<DBAAuthProfile>> captureProfiles() {
        var captured = new AtomicReference<List<DBAAuthProfile>>();
        doAnswer(call -> {
            captured.set(call.getArgument(0));
            return null;
        }).when(registry).setAuthProfiles(anyList());
        return captured;
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "empty", "user-only"})
    void repeatedLoadDoesNotRestoreRemovedCredentials(String replacement) throws Exception {
        var captured = prepareRepeatedCredentialLoad();
        parse(new DataSourceParseResults());
        assertEquals("synthetic-old-password", captured.get().getFirst().getUserPassword());
        InputStream next = switch (replacement) {
            case "missing" -> null;
            case "empty" -> encryptedJson("{}");
            default -> encryptedJson("{\"profile:fixture-profile\":{\"#connection\":{\"user\":\"replacement-user\"}}}");
        };
        when(manager.readConfiguration(credentialsName(), null)).thenReturn(next);
        parse(new DataSourceParseResults());
        assertNull(captured.get().getFirst().getUserPassword());
        assertEquals("user-only".equals(replacement) ? "replacement-user" : null,
            captured.get().getFirst().getUserName());
    }

    @Test
    void failedCredentialReloadLeavesAppliedProfileUntouchedAndCanRetry() throws Exception {
        var captured = prepareRepeatedCredentialLoad();
        parse(new DataSourceParseResults());
        List<DBAAuthProfile> original = captured.get();
        when(manager.readConfiguration(credentialsName(), null)).thenReturn(new ByteArrayInputStream(new byte[] {1}));
        var failed = new DataSourceParseResults();
        assertThrows(DBException.class, () -> parse(failed));
        assertEmptyResults(failed);
        assertSame(original, captured.get());
        assertEquals("synthetic-old-password", original.getFirst().getUserPassword());
        InputStream cleared = encryptedJson("{}");
        when(manager.readConfiguration(credentialsName(), null)).thenReturn(cleared);
        parse(new DataSourceParseResults());
        assertNull(captured.get().getFirst().getUserPassword());
    }

    private AtomicReference<List<DBAAuthProfile>> prepareRepeatedCredentialLoad() throws Exception {
        configure(false);
        when(storage.getStorageSubId()).thenReturn("");
        when(manager.readConfiguration("fixture.json", null))
            .thenAnswer(call -> new ByteArrayInputStream(fixtureConfiguration()));
        InputStream initial = encryptedJson(
            "{\"profile:fixture-profile\":{\"#connection\":{\"user\":\"initial-user\",\"password\":\"synthetic-old-password\"}}}");
        when(manager.readConfiguration(credentialsName(), null)).thenReturn(initial);
        return captureProfiles();
    }

    private InputStream encryptedJson(String json) throws Exception {
        return new ByteArrayInputStream(encryptor.encryptValue(json.getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @CsvSource({"false, true", "true, true", "false, false", "true, false"})
    void networkProfileIdentityAndDisplayMetadataSurviveLoading(boolean encryptedProject, boolean named) throws Exception {
        configure(encryptedProject);
        var profiles = mock(DBWNetworkProfileManager.class);
        when(registry.getNetworkProfiles()).thenReturn(profiles);
        String json = "{\"connections\":{},\"network-profiles\":{\"fixture-id\":{" + (named
            ? "\"name\":\"中文隧道\",\"description\":\"测试网络配置\"," : "")
            + "\"properties\":{\"label\":\"中文值\"},\"handlers\":{}}}}";
        byte[] contents = json.getBytes(StandardCharsets.UTF_8);
        byte[] storedContents = encryptedProject ? encryptor.encryptValue(contents) : contents;
        when(manager.readConfiguration("fixture.json", null)).thenReturn(new ByteArrayInputStream(storedContents));
        var results = new DataSourceParseResults();
        parse(results);
        var profileCaptor = org.mockito.ArgumentCaptor.forClass(DBWNetworkProfile.class);
        verify(profiles).addOrUpdateProfile(profileCaptor.capture());
        var loaded = profileCaptor.getValue();
        assertEquals("fixture-id", loaded.getProfileId());
        assertEquals(named ? "中文隧道" : "fixture-id", loaded.getProfileName());
        assertEquals(named ? "测试网络配置" : null, loaded.getProfileDescription());
        assertEquals(Map.of("label", "中文值"), loaded.getProperties());
        assertTrue(loaded.getConfigurations().isEmpty());
        assertTrue(results.updatedProfiles.contains(loaded));
        loaded.setProfileName("重命名后的显示名");
        assertEquals("fixture-id", loaded.getProfileId());
    }

    @ParameterizedTest
    @CsvSource({"false, replacement", "true, replacement", "false, absent", "true, absent",
        "false, invalid", "true, invalid"})
    void encryptedCredentialPropertiesReloadWithoutAliasingOrStaleValues(boolean encryptedProject, String nextMode)
        throws Exception {
        configure(encryptedProject);
        when(storage.getStorageSubId()).thenReturn("");
        byte[] config = fixtureConfiguration();
        byte[] storedConfig = encryptedProject ? encryptor.encryptValue(config) : config;
        when(manager.readConfiguration("fixture.json", null))
            .thenAnswer(call -> new ByteArrayInputStream(storedConfig));
        InputStream initial = encryptedJson("{\"profile:fixture-profile\":{\"#connection\":{"
            + "\"user\":\"测试用户\",\"password\":\"fixture-password\",\"tenant\":\"旧租户\",\"token\":\"fixture-token\"}}}");
        when(manager.readConfiguration(credentialsName(), null)).thenReturn(initial);
        var captured = captureProfiles();
        parse(new DataSourceParseResults());
        var original = captured.get().getFirst();
        assertEquals(Map.of("tenant", "旧租户", "token", "fixture-token"), original.getProperties());
        original.getProperties().put("local-only", "本地修改");

        if ("invalid".equals(nextMode)) {
            when(manager.readConfiguration(credentialsName(), null)).thenReturn(new ByteArrayInputStream(new byte[] {1}));
            var failed = new DataSourceParseResults();
            assertThrows(DBException.class, () -> parse(failed));
            assertEmptyResults(failed);
            assertSame(original, captured.get().getFirst());
            assertEquals("fixture-password", original.getUserPassword());
        }
        InputStream next = "absent".equals(nextMode) ? null : encryptedJson(
            "{\"profile:fixture-profile\":{\"#connection\":{\"user\":\"新用户\",\"tenant\":\"新租户\"}}}");
        when(manager.readConfiguration(credentialsName(), null)).thenReturn(next);
        parse(new DataSourceParseResults());
        var reloaded = captured.get().getFirst();
        assertNotSame(original, reloaded);
        assertEquals("absent".equals(nextMode) ? Map.of() : Map.of("tenant", "新租户"), reloaded.getProperties());
        assertEquals("absent".equals(nextMode) ? null : "新用户", reloaded.getUserName());
        assertNull(reloaded.getUserPassword());
        reloaded.getProperties().put("new-only", "新配置修改");
        assertEquals(Map.of("tenant", "旧租户", "token", "fixture-token", "local-only", "本地修改"),
            original.getProperties());
        assertEquals("fixture-password", original.getUserPassword());
    }

    private String credentialsName() {
        return DBPDataSourceRegistry.CREDENTIALS_CONFIG_FILE_PREFIX + DBPDataSourceRegistry.CREDENTIALS_CONFIG_FILE_EXT;
    }

    @ParameterizedTest
    @CsvSource({"false, io", "true, io", "false, db", "true, db"})
    void credentialWriteFailureMustPropagateAndAllowExplicitRetry(boolean removeCredentials, String failureKind)
        throws Exception {
        configure(false);
        when(storage.isDefault()).thenReturn(true);
        when(storage.getStorageSubId()).thenReturn("");
        when(manager.isTrusted()).thenReturn(true);
        when(registry.getNetworkProfiles()).thenReturn(mock(DBWNetworkProfileManager.class));
        var profile = new DBAAuthProfile(registry.getProject());
        profile.setProfileId("write-fixture");
        profile.setProfileName("写入测试");
        profile.setSavePassword(true);
        profile.setUserName("fixture-user");
        profile.setUserPassword("fixture-password");
        when(registry.getAllAuthProfiles()).thenReturn(removeCredentials ? List.of() : List.of(profile));
        Exception failure = "io".equals(failureKind) ? new IOException("synthetic write failure")
            : new DBException("synthetic configuration store failure");
        var reject = new java.util.concurrent.atomic.AtomicBoolean(true);
        var written = new HashMap<String, byte[]>();
        doAnswer(call -> {
            String name = call.getArgument(0);
            if (name.equals(credentialsName()) && reject.getAndSet(false)) {
                throw failure;
            }
            byte[] bytes = call.getArgument(1);
            written.put(name, bytes == null ? null : bytes.clone());
            return null;
        }).when(manager).writeConfiguration(anyString(), nullable(byte[].class));
        var constructor = DataSourceSerializerModern.class.getDeclaredConstructor(DataSourceRegistry.class);
        constructor.setAccessible(true);
        var saver = (DataSourceSerializerModern<?>) constructor.newInstance(registry);
        assertSame(failure, assertThrows(Exception.class, () -> saver.saveDataSources(
            new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(), manager, storage, List.of())));
        assertTrue(written.containsKey("fixture.json"), "This path does not promise atomicity across both files");
        assertFalse(written.containsKey(credentialsName()));
        assertEquals("fixture-password", profile.getUserPassword());
        saver.saveDataSources(new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(), manager, storage, List.of());
        assertTrue(written.containsKey(credentialsName()));
        if (removeCredentials) {
            assertNull(written.get(credentialsName()));
        } else {
            String plaintext = new String(encryptor.decryptValue(written.get(credentialsName())), StandardCharsets.UTF_8);
            assertTrue(plaintext.contains("fixture-password"));
            assertTrue(plaintext.contains("profile:write-fixture"));
        }
        verify(manager, times(2)).writeConfiguration(eq(credentialsName()), nullable(byte[].class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rename", "delete", "no-password"})
    void freshConfigurationSaveRemovesObsoleteNetworkCredentialPayload(String operation) throws Exception {
        configure(false);
        when(storage.isDefault()).thenReturn(true);
        when(storage.getStorageSubId()).thenReturn("");
        when(manager.isTrusted()).thenReturn(true);
        var profiles = mock(DBWNetworkProfileManager.class);
        when(registry.getNetworkProfiles()).thenReturn(profiles);
        var profile = new DBWNetworkProfile(registry.getProject());
        profile.setProfileId("stable-profile");
        profile.setProfileName("旧配置名称");
        var descriptor = NetworkHandlerRegistry.getInstance().getDescriptor("ssh_tunnel");
        assertNotNull(descriptor);
        var handler = new DBWHandlerConfiguration(descriptor, null);
        handler.setEnabled(true);
        handler.setSavePassword(true);
        handler.setUserName("fixture-user");
        handler.setPassword("fixture-old-password");
        profile.updateConfiguration(handler);
        when(profiles.getProfiles()).thenReturn(List.of(profile));
        var writes = new HashMap<String, byte[]>();
        doAnswer(call -> {
            byte[] bytes = call.getArgument(1);
            writes.put(call.getArgument(0), bytes == null ? null : bytes.clone());
            return null;
        }).when(manager).writeConfiguration(anyString(), nullable(byte[].class));
        var constructor = DataSourceSerializerModern.class.getDeclaredConstructor(DataSourceRegistry.class);
        constructor.setAccessible(true);
        var first = (DataSourceSerializerModern<?>) constructor.newInstance(registry);
        first.saveDataSources(new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(), manager, storage, List.of());
        byte[] initialBytes = writes.get(credentialsName());
        assertNotNull(initialBytes);
        String initial = new String(encryptor.decryptValue(initialBytes), StandardCharsets.UTF_8);
        assertTrue(initial.contains("fixture-old-password"));
        assertTrue(initial.contains("network/ssh_tunnel/profile/旧配置名称"));
        assertFalse(new String(writes.get("fixture.json"), StandardCharsets.UTF_8).contains("fixture-old-password"));
        switch (operation) {
            case "rename" -> profile.setProfileName("新配置名称");
            case "delete" -> when(profiles.getProfiles()).thenReturn(List.of());
            default -> handler.setSavePassword(false);
        }
        writes.clear();
        var second = (DataSourceSerializerModern<?>) constructor.newInstance(registry);
        second.saveDataSources(new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(), manager, storage, List.of());
        assertTrue(writes.containsKey(credentialsName()));
        byte[] replacement = writes.get(credentialsName());
        if ("delete".equals(operation)) {
            assertNull(replacement, "An empty credential set must remove the old file");
        } else {
            assertNotNull(replacement);
            String decoded = new String(encryptor.decryptValue(replacement), StandardCharsets.UTF_8);
            assertEquals("rename".equals(operation), decoded.contains("fixture-old-password"));
            assertEquals("rename".equals(operation), decoded.contains("network/ssh_tunnel/profile/新配置名称"));
            assertEquals(!"rename".equals(operation), decoded.contains("network/ssh_tunnel/profile/旧配置名称"));
            assertTrue(decoded.contains("fixture-user"));
        }
        assertEquals("fixture-old-password", handler.getPassword(), "Saving must not clear the in-memory password");
        assertEquals(initial, new String(encryptor.decryptValue(initialBytes), StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @CsvSource({"ssh_tunnel, false", "ssh_tunnel, true", "postgre_ssl, false", "postgre_ssl, true"})
    void untrustedNetworkExportContainsOnlyHandlerReferenceAndDoesNotModifySource(String handlerId, boolean secure)
        throws Exception {
        var descriptor = NetworkHandlerRegistry.getInstance().getDescriptor(handlerId);
        assertNotNull(descriptor);
        var profile = new DBWNetworkProfile(mock(DBPProject.class));
        profile.setProfileId("export-id");
        profile.setProfileName("共享网络配置");
        var handler = new DBWHandlerConfiguration(descriptor, null);
        handler.setEnabled(true);
        handler.setSavePassword(true);
        handler.setUserName("fixture-private-user");
        handler.setPassword("fixture-private-password");
        handler.setSecureProperty("fixture-key", "fixture-private-key");
        handler.setProperty("host", "private.example.invalid");
        profile.updateConfiguration(handler);
        var configurationManager = mock(DataSourceConfigurationManager.class);
        when(configurationManager.isTrusted()).thenReturn(false);
        when(configurationManager.isSecure()).thenReturn(secure);
        var parameters = new DataSourceParser.ContextParameters(profile.getProject(), configurationManager, new HashMap<>());
        var writerMethod = DataSourceParser.class.getDeclaredMethod("saveNetworkProfiles",
            DataSourceParser.ContextParameters.class, JsonWriter.class, List.class);
        writerMethod.setAccessible(true);
        var output = new StringWriter();
        try (var writer = new JsonWriter(output)) {
            writer.beginObject();
            writerMethod.invoke(null, parameters, writer, List.of(profile));
            writer.endObject();
        }
        String json = output.toString();
        for (String forbidden : List.of("fixture-private-user", "fixture-private-password", "fixture-private-key",
            "private.example.invalid", "credentials", "save-password")) {
            assertFalse(json.contains(forbidden), "Untrusted export contains a restricted handler field");
        }
        assertTrue(parameters.secureProperties().isEmpty());
        var loaded = DataSourceParser.parseProfiles(parameters, JSONUtils.parseMap(new Gson(), new StringReader(json)));
        assertEquals(1, loaded.size());
        var restored = loaded.getFirst().getConfiguration(handlerId);
        assertNotNull(restored);
        assertTrue(restored.isEnabled());
        assertFalse(restored.isSavePassword());
        assertNull(restored.getUserName());
        assertNull(restored.getPassword());
        assertTrue(restored.getSecureProperties().isEmpty());
        assertTrue(restored.getProperties().isEmpty());
        assertSame(handler, profile.getConfiguration(handlerId));
        assertEquals("fixture-private-user", handler.getUserName());
        assertEquals("fixture-private-password", handler.getPassword());
        assertEquals("fixture-private-key", handler.getSecureProperty("fixture-key"));
        assertEquals("private.example.invalid", handler.getStringProperty("host"));
        assertTrue(handler.isSavePassword());
    }

    @ParameterizedTest
    @CsvSource({"ssh_tunnel, false, false", "ssh_tunnel, false, true", "ssh_tunnel, true, false",
        "ssh_tunnel, true, true", "postgre_ssl, false, false", "postgre_ssl, false, true",
        "postgre_ssl, true, false", "postgre_ssl, true, true"})
    void networkHandlerCredentialsRemainAssociatedAfterProfileRename(String handlerId, boolean secure, boolean savePassword)
        throws Exception {
        var descriptor = NetworkHandlerRegistry.getInstance().getDescriptor(handlerId);
        assertNotNull(descriptor, "Required network handler must be installed in the test runtime");
        var project = mock(DBPProject.class);
        var profile = new DBWNetworkProfile(project);
        profile.setProfileId("stable-network-id");
        profile.setProfileName("中文网络名");
        var handler = new DBWHandlerConfiguration(descriptor, null);
        handler.setEnabled(true);
        handler.setSavePassword(savePassword);
        handler.setUserName("网络用户");
        handler.setPassword("fixture-network-password");
        handler.setSecureProperty("fixture-key", "fixture-private-key");
        handler.setProperty("host", "example.invalid");
        profile.updateConfiguration(handler);
        var configurationManager = mock(DataSourceConfigurationManager.class);
        when(configurationManager.isTrusted()).thenReturn(true);
        when(configurationManager.isSecure()).thenReturn(secure);
        var writerMethod = DataSourceParser.class.getDeclaredMethod("saveNetworkProfiles",
            DataSourceParser.ContextParameters.class, JsonWriter.class, List.class);
        writerMethod.setAccessible(true);
        for (int round = 0; round < 2; round++) {
            var parameters = new DataSourceParser.ContextParameters(project, configurationManager, new HashMap<>());
            var output = new StringWriter();
            try (var writer = new JsonWriter(output)) {
                writer.beginObject();
                writerMethod.invoke(null, parameters, writer, List.of(profile));
                writer.endObject();
            }
            String json = output.toString();
            assertEquals(secure && savePassword, json.contains("fixture-network-password"));
            assertEquals(secure, json.contains("fixture-private-key"));
            if (secure) {
                assertTrue(parameters.secureProperties().isEmpty());
            } else {
                var secrets = parameters.secureProperties().get("profile:stable-network-id");
                assertNotNull(secrets);
                assertEquals(1, secrets.size());
                assertTrue(secrets.containsKey("network/" + handlerId + "/profile/" + profile.getProfileName()));
                assertEquals(savePassword, secrets.values().iterator().next().containsKey("password"));
            }
            var loaded = DataSourceParser.parseProfiles(parameters, JSONUtils.parseMap(new Gson(), new StringReader(json)));
            assertEquals(1, loaded.size());
            var restored = loaded.getFirst();
            assertEquals("stable-network-id", restored.getProfileId());
            assertEquals(profile.getProfileName(), restored.getProfileName());
            var restoredHandler = restored.getConfiguration(handlerId);
            assertNotNull(restoredHandler);
            assertTrue(restoredHandler.isEnabled());
            assertEquals(savePassword, restoredHandler.isSavePassword());
            assertEquals("网络用户", restoredHandler.getUserName());
            assertEquals(savePassword ? "fixture-network-password" : null, restoredHandler.getPassword());
            assertEquals("fixture-private-key", restoredHandler.getSecureProperty("fixture-key"));
            assertEquals("example.invalid", restoredHandler.getStringProperty("host"));
            profile = restored;
            profile.setProfileName("改名后的网络配置");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void networkProfileWriterParserAndNameLookupRoundTrip(boolean withProperties) throws Exception {
        var profile = new DBWNetworkProfile(mock(DBPProject.class));
        profile.setProfileId("stable-id");
        profile.setProfileName("中文隧道𠀀");
        profile.setProfileDescription("第一行\n第二行");
        Map<String, String> properties = withProperties ? Map.of("label", "值\"\\", "empty", "") : Map.of();
        profile.setProperties(properties);
        var parameters = new DataSourceParser.ContextParameters(profile.getProject(), null, new HashMap<>());
        var writerMethod = DataSourceParser.class.getDeclaredMethod("saveNetworkProfiles",
            DataSourceParser.ContextParameters.class, JsonWriter.class, List.class);
        writerMethod.setAccessible(true);
        for (int round = 0; round < 2; round++) {
            var output = new StringWriter();
            try (var writer = new JsonWriter(output)) {
                writer.beginObject();
                writerMethod.invoke(null, parameters, writer, List.of(profile));
                writer.endObject();
            }
            var json = JSONUtils.parseMap(new Gson(), new StringReader(output.toString()));
            var loaded = DataSourceParser.parseProfiles(parameters, json);
            assertEquals(1, loaded.size());
            var restored = loaded.getFirst();
            var manager = mock(DBWNetworkProfileManager.class, withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
            manager.addOrUpdateProfile(restored);
            assertAll(
                () -> assertEquals("stable-id", restored.getProfileId()),
                () -> assertEquals("中文隧道𠀀", restored.getProfileName()),
                () -> assertEquals("第一行\n第二行", restored.getProfileDescription()),
                () -> assertEquals(properties, restored.getProperties()),
                () -> assertSame(restored, manager.getProfile(null, "中文隧道𠀀"))
            );
            profile = restored;
        }
    }

    private void parse(DataSourceParseResults results) throws Exception {
        var secrets = DataSourceSerializerModern.class.getDeclaredField("secureProperties");
        secrets.setAccessible(true);
        if (secrets.get(serializer) == null) {
            secrets.set(serializer, new HashMap<>());
        }
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
