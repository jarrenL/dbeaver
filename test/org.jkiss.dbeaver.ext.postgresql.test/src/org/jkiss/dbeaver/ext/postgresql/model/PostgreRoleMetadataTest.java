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
package org.jkiss.dbeaver.ext.postgresql.model;

import org.junit.jupiter.api.Test;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreRoleMetadataTest {
    private PostgreRole exportRole(int limit) throws Exception {
        var database = mock(PostgreDatabase.class);
        var source = mock(PostgreDataSource.class);
        when(database.getDataSource()).thenReturn(source);
        when(source.getSQLDialect()).thenReturn(new PostgreDialect());
        when(source.getServerType()).thenReturn(mock(PostgreServerExtension.class));
        var role = new PostgreRole(database, "Role 中文", "not-a-real-password", true);
        role.setInherit(false);
        role.setConnLimit(limit);
        var settings = PostgreRole.class.getDeclaredField("extraSettings");
        settings.setAccessible(true);
        settings.set(role, List.of());
        return role;
    }

    @Test
    void roleDdlPreservesZeroConnectionLimit() throws Exception {
        var ddl = exportRole(0).getObjectDefinitionText(new VoidProgressMonitor(), Map.of());
        assertTrue(ddl.contains("CONNECTION LIMIT 0"), ddl);
        assertFalse(ddl.contains("CONNECTION LIMIT -1"), ddl);
    }

    @Test
    void roleDdlPreservesPositiveAndUnlimitedLimitsWithoutExportingPassword() throws Exception {
        for (int limit : new int[] {7, -1}) {
            var ddl = exportRole(limit).getObjectDefinitionText(new VoidProgressMonitor(), Map.of());
            assertTrue(ddl.contains("CONNECTION LIMIT " + limit), ddl);
            assertTrue(ddl.contains("CREATE ROLE \"Role 中文\" WITH"), ddl);
            assertTrue(ddl.contains("\tLOGIN"), ddl);
            assertTrue(ddl.contains("NOCREATEROLE"), ddl);
            assertFalse(ddl.contains("not-a-real-password"), ddl);
            assertFalse(ddl.contains("PASSWORD"), ddl);
            assertFalse(ddl.contains("SUPERUSER"), ddl);
            assertFalse(ddl.contains("REPLICATION"), ddl);
        }
    }

    @Test
    void roleDdlKeepsSupportedPrivilegeFlagsIndependent() throws Exception {
        String[] options = {"SUPERUSER", "CREATEDB", "CREATEROLE", "LOGIN", "REPLICATION", "BYPASSRLS"};
        for (int mask = 0; mask < 64; mask++) {
            var role = exportRole(7);
            var extension = role.getDataSource().getServerType();
            when(extension.supportsSuperusers()).thenReturn(true);
            when(extension.supportsRolesWithCreateDBAbility()).thenReturn(true);
            when(extension.supportsInheritance()).thenReturn(true);
            when(extension.supportsRoleReplication()).thenReturn(true);
            when(extension.supportsRoleBypassRLS()).thenReturn(true);
            role.setSuperUser((mask & 1) != 0);
            role.setCreateDatabase((mask & 2) != 0);
            role.setCreateRole((mask & 4) != 0);
            role.setCanLogin((mask & 8) != 0);
            role.setReplication((mask & 16) != 0);
            role.setBypassRls((mask & 32) != 0);
            var ddl = role.getObjectDefinitionText(new VoidProgressMonitor(), Map.of());
            var lines = ddl.lines().map(String::trim).toList();
            for (int index = 0; index < options.length; index++) {
                boolean enabled = (mask & (1 << index)) != 0;
                assertTrue(lines.contains((enabled ? "" : "NO") + options[index]), ddl);
                assertFalse(lines.contains((enabled ? "NO" : "") + options[index]), ddl);
            }
            assertTrue(lines.contains("NOINHERIT"), ddl);
        }
    }

    @Test
    void roleDdlQuotesEmbeddedQuotesAndCommentAndPreservesExpiry() throws Exception {
        var role = exportRole(7);
        role.setName("Role \"中文\"");
        role.setDescription("研发's role; --说明");
        role.setValidUntil(LocalDateTime.of(2030, 1, 2, 3, 4, 5));
        var ddl = role.getObjectDefinitionText(new VoidProgressMonitor(), Map.of());
        assertTrue(ddl.contains("CREATE ROLE \"Role \"\"中文\"\"\" WITH"), ddl);
        assertTrue(ddl.contains("VALID UNTIL '2030-01-02T03:04:05'"), ddl);
        assertTrue(ddl.contains("COMMENT ON ROLE \"Role \"\"中文\"\"\" IS '研发''s role; --说明';"), ddl);
    }

    @Test
    void unsupportedPrivilegeOptionsAreOmittedEvenWhenModelFlagsAreTrue() throws Exception {
        var role = exportRole(7);
        role.setSuperUser(true);
        role.setCreateDatabase(true);
        role.setReplication(true);
        role.setBypassRls(true);
        var ddl = role.getObjectDefinitionText(new VoidProgressMonitor(), Map.of());
        for (String option : new String[] {"SUPERUSER", "CREATEDB", "INHERIT", "REPLICATION", "BYPASSRLS"}) {
            assertFalse(ddl.contains(option), ddl);
        }
        assertFalse(ddl.contains("VALID UNTIL"), ddl);
        assertFalse(ddl.contains("COMMENT ON ROLE"), ddl);
    }

    @Test
    void catalogFieldsRemainIndependentAndPreserveUnlimitedConnections() throws Exception {
        var database = mock(PostgreDatabase.class);
        var rows = mock(ResultSet.class);
        when(rows.getLong("oid")).thenReturn(9876543210L);
        when(rows.getString("rolname")).thenReturn("Role 中文");
        when(rows.getBoolean("rolsuper")).thenReturn(true);
        when(rows.getBoolean("rolcreatedb")).thenReturn(true);
        when(rows.getBoolean("rolreplication")).thenReturn(true);
        when(rows.getInt("rolconnlimit")).thenReturn(-1);
        var expiry = LocalDateTime.of(2030, 1, 2, 3, 4, 5);
        when(rows.getTimestamp("rolvaliduntil")).thenReturn(Timestamp.valueOf(expiry));
        when(rows.getString("description")).thenReturn("角色说明");
        var role = new PostgreRole(database, rows);
        assertEquals(9876543210L, role.getObjectId());
        assertEquals("Role 中文", role.getName());
        assertSame(database, role.getDatabase());
        assertTrue(role.isPersisted());
        assertTrue(role.isSuperUser());
        assertTrue(role.isCreateDatabase());
        assertTrue(role.isReplication());
        assertFalse(role.isCreateRole());
        assertFalse(role.isInherit());
        assertFalse(role.isCanLogin());
        assertFalse(role.isUser());
        assertFalse(role.isBypassRls());
        assertEquals(-1, role.getConnLimit());
        assertEquals(expiry, role.getValidUntil());
        assertEquals("角色说明", role.getDescription());
    }

    @Test
    void missingOptionalCatalogFieldsDoNotGrantPrivileges() throws Exception {
        var rows = mock(ResultSet.class);
        when(rows.getBoolean(anyString())).thenThrow(new SQLException("column unavailable"));
        when(rows.getTimestamp("rolvaliduntil")).thenThrow(new SQLException("column unavailable"));
        var role = new PostgreRole(mock(PostgreDatabase.class), rows);
        assertFalse(role.isSuperUser());
        assertFalse(role.isCreateRole());
        assertFalse(role.isCreateDatabase());
        assertFalse(role.isReplication());
        assertFalse(role.isBypassRls());
        assertFalse(role.isCanLogin());
        assertNull(role.getValidUntil());
    }

    @Test
    void newRoleAndUserDifferOnlyByExplicitLoginChoice() {
        var database = mock(PostgreDatabase.class);
        var role = new PostgreRole(database, "group", null, false);
        var user = new PostgreRole(database, "login", null, true);
        assertFalse(role.isPersisted());
        assertFalse(user.isPersisted());
        assertFalse(role.isUser());
        assertTrue(user.isUser());
        assertTrue(user.isCanLogin());
        assertFalse(user.isSuperUser());
        assertFalse(user.isCreateRole());
        assertFalse(user.isCreateDatabase());
    }
}
