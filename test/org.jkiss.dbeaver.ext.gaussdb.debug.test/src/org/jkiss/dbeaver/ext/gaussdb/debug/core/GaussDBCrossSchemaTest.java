/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugResolver;
import org.jkiss.dbeaver.ext.gaussdb.model.*;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBCrossSchemaTest {
    @Test
    void resolvesCrossSchemaOidWithoutChangingLaunchConfiguration() throws Exception {
        var monitor = mock(DBRProgressMonitor.class);
        var container = mock(DBPDataSourceContainer.class);
        var source = mock(GaussDBDataSource.class);
        var database = mock(GaussDBDatabase.class);
        var parent = mock(GaussDBSchema.class, RETURNS_DEEP_STUBS);
        var other = mock(GaussDBSchema.class, RETURNS_DEEP_STUBS);
        var sameName = mock(GaussDBProcedure.class);
        var child = mock(GaussDBProcedure.class);
        when(sameName.getObjectId()).thenReturn(1L);
        when(child.getObjectId()).thenReturn(2L);
        when(container.isConnected()).thenReturn(true);
        when(container.getDataSource()).thenReturn(source);
        when(source.getDatabase("test")).thenReturn(database);
        when(database.getSchema(monitor, "parent_schema")).thenReturn(parent);
        when(database.getSchema(monitor, "other_schema")).thenReturn(other);
        when(parent.getGaussDBProceduresCache().getAllObjects(monitor, parent)).thenReturn(List.of(sameName));
        when(other.getGaussDBProceduresCache().getAllObjects(monitor, other)).thenReturn(List.of(child));
        Map<String, Object> launch = Map.of(GaussDBDebugConstants.ATTR_DATABASE_NAME, "test",
            GaussDBDebugConstants.ATTR_SCHEMA_NAME, "parent_schema", GaussDBDebugConstants.ATTR_ROUTINE_OID, "1");
        var resolver = new GaussDBDebugResolver(container);
        var session = mock(JDBCSession.class);
        var context = mock(JDBCExecutionContext.class);
        var statement = mock(JDBCPreparedStatement.class);
        var rows = mock(JDBCResultSet.class);
        when(database.isInstanceConnected()).thenReturn(true);
        when(database.getDefaultContext(any(), eq(true))).thenReturn(context);
        when(context.openSession(eq(monitor), any(), anyString())).thenReturn(session);
        when(session.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getString(1)).thenReturn("other_schema");
        assertSame(child, resolver.resolveObject(launch, 2L, monitor));
        verify(statement).setObject(1, 2L);
        assertEquals("1", launch.get(GaussDBDebugConstants.ATTR_ROUTINE_OID));
        assertSame(sameName, resolver.resolveObject(launch, null, monitor));
        when(rows.next()).thenReturn(false);
        assertThrows(org.jkiss.dbeaver.DBException.class, () -> resolver.resolveObject(launch, 3L, monitor));
    }
}
