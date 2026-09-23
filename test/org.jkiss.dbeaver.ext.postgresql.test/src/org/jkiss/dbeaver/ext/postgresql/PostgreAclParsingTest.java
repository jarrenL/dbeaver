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
package org.jkiss.dbeaver.ext.postgresql;

import org.jkiss.dbeaver.ext.postgresql.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreAclParsingTest {
    private PostgreDatabase database;
    private PostgreTable owner;

    @BeforeEach
    void setup() {
        var source = mock(PostgreDataSource.class);
        when(source.getSQLDialect()).thenReturn(new PostgreDialect());
        database = mock(PostgreDatabase.class);
        when(database.getDataSource()).thenReturn(source);
        when(database.getName()).thenReturn("db");
        var schema = mock(PostgreSchema.class);
        when(schema.getName()).thenReturn("s");
        owner = mock(PostgreTable.class);
        when(owner.getDatabase()).thenReturn(database);
        when(owner.getSchema()).thenReturn(schema);
        when(owner.getName()).thenReturn("t");
    }

    @Test
    void quotedGranteeCanContainEqualsAndEscapedQuote() {
        var grantees = PostgreUtils.extractGranteesFromACL(database,
            new String[] {"\"read=\"\"role\"=r/owner"});
        assertEquals(1, grantees.size());
        assertEquals("read=\"role", grantees.iterator().next().getRoleName());
    }

    @Test
    void quotedGrantorAndPerPrivilegeGrantOptionsArePreserved() {
        var privileges = PostgreUtils.extractPermissionsFromACL(owner,
            new String[] {"\"read=role\"=r*w/\"owner\"\" name\""}, false);
        assertEquals(1, privileges.size());
        var privilege = (PostgreObjectPrivilege) privileges.get(0);
        assertEquals("read=role", privilege.getGrantee().getRoleName());
        assertEquals(PostgrePrivilege.GRANTED | PostgrePrivilege.WITH_GRANT_OPTION,
            privilege.getPermission(PostgrePrivilegeType.SELECT));
        assertEquals(PostgrePrivilege.GRANTED, privilege.getPermission(PostgrePrivilegeType.UPDATE));
        assertEquals(2, privilege.getPermissions().length);
        for (var permission : privilege.getPermissions()) {
            assertEquals("owner\" name", permission.getGrantor().getRoleName());
        }
    }

    @Test
    void publicAndDuplicateGranteesAreRecognizedAndMalformedItemsIgnored() {
        var grantees = PostgreUtils.extractGranteesFromACL(database,
            new String[] {"=r/owner", "=w/owner", "", "broken"});
        assertEquals(1, grantees.size());
        assertEquals("public", grantees.iterator().next().getRoleName());
    }

    @Test
    void defaultAclKeepsGrantorAndDoesNotInventGrantOption() {
        var privileges = PostgreUtils.extractPermissionsFromACL(owner, new String[] {"reader=r/owner"}, true);
        assertEquals(1, privileges.size());
        var privilege = assertInstanceOf(PostgreDefaultPrivilege.class, privileges.get(0));
        assertEquals("owner", privilege.getGrantor().getRoleName());
        assertEquals(PostgrePrivilege.GRANTED, privilege.getPermission(PostgrePrivilegeType.SELECT));
        assertEquals(PostgrePrivilege.NONE, privilege.getPermission(PostgrePrivilegeType.INSERT));
    }
}
