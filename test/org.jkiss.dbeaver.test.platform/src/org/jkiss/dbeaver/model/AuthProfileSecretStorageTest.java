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

import com.google.gson.JsonParser;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.access.DBAAuthProfile;
import org.jkiss.dbeaver.model.secret.DBSSecretController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthProfileSecretStorageTest {
    private static final String KEY = "fixture/auth-profile";

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"user\":null,\"password\":null,\"properties\":null}"})
    void explicitEmptySecretObjectStillClearsCredentials(String json) throws Exception {
        var controller = mock(DBSSecretController.class);
        when(controller.getPrivateSecretValue(KEY)).thenReturn(json);
        var source = profile(true);
        source.resolveSecrets(controller);
        assertNull(source.getUserName());
        assertNull(source.getUserPassword());
        assertTrue(source.getProperties().isEmpty());
        assertTrue(source.isSavePassword());
        verify(controller).getPrivateSecretValue(KEY);
        verifyNoMoreInteractions(controller);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "null", "[]", "{\"user\":[\"synthetic-sensitive-marker\"]}",
        "{\"password\":{\"token\":\"synthetic-sensitive-marker\"}}",
        "{\"user\":\"changed\",\"properties\":[\"synthetic-sensitive-marker\"]}",
        "{\"properties\":{\"token\":{\"nested\":\"synthetic-sensitive-marker\"}}}",
        "{\"password\":\"synthetic-sensitive-marker\""
    })
    void corruptedSecretPreservesRuntimeStateAndAllowsCorrectedRetry(String json) throws Exception {
        var controller = mock(DBSSecretController.class);
        when(controller.getPrivateSecretValue(KEY)).thenReturn(json,
            "{\"user\":\"recovered-user\",\"password\":\"synthetic-recovered\",\"properties\":{\"realm\":\"recovered\"}}");
        var source = profile(true);
        var failure = assertThrows(DBException.class, () -> source.resolveSecrets(controller));
        var errors = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(errors));
        assertFalse(errors.toString().contains("synthetic-sensitive-marker"), "Malformed payload must not leak into diagnostics");
        assertEquals("中文用户", source.getUserName());
        assertEquals("synthetic-profile-secret", source.getUserPassword());
        assertEquals(Map.of("realm", "fixture-domain"), source.getProperties());
        source.resolveSecrets(controller);
        assertEquals("recovered-user", source.getUserName());
        assertEquals("synthetic-recovered", source.getUserPassword());
        assertEquals(Map.of("realm", "recovered"), source.getProperties());
        verify(controller, times(2)).getPrivateSecretValue(KEY);
        verifyNoMoreInteractions(controller);
    }

    private DBAAuthProfile profile(boolean save) {
        var profile = spy(new DBAAuthProfile());
        doReturn(KEY).when(profile).getSecretKeyId();
        profile.setUserName("中文用户");
        profile.setUserPassword("synthetic-profile-secret");
        profile.setSavePassword(save);
        profile.setProperties(Map.of("realm", "fixture-domain"));
        return profile;
    }

    private DBSSecretController storage(Map<String, String> values) throws Exception {
        var controller = mock(DBSSecretController.class);
        doAnswer(call -> {
            values.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(controller).setPrivateSecretValue(anyString(), nullable(String.class));
        when(controller.getPrivateSecretValue(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
        return controller;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savedPayloadAndRestoredProfileHonorPasswordOption(boolean save) throws Exception {
        var values = new HashMap<String, String>();
        var controller = storage(values);
        var source = profile(save);
        source.persistSecrets(controller);
        var json = JsonParser.parseString(values.get(KEY)).getAsJsonObject();
        assertEquals("中文用户", json.get("user").getAsString());
        assertEquals(save, json.has("password"));
        assertEquals("synthetic-profile-secret", source.getUserPassword());
        var restored = profile(save);
        restored.setUserPassword(null);
        restored.resolveSecrets(controller);
        assertEquals(save ? "synthetic-profile-secret" : null, restored.getUserPassword());
        assertEquals("中文用户", restored.getUserName());
        assertEquals(Map.of("realm", "fixture-domain"), restored.getProperties());
        var order = inOrder(controller);
        order.verify(controller).setPrivateSecretValue(eq(KEY), anyString());
        order.verify(controller).flushChanges();
        order.verify(controller).getPrivateSecretValue(KEY);
    }

    @Test
    void disablingPasswordPersistenceReplacesPreviouslyStoredPassword() throws Exception {
        var values = new HashMap<String, String>();
        var controller = storage(values);
        var source = profile(true);
        source.persistSecrets(controller);
        assertTrue(JsonParser.parseString(values.get(KEY)).getAsJsonObject().has("password"));
        source.setSavePassword(false);
        source.persistSecrets(controller);
        assertFalse(JsonParser.parseString(values.get(KEY)).getAsJsonObject().has("password"));
        assertEquals(1, values.size());
        assertEquals("synthetic-profile-secret", source.getUserPassword());
        verify(controller, times(2)).flushChanges();
    }

    @Test
    void writeFailurePropagatesWithoutFlushingOrDestroyingRuntimePassword() throws Exception {
        var controller = mock(DBSSecretController.class);
        var failure = new DBException("synthetic storage failure");
        doThrow(failure).when(controller).setPrivateSecretValue(eq(KEY), anyString());
        var source = profile(true);
        assertSame(failure, assertThrows(DBException.class, () -> source.persistSecrets(controller)));
        verify(controller, never()).flushChanges();
        assertEquals("synthetic-profile-secret", source.getUserPassword());
    }

    @Test
    void flushFailurePropagatesAndDoesNotSilentlyRetry() throws Exception {
        var controller = mock(DBSSecretController.class);
        var failure = new DBException("synthetic flush failure");
        doThrow(failure).when(controller).flushChanges();
        assertSame(failure, assertThrows(DBException.class, () -> profile(true).persistSecrets(controller)));
        verify(controller).setPrivateSecretValue(eq(KEY), anyString());
        verify(controller).flushChanges();
        verifyNoMoreInteractions(controller);
    }

    @Test
    void readFailureLeavesRuntimeCredentialsUnchanged() throws Exception {
        var controller = mock(DBSSecretController.class);
        var failure = new DBException("synthetic read failure");
        when(controller.getPrivateSecretValue(KEY)).thenThrow(failure);
        var source = profile(true);
        assertSame(failure, assertThrows(DBException.class, () -> source.resolveSecrets(controller)));
        assertEquals("中文用户", source.getUserName());
        assertEquals("synthetic-profile-secret", source.getUserPassword());
        assertEquals(Map.of("realm", "fixture-domain"), source.getProperties());
    }

    @Test
    void missingSecretDoesNotEraseExistingRuntimeCredentials() throws Exception {
        var controller = mock(DBSSecretController.class);
        var source = profile(true);
        source.resolveSecrets(controller);
        assertEquals("synthetic-profile-secret", source.getUserPassword());
        verify(controller).getPrivateSecretValue(KEY);
        verifyNoMoreInteractions(controller);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nestedExtensionCredentialsRemainSeparateFromPrimaryFields(boolean save) throws Exception {
        var values = new HashMap<String, String>();
        var controller = storage(values);
        var source = profile(save);
        var extensions = Map.of("user", "extension-user", "password", "synthetic-extension-secret",
            "token", "虚构'引号\"换行\n反斜杠\\");
        source.setProperties(extensions);
        source.persistSecrets(controller);
        var json = JsonParser.parseString(values.get(KEY)).getAsJsonObject();
        assertEquals("中文用户", json.get("user").getAsString());
        assertEquals(save, json.has("password"));
        assertEquals("synthetic-extension-secret", json.getAsJsonObject("properties").get("password").getAsString());
        var restored = profile(save);
        restored.resolveSecrets(controller);
        assertEquals("中文用户", restored.getUserName());
        assertEquals(save ? "synthetic-profile-secret" : null, restored.getUserPassword());
        assertEquals(extensions, restored.getProperties());
        assertEquals(extensions, source.getProperties());
    }

    @Test
    void updatingOneProfileSecretDoesNotReplaceAnotherProfileRecord() throws Exception {
        var values = new HashMap<String, String>();
        var controller = storage(values);
        var first = profile(true);
        var second = profile(true);
        doReturn(KEY + "/second").when(second).getSecretKeyId();
        second.setUserName("second-user");
        second.setUserPassword("synthetic-second-secret");
        first.persistSecrets(controller);
        second.persistSecrets(controller);
        String secondPayload = values.get(KEY + "/second");
        first.setSavePassword(false);
        first.persistSecrets(controller);
        assertEquals(2, values.size());
        assertEquals(secondPayload, values.get(KEY + "/second"));
        second.setUserPassword(null);
        second.resolveSecrets(controller);
        assertEquals("second-user", second.getUserName());
        assertEquals("synthetic-second-secret", second.getUserPassword());
        assertFalse(JsonParser.parseString(values.get(KEY)).getAsJsonObject().has("password"));
    }
}
