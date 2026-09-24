/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.edit;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDataSource;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDialect;
import org.jkiss.dbeaver.ext.postgresql.edit.PostgreTablespaceManager;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDatabase;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreRole;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreTablespace;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBTablespaceContractTest {
    private PostgreTablespace model() throws DBException {
        var source = mock(GaussDBDataSource.class);
        when(source.getSQLDialect()).thenReturn(new GaussDBDialect());
        var database = mock(PostgreDatabase.class);
        when(database.getDataSource()).thenReturn(source);
        var owner = mock(PostgreRole.class);
        when(owner.getDataSource()).thenReturn(source);
        when(owner.getName()).thenReturn("owner name");
        when(database.getRoleById(any(), eq(42L))).thenReturn(owner);
        var result = new PostgreTablespace(database);
        result.setOwnerId(42L);
        result.setName("space\"name");
        return result;
    }

    @ParameterizedTest
    @CsvSource(value = {"/data/simple|/data/simple", "/data/O'Brien|/data/O''Brien", "/data/中文 空间|/data/中文 空间"}, delimiter = '|')
    void locationIsOneQuotedLiteralAndNamesAreIdentifiers(String location, String escaped) throws Exception {
        var tablespace = model();
        tablespace.setLoc(location);
        assertEquals("CREATE TABLESPACE \"space\"\"name\" OWNER \"owner name\" LOCATION '" + escaped + "'",
            tablespace.getObjectDefinitionText(new VoidProgressMonitor(), Map.of()));
    }

    @Test
    void emptyLocationAndOptionsDoNotGenerateEmptyClauses() throws Exception {
        assertEquals("CREATE TABLESPACE \"space\"\"name\" OWNER \"owner name\"",
            model().getObjectDefinitionText(new VoidProgressMonitor(), Map.of()));
    }

    @Test
    void createManagerKeepsDefinitionErrorsInsteadOfReturningSuccessfulEmptyActions() throws Exception {
        var tablespace = mock(PostgreTablespace.class);
        var failure = new DBException("Synthetic owner lookup failure");
        when(tablespace.getObjectDefinitionText(any(), any())).thenThrow(failure);
        var actions = new ArrayList<DBEPersistAction>();
        assertSame(failure, assertThrows(DBException.class, () -> new Manager().create(tablespace, actions)));
        assertTrue(actions.isEmpty());
    }

    @Test
    void managerUsesTheSameSafeDefinition() throws Exception {
        var tablespace = model();
        tablespace.setLoc("/data/O'Brien");
        var actions = new ArrayList<DBEPersistAction>();
        new Manager().create(tablespace, actions);
        assertEquals(1, actions.size());
        assertEquals("CREATE TABLESPACE \"space\"\"name\" OWNER \"owner name\" LOCATION '/data/O''Brien'",
            actions.get(0).getScript());
    }

    private static class Manager extends PostgreTablespaceManager {
        void create(PostgreTablespace tablespace, List<DBEPersistAction> actions) throws DBException {
            var command = mock(ObjectCreateCommand.class);
            when(command.getObject()).thenReturn(tablespace);
            addObjectCreateActions(new VoidProgressMonitor(), mock(DBCExecutionContext.class), actions, command, Map.of());
        }
    }
}
