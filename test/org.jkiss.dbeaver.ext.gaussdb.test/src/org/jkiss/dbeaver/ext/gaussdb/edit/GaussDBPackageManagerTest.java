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
package org.jkiss.dbeaver.ext.gaussdb.edit;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBPackage;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDatabase;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDataSource;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBSchema;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBServerInfo;
import org.jkiss.dbeaver.model.DBConstants;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class GaussDBPackageManagerTest {
    private static final String SPEC = "CREATE PACKAGE s.p AS FUNCTION f RETURN INTEGER; END p;";
    private static final String BODY = "CREATE PACKAGE BODY s.p AS FUNCTION f RETURN INTEGER AS BEGIN RETURN 43; END; END p;";

    private static class Manager extends GaussDBPackageManager {
        List<DBEPersistAction> modify(String declaration, String body, String... changed) throws DBException {
            return modify(declaration, body, GaussDBServerInfo.Deployment.CENTRALIZED, changed);
        }

        List<DBEPersistAction> modify(
            String declaration,
            String body,
            GaussDBServerInfo.Deployment deployment,
            String... changed
        ) throws DBException {
            GaussDBPackage pack = packageObject(declaration, body, "\"Business Data\".\"p\"\"kg\"", deployment);
            ObjectChangeCommand command = new ObjectChangeCommand(pack);
            for (String property : changed) {
                command.getProperties().put(property, "changed");
            }
            List<DBEPersistAction> actions = new ArrayList<>();
            addObjectModifyActions(new VoidProgressMonitor(), mock(DBCExecutionContext.class), actions, command, Map.of());
            return actions;
        }

        List<DBEPersistAction> drop(String qualifiedName) throws DBException {
            return drop(qualifiedName, GaussDBServerInfo.Deployment.CENTRALIZED);
        }

        List<DBEPersistAction> drop(
            String qualifiedName,
            GaussDBServerInfo.Deployment deployment
        ) throws DBException {
            GaussDBPackage pack = packageObject(SPEC, BODY, qualifiedName, deployment);
            ObjectDeleteCommand command = new ObjectDeleteCommand(pack, "Drop fixture package");
            List<DBEPersistAction> actions = new ArrayList<>();
            addObjectDeleteActions(new VoidProgressMonitor(), mock(DBCExecutionContext.class), actions, command, Map.of());
            return actions;
        }

        List<DBEPersistAction> create(GaussDBServerInfo.Deployment deployment) throws DBException {
            GaussDBPackage pack = packageObject(SPEC, BODY, "s.p", deployment);
            List<DBEPersistAction> actions = new ArrayList<>();
            addPackageSourceActions(actions, pack, true, true, false);
            return actions;
        }

        private static GaussDBPackage packageObject(
            String declaration,
            String body,
            String qualifiedName,
            GaussDBServerInfo.Deployment deployment
        ) {
            GaussDBPackage pack = mock(GaussDBPackage.class);
            GaussDBDataSource dataSource = mock(GaussDBDataSource.class);
            GaussDBDatabase database = mock(GaussDBDatabase.class);
            GaussDBSchema schema = mock(GaussDBSchema.class);
            when(pack.getObjectDefinitionText()).thenReturn(declaration);
            when(pack.getExtendedDefinitionText()).thenReturn(body);
            when(pack.getFullyQualifiedName(DBPEvaluationContext.DDL)).thenReturn(qualifiedName);
            when(pack.getName()).thenReturn("p\"kg");
            when(pack.getDataSource()).thenReturn(dataSource);
            when(pack.getDatabase()).thenReturn(database);
            when(pack.getSchema()).thenReturn(schema);
            when(dataSource.getServerInfo()).thenReturn(
                GaussDBServerInfo.forTest(deployment, Set.of("gs_package")));
            when(database.getObjectId()).thenReturn(10L);
            when(schema.getObjectId()).thenReturn(20L);
            when(schema.getName()).thenReturn("Business Data");
            return pack;
        }
    }

    @Test
    void bodyEditDoesNotRecreateUnchangedSpecification() throws Exception {
        var actions = new Manager().modify(SPEC, BODY, DBConstants.PARAM_EXTENDED_DEFINITION_TEXT);
        assertEquals(1, actions.size());
        assertEquals(BODY.replaceFirst("CREATE", "CREATE OR REPLACE"), actions.getFirst().getScript());
    }

    @Test
    void specificationEditDoesNotRewriteUnchangedBody() throws Exception {
        var actions = new Manager().modify(SPEC, BODY, DBConstants.PARAM_OBJECT_DEFINITION_TEXT);
        assertEquals(1, actions.size());
        assertEquals(SPEC.replaceFirst("CREATE", "CREATE OR REPLACE"), actions.getFirst().getScript());
    }

    @Test
    void bothChangedDefinitionsPreserveOrderAndExistingReplace() throws Exception {
        String body = BODY.replaceFirst("CREATE", "create or replace");
        var actions = new Manager().modify(SPEC, body,
            DBConstants.PARAM_OBJECT_DEFINITION_TEXT, DBConstants.PARAM_EXTENDED_DEFINITION_TEXT);
        assertEquals(2, actions.size());
        assertEquals(SPEC.replaceFirst("CREATE", "CREATE OR REPLACE"), actions.get(0).getScript());
        assertEquals(body, actions.get(1).getScript());
    }

    @Test
    void unrelatedPropertyDoesNotRecreatePackage() throws Exception {
        assertTrue(new Manager().modify(SPEC, BODY, DBConstants.PROP_ID_DESCRIPTION).isEmpty());
    }

    @Test
    void clearingBodyDropsOnlyTheQualifiedPackageBody() throws Exception {
        var actions = new Manager().modify(SPEC, "", DBConstants.PARAM_EXTENDED_DEFINITION_TEXT);
        assertEquals(1, actions.size());
        assertEquals("DROP PACKAGE BODY IF EXISTS \"Business Data\".\"p\"\"kg\"", actions.getFirst().getScript());
        assertEquals(DBEPersistAction.ActionType.OPTIONAL, actions.getFirst().getType());
    }

    @Test
    void deletingPackageUsesTheQualifiedNameWithoutCascade() throws Exception {
        var actions = new Manager().drop("\"Business Data\".\"p\"\"kg\"");
        assertEquals(1, actions.size());
        assertEquals("DROP PACKAGE IF EXISTS \"Business Data\".\"p\"\"kg\"", actions.getFirst().getScript());
        assertEquals(DBEPersistAction.ActionType.NORMAL, actions.getFirst().getType());
    }

    @Test
    void distributedCreateModifyAndDeleteAcquireTheSameLockFirst() throws Exception {
        Manager manager = new Manager();

        var create = manager.create(GaussDBServerInfo.Deployment.DISTRIBUTED);
        assertEquals(3, create.size());
        assertTrue(create.get(0).getScript().startsWith("SELECT pg_catalog.pg_advisory_xact_lock("));
        assertEquals(SPEC, create.get(1).getScript());
        assertEquals(BODY, create.get(2).getScript());

        var modify = manager.modify(SPEC, BODY, GaussDBServerInfo.Deployment.DISTRIBUTED,
            DBConstants.PARAM_EXTENDED_DEFINITION_TEXT);
        assertEquals(2, modify.size());
        assertEquals(create.get(0).getScript(), modify.get(0).getScript());
        assertEquals(BODY.replaceFirst("CREATE", "CREATE OR REPLACE"), modify.get(1).getScript());

        var delete = manager.drop("\"Business Data\".\"p\"\"kg\"",
            GaussDBServerInfo.Deployment.DISTRIBUTED);
        assertEquals(2, delete.size());
        assertEquals(create.get(0).getScript(), delete.get(0).getScript());
        assertEquals("DROP PACKAGE IF EXISTS \"Business Data\".\"p\"\"kg\"", delete.get(1).getScript());
    }

    @Test
    void centralizedPackageActionsDoNotAddApplicationLock() throws Exception {
        var actions = new Manager().create(GaussDBServerInfo.Deployment.CENTRALIZED);
        assertEquals(2, actions.size());
        assertEquals(SPEC, actions.get(0).getScript());
        assertEquals(BODY, actions.get(1).getScript());
    }

    @Test
    void distributedLockRefusesAutoCommitAndAllowsExplicitTransaction() throws Exception {
        var action = new Manager().modify(SPEC, BODY, GaussDBServerInfo.Deployment.DISTRIBUTED,
            DBConstants.PARAM_EXTENDED_DEFINITION_TEXT).getFirst();
        JDBCSession session = mock(JDBCSession.class);

        when(session.getAutoCommit()).thenReturn(true);
        DBCException error = assertThrows(DBCException.class, () -> action.beforeExecute(session));
        assertTrue(error.getMessage().contains("explicit transaction"));

        when(session.getAutoCommit()).thenReturn(false);
        assertDoesNotThrow(() -> action.beforeExecute(session));
    }

    @Test
    void commentsAndQuotedSourceArePreserved() throws Exception {
        String sql = "-- CREATE PACKAGE ignored\r\n/* outer /* nested */ end */\n"
            + "CrEaTe/* separator */ PACKAGE BODY s.p AS PROCEDURE f AS BEGIN "
            + "RAISE NOTICE 'CREATE PACKAGE'; END; END p;";
        var actions = new Manager().modify(SPEC, sql, DBConstants.PARAM_EXTENDED_DEFINITION_TEXT);
        assertEquals(sql.replace("CrEaTe/*", "CrEaTe OR REPLACE/*"), actions.getFirst().getScript());
    }

    @Test
    void doesNotRewriteDifferentOrMalformedStatement() throws Exception {
        for (String sql : List.of("CREATE PACKAGE_NOT_A_KEYWORD x;", "/* unfinished CREATE PACKAGE x;",
            "SELECT 'CREATE PACKAGE';", "CREATE OR REPLACE PACKAGE BODY s.p AS END p;")) {
            var actions = new Manager().modify(SPEC, sql, DBConstants.PARAM_EXTENDED_DEFINITION_TEXT);
            assertEquals(sql, actions.getFirst().getScript());
        }
    }
}
