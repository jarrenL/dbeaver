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
import org.jkiss.dbeaver.model.DBConstants;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class GaussDBPackageManagerTest {
    private static final String SPEC = "CREATE PACKAGE s.p AS FUNCTION f RETURN INTEGER; END p;";
    private static final String BODY = "CREATE PACKAGE BODY s.p AS FUNCTION f RETURN INTEGER AS BEGIN RETURN 43; END; END p;";

    private static class Manager extends GaussDBPackageManager {
        List<DBEPersistAction> modify(String declaration, String body, String... changed) throws DBException {
            GaussDBPackage pack = mock(GaussDBPackage.class);
            when(pack.getObjectDefinitionText()).thenReturn(declaration);
            when(pack.getExtendedDefinitionText()).thenReturn(body);
            ObjectChangeCommand command = new ObjectChangeCommand(pack);
            for (String property : changed) {
                command.getProperties().put(property, "changed");
            }
            List<DBEPersistAction> actions = new ArrayList<>();
            addObjectModifyActions(new VoidProgressMonitor(), mock(DBCExecutionContext.class), actions, command, Map.of());
            return actions;
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
