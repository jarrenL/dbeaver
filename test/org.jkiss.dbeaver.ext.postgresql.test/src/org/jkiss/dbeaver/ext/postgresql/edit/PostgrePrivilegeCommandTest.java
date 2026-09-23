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
package org.jkiss.dbeaver.ext.postgresql.edit;

import org.jkiss.dbeaver.ext.postgresql.model.*;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgrePrivilegeCommandTest {
    private PostgreRole role;
    private PostgreTable table;
    private PostgreRolePrivilege privilege;

    @BeforeEach
    void setup() {
        var source = mock(PostgreDataSource.class);
        when(source.getSQLDialect()).thenReturn(new PostgreDialect());
        when(source.getSupportedPrivilegeTypes()).thenReturn(new PostgrePrivilegeType[] {
            PostgrePrivilegeType.SELECT, PostgrePrivilegeType.INSERT
        });
        role = mock(PostgreRole.class);
        when(role.getDataSource()).thenReturn(source);
        when(role.getName()).thenReturn("read\" role");
        table = mock(PostgreTable.class);
        privilege = mock(PostgreRolePrivilege.class);
        when(privilege.getFullObjectName()).thenReturn("\"测试 schema\".\"table\"");
        when(privilege.getKind()).thenReturn(PostgrePrivilegeGrant.Kind.TABLE);
    }

    private PostgreCommandGrantPrivilege command(boolean grant, PostgrePrivilegeType... types) {
        return new PostgreCommandGrantPrivilege(role, grant, table, privilege, types);
    }

    private String sql(PostgreCommandGrantPrivilege command) {
        var actions = command.getPersistActions(mock(DBRProgressMonitor.class), mock(DBCExecutionContext.class), Map.of());
        assertNotNull(actions);
        assertEquals(1, actions.length);
        return actions[0].getScript();
    }

    @Test
    void grantQuotesRoleAndUsesQualifiedObject() {
        assertEquals("GRANT SELECT ON TABLE \"测试 schema\".\"table\" TO \"read\"\" role\"",
            sql(command(true, PostgrePrivilegeType.SELECT)));
    }

    @Test
    void revokeDoesNotEmitGrantOption() {
        when(privilege.getPermission(PostgrePrivilegeType.SELECT)).thenReturn(PostgrePrivilege.WITH_GRANT_OPTION);
        assertEquals("REVOKE SELECT ON TABLE \"测试 schema\".\"table\" FROM \"read\"\" role\"",
            sql(command(false, PostgrePrivilegeType.SELECT)));
    }

    @Test
    void singlePrivilegePreservesGrantOption() {
        when(privilege.getPermission(PostgrePrivilegeType.SELECT)).thenReturn(PostgrePrivilege.WITH_GRANT_OPTION);
        assertTrue(sql(command(true, PostgrePrivilegeType.SELECT)).endsWith(" WITH GRANT OPTION"));
    }

    @Test
    void allPrivilegesPreserveGrantOption() {
        when(privilege.getPermission(PostgrePrivilegeType.SELECT)).thenReturn(PostgrePrivilege.WITH_GRANT_OPTION);
        when(privilege.getPermission(PostgrePrivilegeType.INSERT)).thenReturn(PostgrePrivilege.WITH_GRANT_OPTION);
        String ddl = sql(new PostgreCommandGrantPrivilege(role, true, table, privilege, null));
        assertTrue(ddl.startsWith("GRANT ALL ON TABLE "));
        assertTrue(ddl.endsWith(" WITH GRANT OPTION"), ddl);
    }

    @Test
    void explicitAllUsedByDdlExportPreservesGrantOption() {
        when(privilege.getPermission(PostgrePrivilegeType.SELECT)).thenReturn(PostgrePrivilege.WITH_GRANT_OPTION);
        when(privilege.getPermission(PostgrePrivilegeType.INSERT)).thenReturn(PostgrePrivilege.WITH_GRANT_OPTION);
        assertTrue(sql(command(true, PostgrePrivilegeType.ALL)).endsWith(" WITH GRANT OPTION"));
    }

    @Test
    void mixedGrantabilityDoesNotEscalateOtherPrivileges() {
        when(privilege.getPermission(PostgrePrivilegeType.SELECT)).thenReturn(PostgrePrivilege.WITH_GRANT_OPTION);
        var actions = command(true, PostgrePrivilegeType.ALL).getPersistActions(
            mock(DBRProgressMonitor.class), mock(DBCExecutionContext.class), Map.of());
        assertEquals(2, actions.length);
        assertEquals("GRANT INSERT ON TABLE \"测试 schema\".\"table\" TO \"read\"\" role\"", actions[0].getScript());
        assertEquals("GRANT SELECT ON TABLE \"测试 schema\".\"table\" TO \"read\"\" role\" WITH GRANT OPTION",
            actions[1].getScript());
    }

    @Test
    void multipleColumnPrivilegesEachCarryTheColumnRestriction() {
        var source = role.getDataSource();
        var column = mock(PostgreTableColumn.class);
        when(column.getDataSource()).thenReturn(source);
        when(column.getName()).thenReturn("private\" field");
        when(column.getTable()).thenReturn(table);
        when(table.getFullyQualifiedName(org.jkiss.dbeaver.model.DBPEvaluationContext.DDL)).thenReturn("s.t");
        var permission = mock(PostgreObjectPrivilege.class);
        when(permission.getGrantee()).thenReturn(new PostgreRoleReference(mock(PostgreDatabase.class), "reader", null));
        when(source.getSupportedPrivilegeTypes()).thenReturn(new PostgrePrivilegeType[] {
            PostgrePrivilegeType.SELECT, PostgrePrivilegeType.INSERT, PostgrePrivilegeType.UPDATE
        });
        assertEquals("GRANT SELECT(\"private\"\" field\"), UPDATE(\"private\"\" field\") ON s.t TO reader",
            sql(new PostgreCommandGrantPrivilege(column, true, column, permission,
                new PostgrePrivilegeType[] {PostgrePrivilegeType.SELECT, PostgrePrivilegeType.UPDATE})));
    }

    @Test
    void defaultPrivilegesQuoteSchemaGrantorAndGrantee() {
        var source = role.getDataSource();
        var schema = mock(PostgreSchema.class);
        when(schema.getDataSource()).thenReturn(source);
        when(schema.getName()).thenReturn("schema space");
        var permission = mock(PostgreDefaultPrivilege.class);
        when(permission.getOwner()).thenReturn(schema);
        when(permission.getDataSource()).thenReturn(source);
        when(permission.getGrantee()).thenReturn(new PostgreRoleReference(mock(PostgreDatabase.class), "read role", null));
        when(permission.getGrantor()).thenReturn(new PostgreRoleReference(mock(PostgreDatabase.class), "owner role", null));
        when(permission.getUnderKind()).thenReturn(PostgrePrivilegeGrant.Kind.TABLE);
        assertEquals("ALTER DEFAULT PRIVILEGES FOR ROLE \"owner role\" IN SCHEMA \"schema space\" GRANT SELECT ON TABLES TO \"read role\"",
            sql(new PostgreCommandGrantPrivilege(schema, true, table, permission,
                new PostgrePrivilegeType[] {PostgrePrivilegeType.SELECT})));
    }

    @Test
    void repeatedMergeIsIdempotent() {
        var grant = command(true, PostgrePrivilegeType.SELECT);
        Map<Object, Object> context = new HashMap<>();
        grant.merge(null, context);
        String before = sql(grant);
        grant.merge(grant, context);
        assertEquals(before, sql(grant));
    }

    @Test
    void grantThenRevokeSamePrivilegeLeavesNoActions() {
        var grant = command(true, PostgrePrivilegeType.SELECT);
        var revoke = command(false, PostgrePrivilegeType.SELECT);
        Map<Object, Object> context = new HashMap<>();
        grant.merge(null, context);
        revoke.merge(grant, context);
        for (var command : new PostgreCommandGrantPrivilege[] { grant, revoke }) {
            assertEquals(0, command.getPersistActions(
                mock(DBRProgressMonitor.class), mock(DBCExecutionContext.class), Map.of()).length);
        }
    }
}
