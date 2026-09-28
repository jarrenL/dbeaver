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
import org.jkiss.dbeaver.model.net.DBWHandlerConfiguration;
import org.jkiss.dbeaver.model.net.DBWHandlerDescriptor;
import org.jkiss.dbeaver.model.net.DBWNetworkProfile;
import org.jkiss.dbeaver.model.secret.DBSSecretController;
import org.jkiss.dbeaver.model.secret.DBSSecretSubject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkProfileContractsTest {
    @ParameterizedTest
    @ValueSource(strings = {"none", "user", "password", "property", "list-second"})
    void legacyMigrationReadsWholeProfileBeforeUpdating(String failureAt) throws Exception {
        var project = mock(org.jkiss.dbeaver.model.app.DBPProject.class);
        when(project.getId()).thenReturn("fixture-project");
        var profile = new DBWNetworkProfile(project);
        profile.setProfileId("fixture");
        var ssh = handler("ssh");
        var ssl = handler("ssl");
        profile.updateConfiguration(ssh);
        profile.updateConfiguration(ssl);
        var controller = mock(DBSSecretController.class,
            withSettings().extraInterfaces(org.jkiss.dbeaver.model.secret.DBSSecretBrowser.class));
        var browser = (org.jkiss.dbeaver.model.secret.DBSSecretBrowser) controller;
        var failure = new DBException("synthetic legacy storage failure");
        String prefix = "projects/fixture-project/network/";
        for (String id : List.of("ssh", "ssl")) {
            when(browser.listSecrets(prefix + id + "/profile/fixture")).thenReturn(List.of(
                new org.jkiss.dbeaver.model.secret.DBSSecret(id + "-user", "user"),
                new org.jkiss.dbeaver.model.secret.DBSSecret(id + "-password", "password"),
                new org.jkiss.dbeaver.model.secret.DBSSecret(id + "-property", "fixture-key"),
                new org.jkiss.dbeaver.model.secret.DBSSecret(id + "-name", "name")));
            for (String key : List.of("user", "password", "property")) {
                when(controller.getPrivateSecretValue(id + "-" + key)).thenReturn("new-" + id + "-" + key);
            }
        }
        if (failureAt.equals("list-second")) {
            when(browser.listSecrets(prefix + "ssl/profile/fixture")).thenThrow(failure).thenReturn(List.of());
        } else if (!failureAt.equals("none")) {
            when(controller.getPrivateSecretValue("ssl-" + failureAt))
                .thenThrow(failure).thenReturn("new-ssl-" + failureAt);
        }
        {
            if (!failureAt.equals("none")) {
                assertSame(failure, assertThrows(DBException.class, () -> resolveLegacy(profile, controller)));
                for (var cfg : List.of(ssh, ssl)) {
                    assertEquals("中文-" + cfg.getId(), cfg.getUserName());
                    assertEquals("synthetic-" + cfg.getId(), cfg.getPassword());
                    assertEquals("synthetic-key-" + cfg.getId(), cfg.getSecureProperty("fixture-key"));
                }
            }
            resolveLegacy(profile, controller);
        }
        assertSame(ssh, profile.getConfiguration("ssh"));
        assertEquals("new-ssh-user", ssh.getUserName());
        assertEquals("new-ssh-password", ssh.getPassword());
        assertEquals("new-ssh-property", ssh.getSecureProperty("fixture-key"));
        assertEquals(failureAt.equals("list-second") ? "synthetic-ssl" : "new-ssl-password", ssl.getPassword());
        verify(controller, never()).getPrivateSecretValue("ssh-name");
        verify(controller, never()).getPrivateSecretValue("ssl-name");
        verify(controller, never()).flushChanges();
    }

    private void resolveLegacy(DBWNetworkProfile profile, DBSSecretController controller) throws Exception {
        // Exercise legacy loading without replacing the global application workbench.
        var method = DBWNetworkProfile.class.getDeclaredMethod("loadFromLegacySecret", DBSSecretController.class);
        method.setAccessible(true);
        try {
            method.invoke(profile, controller);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"handlers\":null}", "{\"handlers\":[]}"})
    void emptyHandlerRecordsPreserveExistingCredentials(String record) throws Exception {
        var profile = new DBWNetworkProfile();
        profile.setProfileId("fixture");
        var ssh = handler("ssh");
        profile.updateConfiguration(ssh);
        var controller = mock(DBSSecretController.class);
        when(controller.getPrivateSecretValue(profile.getSecretKeyId())).thenReturn(record);
        profile.resolveSecrets(controller);
        assertEquals("synthetic-ssh", ssh.getPassword());
        assertEquals("synthetic-key-ssh", ssh.getSecureProperty("fixture-key"));
        assertEquals("中文-ssh", ssh.getUserName());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "null", "[]", "{\"handlers\":{}}", "{\"handlers\":[null]}",
        "{\"handlers\":[{\"id\":[]}]}",
        "{\"handlers\":[{\"id\":\"ssh\",\"user\":[]}]}",
        "{\"handlers\":[{\"id\":\"ssh\",\"password\":{}}]}",
        "{\"handlers\":[{\"id\":\"ssh\",\"properties\":[]}]}",
        "{\"handlers\":[{\"id\":\"ssh\",\"password\":\"changed\"},{\"id\":\"ssl\",\"properties\":{\"key\":{}}}]}",
        "{\"handlers\":[{\"id\":\"ssh\",\"password\":\"SYNTHETIC_PRIVATE_MARKER"
    })
    void malformedRecordPreservesAllHandlersAndAllowsRetry(String record) throws Exception {
        var profile = new DBWNetworkProfile();
        profile.setProfileId("fixture");
        var ssh = handler("ssh");
        var ssl = handler("ssl");
        profile.updateConfiguration(ssh);
        profile.updateConfiguration(ssl);
        var controller = mock(DBSSecretController.class);
        when(controller.getPrivateSecretValue(profile.getSecretKeyId())).thenReturn(record,
            "{\"handlers\":[{\"id\":\"ssh\",\"user\":\"新用户\",\"password\":\"synthetic-new\"}]}");
        var error = assertThrows(DBException.class, () -> profile.resolveSecrets(controller));
        var trace = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(trace));
        assertFalse(trace.toString().contains("SYNTHETIC_PRIVATE_MARKER"));
        assertEquals("synthetic-ssh", ssh.getPassword());
        assertEquals("中文-ssh", ssh.getUserName());
        assertEquals("synthetic-key-ssh", ssh.getSecureProperty("fixture-key"));
        assertEquals("synthetic-ssl", ssl.getPassword());
        assertEquals("synthetic-key-ssl", ssl.getSecureProperty("fixture-key"));
        profile.resolveSecrets(controller);
        assertEquals("新用户", ssh.getUserName());
        assertEquals("synthetic-new", ssh.getPassword());
        assertEquals("synthetic-ssl", ssl.getPassword());
    }

    private DBWHandlerConfiguration handler(String id) {
        var descriptor = mock(DBWHandlerDescriptor.class);
        when(descriptor.getId()).thenReturn(id);
        var result = new DBWHandlerConfiguration(descriptor, null);
        result.setEnabled(true);
        result.setSavePassword(true);
        result.setUserName("中文-" + id);
        result.setPassword("synthetic-" + id);
        result.setSecureProperty("fixture-key", "synthetic-key-" + id);
        return result;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void secretScopeAndSharedPropertySnapshotRemainIndependent(boolean scoped) {
        var profile = new DBWNetworkProfile();
        profile.setProfileId("fixture-id");
        if (scoped) {
            var subject = mock(DBSSecretSubject.class);
            when(subject.getSecretSubjectId()).thenReturn("fixture-subject");
            profile.setSecretSubject(subject);
        }
        assertEquals((scoped ? "fixture-subject" : "global") + "/network-profile/fixture-id", profile.getSecretKeyId());
        assertEquals(!scoped, profile.isGlobal());
        assertEquals(scoped ? "fixture-subject" : null, profile.getProfileSource());
        var input = new java.util.LinkedHashMap<>(Map.of("label", "原值"));
        profile.setProperties(input);
        input.clear();
        assertEquals("原值", profile.getProperties().get("label"));
        profile.getProperties().put("label", "修改");
        assertTrue(input.isEmpty());
    }

    @Test
    void handlerReplacementPreservesOtherHandlersAndOrder() {
        var profile = new DBWNetworkProfile();
        var ssh = handler("ssh");
        var ssl = handler("ssl");
        profile.updateConfiguration(ssh);
        profile.updateConfiguration(ssl);
        var replacement = handler("ssh");
        replacement.setPassword("synthetic-replacement");
        profile.updateConfiguration(replacement);
        assertEquals(List.of(replacement, ssl), profile.getConfigurations());
        assertSame(replacement, profile.getConfiguration("ssh"));
        assertSame(ssl, profile.getConfiguration("ssl"));
        assertNull(profile.getConfiguration("unknown"));
        assertEquals("synthetic-ssh", ssh.getPassword());
    }

    @Test
    void multiHandlerSecretRoundTripRestoresOnlyConfiguredHandlers() throws Exception {
        var source = new DBWNetworkProfile();
        source.setProfileId("fixture");
        source.updateConfiguration(handler("ssh"));
        source.updateConfiguration(handler("ssl"));
        var controller = mock(DBSSecretController.class);
        source.persistSecrets(controller);
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(controller).setPrivateSecretValue(eq(source.getSecretKeyId()), payload.capture());
        var destination = new DBWNetworkProfile();
        destination.setProfileId("fixture");
        var ssh = handler("ssh");
        ssh.setPassword(null);
        ssh.setSecureProperties(Map.of());
        destination.updateConfiguration(ssh);
        when(controller.getPrivateSecretValue(destination.getSecretKeyId())).thenReturn(payload.getValue());
        destination.resolveSecrets(controller);
        assertEquals("中文-ssh", ssh.getUserName());
        assertEquals("synthetic-ssh", ssh.getPassword());
        assertEquals("synthetic-key-ssh", ssh.getSecureProperty("fixture-key"));
        assertTrue(ssh.isEnabled());
        assertTrue(ssh.isSavePassword());
        assertEquals(1, destination.getConfigurations().size());
        assertNull(destination.getConfiguration("ssl"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "write"})
    void storageFailuresDoNotChangeRuntimeHandlers(String operation) throws Exception {
        var profile = new DBWNetworkProfile();
        profile.setProfileId("fixture");
        var ssh = handler("ssh");
        profile.updateConfiguration(ssh);
        var controller = mock(DBSSecretController.class);
        var failure = new DBException("synthetic storage failure");
        if (operation.equals("read")) {
            when(controller.getPrivateSecretValue(profile.getSecretKeyId())).thenThrow(failure);
            assertSame(failure, assertThrows(DBException.class, () -> profile.resolveSecrets(controller)));
        } else {
            doThrow(failure).when(controller).setPrivateSecretValue(eq(profile.getSecretKeyId()), anyString());
            assertSame(failure, assertThrows(DBException.class, () -> profile.persistSecrets(controller)));
        }
        assertSame(ssh, profile.getConfiguration("ssh"));
        assertEquals("synthetic-ssh", ssh.getPassword());
        assertEquals("synthetic-key-ssh", ssh.getSecureProperty("fixture-key"));
        verify(controller, never()).flushChanges();
    }
}
