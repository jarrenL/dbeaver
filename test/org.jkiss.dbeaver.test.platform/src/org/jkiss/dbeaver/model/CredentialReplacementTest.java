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
import org.jkiss.dbeaver.model.connection.DBPConfigurationProfile;
import org.jkiss.dbeaver.registry.DataSourceDescriptor;
import org.jkiss.dbeaver.registry.DataSourceParser;
import org.jkiss.dbeaver.registry.SecureCredentials;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CredentialReplacementTest {
    private final Map<String, Map<String, Map<String, String>>> records = new HashMap<>();
    private final DataSourceDescriptor source = mock(DataSourceDescriptor.class);
    private final DataSourceParser.ContextParameters context = new DataSourceParser.ContextParameters(null, null, records);

    private SecureCredentials credentials(String password) {
        var credentials = new SecureCredentials();
        credentials.setUserName("fixture-user");
        credentials.setUserPassword(password);
        return credentials;
    }

    private void save(DataSourceParser.ContextParameters targetContext, DBPConfigurationProfile profile,
        String node, SecureCredentials credentials) throws Exception {
        when(source.getId()).thenReturn("fixture-source");
        var method = DataSourceParser.class.getDeclaredMethod("saveSecuredCredentials",
            DataSourceParser.ContextParameters.class, DataSourceDescriptor.class,
            DBPConfigurationProfile.class, String.class, SecureCredentials.class);
        method.setAccessible(true);
        method.invoke(null, targetContext, source, profile, node, credentials);
    }

    @Test
    void replacingConnectionCredentialsRemovesOldPasswordAndAuthProperties() throws Exception {
        var first = credentials("synthetic-old-password");
        first.setSecureProp("token", "synthetic-old-token");
        save(context, null, null, first);
        var replacement = credentials(null);
        replacement.setSecureProp("realm", "new-domain");
        save(context, null, null, replacement);
        assertEquals(Map.of("user", "fixture-user", "realm", "new-domain"),
            records.get("fixture-source").get("#connection"));
    }

    @Test
    void clearingOneNodePreservesSiblingAndUnrelatedConnection() throws Exception {
        save(context, null, null, credentials("synthetic-db-password"));
        save(context, null, "ssh", credentials("synthetic-tunnel-password"));
        records.put("unrelated", new HashMap<>(Map.of("#connection", Map.of("user", "other-user"))));
        save(context, null, null, new SecureCredentials());
        assertFalse(records.get("fixture-source").containsKey("#connection"));
        assertEquals("synthetic-tunnel-password", records.get("fixture-source").get("ssh").get("password"));
        assertEquals(Map.of("#connection", Map.of("user", "other-user")), records.get("unrelated"));
    }

    @Test
    void clearingFinalNodePrunesEmptyTopLevelRecordAndIsRepeatable() throws Exception {
        save(context, null, null, credentials("synthetic-old-password"));
        save(context, null, null, new SecureCredentials());
        assertTrue(records.isEmpty());
        save(context, null, null, new SecureCredentials());
        assertTrue(records.isEmpty());
    }

    @Test
    void profileReplacementUsesProfileKeyAndDoesNotMergeOldSecrets() throws Exception {
        var profile = mock(DBPConfigurationProfile.class);
        when(profile.getProfileId()).thenReturn("fixture-profile");
        save(context, profile, null, credentials("synthetic-profile-password"));
        save(context, profile, null, credentials(null));
        assertEquals(Map.of("profile:fixture-profile", Map.of("#connection", Map.of("user", "fixture-user"))), records);
    }

    @Test
    void secretStorageProjectsDoNotWriteLegacyRecords() throws Exception {
        var project = mock(DBPProject.class);
        when(project.isUseSecretStorage()).thenReturn(true);
        var secretContext = new DataSourceParser.ContextParameters(project, null, records);
        records.put("unrelated", new HashMap<>(Map.of("#connection", Map.of("user", "other-user"))));
        save(secretContext, null, null, credentials("synthetic-password"));
        assertEquals(Map.of("unrelated", Map.of("#connection", Map.of("user", "other-user"))), records);
    }
}
