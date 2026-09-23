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

import org.jkiss.dbeaver.model.access.DBAAuthProfile;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.net.DBWHandlerConfiguration;
import org.jkiss.dbeaver.registry.SecureCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecureCredentialsHistoricalTest {
    private Map<String, String> serialized(SecureCredentials credentials) throws Exception {
        var parser = Class.forName("org.jkiss.dbeaver.registry.DataSourceParser", true,
            SecureCredentials.class.getClassLoader());
        var method = parser.getDeclaredMethod("saveCredentialsToMap", Map.class, SecureCredentials.class);
        method.setAccessible(true);
        Map<String, String> output = new HashMap<>();
        method.invoke(null, output, credentials);
        return output;
    }

    private DBPDataSourceContainer source(DBPConnectionConfiguration config, boolean save) {
        var source = mock(DBPDataSourceContainer.class);
        when(source.getConnectionConfiguration()).thenReturn(config);
        when(source.isSavePassword()).thenReturn(save);
        return source;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dataSourcePasswordPersistenceHonorsSaveFlag(boolean save) throws Exception {
        var config = new DBPConnectionConfiguration();
        config.setUserName("测试用户");
        config.setUserPassword("synthetic-not-a-real-password");
        var exported = serialized(new SecureCredentials(source(config, save)));
        assertEquals("测试用户", exported.get("user"));
        assertEquals(save, exported.containsKey("password"));
        if (save) {
            assertEquals(config.getUserPassword(), exported.get("password"));
        }
        assertEquals("synthetic-not-a-real-password", config.getUserPassword());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void networkPasswordPersistenceHonorsSaveFlag(boolean save) throws Exception {
        var handler = mock(DBWHandlerConfiguration.class);
        when(handler.getUserName()).thenReturn("tunnel-user");
        when(handler.getPassword()).thenReturn("synthetic-tunnel-password");
        when(handler.isSavePassword()).thenReturn(save);
        var exported = serialized(new SecureCredentials(handler));
        assertEquals("tunnel-user", exported.get("user"));
        assertEquals(save, exported.containsKey("password"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authProfilePasswordPersistenceHonorsSaveFlag(boolean save) throws Exception {
        var profile = mock(DBAAuthProfile.class);
        when(profile.getUserName()).thenReturn("profile-user");
        when(profile.getUserPassword()).thenReturn("synthetic-profile-password");
        when(profile.isSavePassword()).thenReturn(save);
        when(profile.getProperties()).thenReturn(Map.of());
        var exported = serialized(new SecureCredentials(profile));
        assertEquals("profile-user", exported.get("user"));
        assertEquals(save, exported.containsKey("password"));
        assertEquals("synthetic-profile-password", profile.getUserPassword());
    }

    @Test
    void dataSourceAuthPropertiesAreAnIndependentSnapshot() {
        var config = new DBPConnectionConfiguration();
        config.setAuthProperties(new HashMap<>(Map.of("token", "synthetic-original")));
        var snapshot = new SecureCredentials(source(config, true));
        snapshot.setSecureProp("token", "synthetic-export-only");
        assertEquals("synthetic-original", config.getAuthProperties().get("token"));
        config.getAuthProperties().put("later", "source-only");
        assertFalse(snapshot.getProperties().containsKey("later"));
    }

    @Test
    void authProfileReadOnlyPropertiesCanBeSafelyExtendedWithoutMutatingProfile() {
        var profile = mock(DBAAuthProfile.class);
        when(profile.getProperties()).thenReturn(Map.of("token", "synthetic-profile"));
        var snapshot = new SecureCredentials(profile);
        snapshot.setSecureProp("extra", "synthetic-extra");
        assertEquals(Map.of("token", "synthetic-profile"), profile.getProperties());
        assertEquals("synthetic-extra", snapshot.getProperties().get("extra"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacingPropertiesWithOwnMapOrViewDoesNotEraseSecrets(boolean useView) throws Exception {
        var snapshot = new SecureCredentials();
        var input = new HashMap<>(Map.of("token", "synthetic-value"));
        snapshot.setProperties(input);
        input.clear();
        var existing = snapshot.getProperties();
        snapshot.setProperties(useView ? Collections.unmodifiableMap(existing) : existing);
        assertEquals(Map.of("token", "synthetic-value"), serialized(snapshot));
        snapshot.setProperties(Map.of());
        assertTrue(serialized(snapshot).isEmpty());
    }

    @Test
    void emptyCredentialsOmitFieldsAndSupportAddingProperties() throws Exception {
        var snapshot = new SecureCredentials();
        assertTrue(serialized(snapshot).isEmpty());
        snapshot.setUserName("");
        snapshot.setUserPassword("");
        assertTrue(serialized(snapshot).isEmpty());
        snapshot.setSecureProp("token", "synthetic-added");
        assertEquals(Map.of("token", "synthetic-added"), serialized(snapshot));
    }
}
