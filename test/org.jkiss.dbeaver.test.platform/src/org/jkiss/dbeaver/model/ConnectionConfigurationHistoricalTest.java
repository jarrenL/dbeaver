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
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionConfigurationHistoricalTest extends DBeaverUnitTest {
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
