/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.postgresql;

import org.eclipse.core.runtime.Platform;
import org.jkiss.dbeaver.debug.DBGResolver;
import org.jkiss.dbeaver.ext.postgresql.debug.PostgreDebugConstants;
import org.jkiss.dbeaver.ext.postgresql.debug.core.PostgreSqlDebugCore;
import org.jkiss.dbeaver.ext.postgresql.model.*;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PostgreDebugRegressionTest {
    @Test
    void resolvesFrameOidWithoutChangingLaunchConfiguration() throws Exception {
        var container = Mockito.mock(DBPDataSourceContainer.class);
        var dataSource = Mockito.mock(PostgreDataSource.class);
        var database = Mockito.mock(PostgreDatabase.class);
        var schema = Mockito.mock(PostgreSchema.class);
        var child = Mockito.mock(PostgreProcedure.class);
        var monitor = Mockito.mock(DBRProgressMonitor.class);
        Mockito.when(container.isConnected()).thenReturn(true);
        Mockito.when(container.getDataSource()).thenReturn(dataSource);
        Mockito.when(dataSource.getDatabase("test")).thenReturn(database);
        Mockito.when(database.getSchema(monitor, "parent_schema")).thenReturn(schema);
        Mockito.when(database.getProcedure(monitor, 2L)).thenReturn(child);
        Map<String, Object> configuration = Map.of(PostgreDebugConstants.ATTR_DATABASE_NAME, "test",
            PostgreDebugConstants.ATTR_SCHEMA_NAME, "parent_schema", PostgreDebugConstants.ATTR_FUNCTION_OID, "1");
        Class<?> type = Platform.getBundle("org.jkiss.dbeaver.ext.postgresql.debug.core")
            .loadClass("org.jkiss.dbeaver.ext.postgresql.debug.internal.PostgreResolver");
        DBGResolver resolver = (DBGResolver) type.getConstructor(DBPDataSourceContainer.class).newInstance(container);
        assertSame(child, resolver.resolveObject(configuration, 2, monitor));
        assertEquals("1", configuration.get(PostgreDebugConstants.ATTR_FUNCTION_OID));
        Mockito.verify(schema).getProcedure(monitor, 2L);
    }

    @Test
    void recognizesOnlyTheSpecificCompletionDiagnostic() {
        assertTrue(PostgreSqlDebugCore.isCompletionDiagnostic(
            new SQLException("ERROR: select() failed waiting for target", "08006")));
        assertFalse(PostgreSqlDebugCore.isCompletionDiagnostic(new SQLException("connection lost", "08006")));
        assertTrue(PostgreSqlDebugCore.isCompletionDiagnostic(
            new SQLException("ERROR: debugger connection terminated", "08006")));
        assertFalse(PostgreSqlDebugCore.isCompletionDiagnostic(
            new SQLException("select() failed waiting for target", "42601")));
    }
}
