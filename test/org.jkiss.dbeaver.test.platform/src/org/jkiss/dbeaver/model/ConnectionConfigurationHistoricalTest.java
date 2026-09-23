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

import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPConnectionEventType;
import org.jkiss.dbeaver.model.runtime.DBRShellCommand;
import org.jkiss.dbeaver.model.net.DBWHandlerConfiguration;
import org.jkiss.dbeaver.model.net.DBWHandlerDescriptor;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectionConfigurationHistoricalTest extends DBeaverUnitTest {
    private DBWHandlerConfiguration handler(String id) {
        var descriptor = mock(DBWHandlerDescriptor.class);
        when(descriptor.getId()).thenReturn(id);
        var handler = new DBWHandlerConfiguration(descriptor, null);
        handler.setEnabled(true);
        return handler;
    }

    @Test
    void importedNetworkHandlersAreInstalledOnNewConfigurationWithoutListAliasing() {
        var configuration = new DBPConnectionConfiguration();
        var ssl = handler("postgre_ssl");
        var input = new ArrayList<>(List.of(ssl));
        configuration.setHandlers(input);
        assertSame(ssl, configuration.getHandler("postgre_ssl"));
        input.clear();
        assertEquals(1, configuration.getHandlers().size());
        assertTrue(configuration.getHandler("postgre_ssl").isEnabled());
    }

    @Test
    void replacingNetworkHandlersAcceptsOwnListAndCanClearIt() {
        var configuration = new DBPConnectionConfiguration();
        var ssl = handler("postgre_ssl");
        configuration.updateHandler(ssl);
        configuration.setHandlers(configuration.getHandlers());
        assertSame(ssl, configuration.getHandler("postgre_ssl"));
        var ssh = handler("ssh_tunnel");
        configuration.setHandlers(List.of(ssh));
        assertNull(configuration.getHandler("postgre_ssl"));
        assertSame(ssh, configuration.getHandler("ssh_tunnel"));
        configuration.setHandlers(List.of());
        assertTrue(configuration.getHandlers().isEmpty());
    }

    @Test
    void copiedNetworkHandlerPropertiesAndCredentialsAreIndependent() {
        var original = new DBPConnectionConfiguration();
        var ssl = handler("postgre_ssl");
        ssl.setProperty("sslMode", "verify-full");
        ssl.setSecureProperty("ssl.client.key.value", "synthetic-key-not-a-certificate");
        ssl.setUserName("fixture-user");
        ssl.setPassword("synthetic-password-not-used");
        ssl.setSavePassword(false);
        original.updateHandler(ssl);
        var copy = new DBPConnectionConfiguration(original);
        var copied = copy.getHandler("postgre_ssl");
        assertNotNull(copied);
        assertNotSame(ssl, copied);
        assertTrue(copied.isEnabled());
        assertFalse(copied.isSavePassword());
        assertEquals("verify-full", copied.getStringProperty("sslMode"));
        assertEquals("synthetic-key-not-a-certificate", copied.getSecureProperty("ssl.client.key.value"));
        assertEquals("fixture-user", copied.getUserName());
        assertEquals("synthetic-password-not-used", copied.getPassword());
        copied.setProperty("sslMode", "require");
        copied.setSecureProperty("ssl.client.key.value", null);
        copied.setUserName(null);
        copied.setPassword(null);
        copied.setSavePassword(true);
        copied.setEnabled(false);
        assertEquals("verify-full", ssl.getStringProperty("sslMode"));
        assertEquals("synthetic-key-not-a-certificate", ssl.getSecureProperty("ssl.client.key.value"));
        assertEquals("fixture-user", ssl.getUserName());
        assertEquals("synthetic-password-not-used", ssl.getPassword());
        assertFalse(ssl.isSavePassword());
        assertTrue(ssl.isEnabled());
        copy.removeHandler("postgre_ssl");
        assertNull(copy.getHandler("postgre_ssl"));
        assertSame(ssl, original.getHandler("postgre_ssl"));
    }

    @Test
    void doNotSavePasswordSuppressesTheWholeSecretPayloadButKeepsRuntimeCredentials() {
        var ssl = handler("postgre_ssl");
        ssl.setUserName("fixture-user");
        ssl.setPassword("synthetic-password-not-used");
        ssl.setSecureProperty("ssl.client.key.value", "synthetic-key-not-a-certificate");
        ssl.setSavePassword(false);
        assertTrue(ssl.saveToSecret().isEmpty());
        assertEquals("synthetic-password-not-used", ssl.getPassword());
        ssl.setSavePassword(true);
        var secret = ssl.saveToSecret();
        assertEquals("fixture-user", secret.get("user"));
        assertEquals("synthetic-password-not-used", secret.get("password"));
        assertEquals(Map.of("ssl.client.key.value", "synthetic-key-not-a-certificate"), secret.get("properties"));
        ssl.setSavePassword(false);
        assertTrue(ssl.saveToSecret().isEmpty());
    }

    @Test
    void updatingSameHandlerIdReplacesOnlyThatHandlerAndRemovingMissingIdIsHarmless() {
        var configuration = new DBPConnectionConfiguration();
        var ssl = handler("postgre_ssl");
        var ssh = handler("ssh_tunnel");
        configuration.updateHandler(ssl);
        configuration.updateHandler(ssh);
        var replacement = handler("postgre_ssl");
        replacement.setEnabled(false);
        configuration.updateHandler(replacement);
        assertEquals(2, configuration.getHandlers().size());
        assertSame(replacement, configuration.getHandler("postgre_ssl"));
        assertSame(ssh, configuration.getHandler("ssh_tunnel"));
        configuration.removeHandler("missing");
        assertEquals(2, configuration.getHandlers().size());
        configuration.removeHandler("postgre_ssl");
        assertEquals(List.of(ssh), configuration.getHandlers());
    }

    @Test
    void copiedDriverAndProviderPropertiesDoNotMutateOriginal() {
        var original = new DBPConnectionConfiguration();
        original.setHostName("example.invalid");
        original.setHostPort("8000");
        original.setDatabaseName("中文库");
        original.setProperty("sslmode", "verify-full");
        original.setProviderProperty("show-system-objects", "false");
        var copy = new DBPConnectionConfiguration(original);
        assertEquals("中文库", copy.getDatabaseName());
        assertEquals("8000", copy.getHostPort());
        copy.setHostName("other.invalid");
        copy.setProperty("sslmode", "require");
        copy.setProviderProperty("show-system-objects", "true");
        assertEquals("example.invalid", original.getHostName());
        assertEquals("verify-full", original.getProperty("sslmode"));
        assertEquals("false", original.getProviderProperty("show-system-objects"));
    }

    @Test
    void authenticationPropertiesAreCopiedAndCanBeRemovedWithoutChangingOriginal() {
        var original = new DBPConnectionConfiguration();
        var input = new HashMap<>(Map.of("test-token", "synthetic-fixture"));
        original.setAuthProperties(input);
        input.clear();
        assertEquals("synthetic-fixture", original.getAuthProperty("test-token"));
        var copy = new DBPConnectionConfiguration(original);
        copy.setAuthProperty("test-token", null);
        assertNull(copy.getAuthProperty("test-token"));
        assertEquals("synthetic-fixture", original.getAuthProperty("test-token"));
        copy.setAuthProperties(null);
        assertTrue(copy.getAuthProperties().isEmpty());
    }

    @Test
    void copiedBootstrapQueriesAndSchemaAreIndependent() {
        var original = new DBPConnectionConfiguration();
        var queries = new ArrayList<>(List.of("SET application_name='history fixture'"));
        original.getBootstrap().setInitQueries(queries);
        original.getBootstrap().setDefaultSchemaName("source_schema");
        original.getBootstrap().setDefaultAutoCommit(false);
        queries.clear();
        var copy = new DBPConnectionConfiguration(original);
        assertEquals(List.of("SET application_name='history fixture'"), copy.getBootstrap().getInitQueries());
        assertEquals(Boolean.FALSE, copy.getBootstrap().getDefaultAutoCommit());
        copy.getBootstrap().setInitQueries(List.of("SELECT 1"));
        copy.getBootstrap().setDefaultSchemaName("target_schema");
        assertEquals("source_schema", original.getBootstrap().getDefaultSchemaName());
        assertEquals(List.of("SET application_name='history fixture'"), original.getBootstrap().getInitQueries());
    }

    @Test
    void copiedConnectionEventsAreIndependentAndClearable() {
        var original = new DBPConnectionConfiguration();
        var command = new DBRShellCommand("fixture-command-not-executed");
        command.setEnabled(true);
        original.setEvent(DBPConnectionEventType.BEFORE_CONNECT, command);
        var copy = new DBPConnectionConfiguration(original);
        var copiedCommand = copy.getEvent(DBPConnectionEventType.BEFORE_CONNECT);
        assertNotNull(copiedCommand);
        assertNotSame(command, copiedCommand);
        copiedCommand.setCommand("changed-fixture-not-executed");
        copiedCommand.setEnabled(false);
        assertEquals("fixture-command-not-executed", command.getCommand());
        assertTrue(command.isEnabled());
        copy.clearEvents();
        assertNull(copy.getEvent(DBPConnectionEventType.BEFORE_CONNECT));
        assertSame(command, original.getEvent(DBPConnectionEventType.BEFORE_CONNECT));
    }
}
