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
import org.jkiss.dbeaver.ext.postgresql.model.PostgreServerExtension;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.sql.ResultSet;
import java.sql.SQLException;

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
    void unresolvedOwnerOmitsOptionalClauseInsteadOfCrashingPreview() throws Exception {
        var tablespace = model();
        when(tablespace.getDatabase().getRoleById(any(), eq(42L))).thenReturn(null);
        tablespace.setLoc("/data/preview");
        assertEquals("CREATE TABLESPACE \"space\"\"name\" LOCATION '/data/preview'",
            tablespace.getObjectDefinitionText(new VoidProgressMonitor(), Map.of()));
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

    @Test
    void catalogMetadataKeepsIdentityOwnerLocationAndOptions() throws Exception {
        var database = model().getDatabase();
        var server = mock(PostgreServerExtension.class);
        when(database.getDataSource().getServerType()).thenReturn(server);
        when(server.supportsTablespaceLocation()).thenReturn(true);
        var rows = mock(ResultSet.class);
        when(rows.getLong("oid")).thenReturn(101L);
        when(rows.getLong("spcowner")).thenReturn(42L);
        when(rows.getString("spcname")).thenReturn("catalog_space");
        when(rows.getString("loc")).thenReturn("/data/catalog_space");
        var tablespace = new PostgreTablespace(database, rows);
        assertEquals(101L, tablespace.getObjectId());
        assertEquals("catalog_space", tablespace.getName());
        assertEquals("owner name", tablespace.getOwner(new VoidProgressMonitor()).getName());
        assertEquals("/data/catalog_space", tablespace.getLoc());
        assertEquals("", tablespace.getOptions());
    }

    @Test
    void unsupportedLocationDoesNotReadMissingColumn() throws Exception {
        var database = model().getDatabase();
        var server = mock(PostgreServerExtension.class);
        when(database.getDataSource().getServerType()).thenReturn(server);
        var rows = mock(ResultSet.class);
        when(rows.getString("loc")).thenThrow(new SQLException("Missing optional column"));
        assertNull(new PostgreTablespace(database, rows).getLoc());
        verify(rows, never()).getString("loc");
    }

    @Test
    void catalogOwnerLookupFailureIsNotHidden() throws Exception {
        var tablespace = model();
        var failure = new DBException("Role metadata unavailable");
        when(tablespace.getDatabase().getRoleById(any(), eq(42L))).thenThrow(failure);
        assertSame(failure, assertThrows(DBException.class,
            () -> tablespace.getObjectDefinitionText(new VoidProgressMonitor(), Map.of())));
    }

    @Test
    void optionsArePreservedInDefinition() throws Exception {
        var tablespace = model();
        tablespace.setOptions("random_page_cost=2,seq_page_cost=1");
        assertTrue(tablespace.getObjectDefinitionText(new VoidProgressMonitor(), Map.of())
            .endsWith("\nWITH (random_page_cost=2,seq_page_cost=1)"));
    }

    @Test
    void dropManagerQuotesIdentifierWithoutCascade() throws Exception {
        var actions = new ArrayList<DBEPersistAction>();
        new Manager().drop(model(), actions);
        assertEquals(1, actions.size());
        assertEquals("DROP TABLESPACE \"space\"\"name\"", actions.get(0).getScript());
    }

    private static class Manager extends PostgreTablespaceManager {
        void drop(PostgreTablespace tablespace, List<DBEPersistAction> actions) {
            var command = new ObjectDeleteCommand(tablespace, "Drop fixture tablespace");
            addObjectDeleteActions(new VoidProgressMonitor(), mock(DBCExecutionContext.class), actions, command, Map.of());
        }
        void create(PostgreTablespace tablespace, List<DBEPersistAction> actions) throws DBException {
            var command = mock(ObjectCreateCommand.class);
            when(command.getObject()).thenReturn(tablespace);
            addObjectCreateActions(new VoidProgressMonitor(), mock(DBCExecutionContext.class), actions, command, Map.of());
        }
    }
}
