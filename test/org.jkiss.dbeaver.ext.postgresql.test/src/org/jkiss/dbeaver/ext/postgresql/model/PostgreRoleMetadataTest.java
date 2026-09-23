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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreRoleMetadataTest {
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
