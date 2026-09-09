/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.eclipse.core.resources.IMarker;
import org.jkiss.dbeaver.debug.DBGConstants;
import org.jkiss.dbeaver.debug.DBGController;
import org.jkiss.dbeaver.debug.core.DebugUtils;
import org.jkiss.dbeaver.debug.core.breakpoints.IDatabaseBreakpoint;
import org.jkiss.dbeaver.debug.core.model.DatabaseDebugTarget;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugBreakpointDescriptor;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseBreakpointRoutingTest {
    @Test
    void preservesProviderRoutineIdentityAcrossSharedMarkerConversion() {
        Map<String, Object> attributes = Map.of(
            GaussDBDebugConstants.ATTR_ROUTINE_OID, "205263", IMarker.LINE_NUMBER, 6,
            DBGConstants.BREAKPOINT_ATTRIBUTE_DATASOURCE_ID, "gaussdb-acceptance");
        Map<String, Object> copy = DebugUtils.toBreakpointDescriptor(attributes);
        GaussDBDebugBreakpointDescriptor descriptor = GaussDBDebugBreakpointDescriptor.fromMap(copy);
        assertEquals(205263L, descriptor.getRoutineOid());
        assertEquals(6, descriptor.getLineNumber());
        assertEquals(attributes, copy);
        assertNotSame(attributes, copy);
    }

    @Test
    void routesByDebugModelAndDatasourceNotMarkerType() throws Exception {
        DatabaseDebugTarget target = mock(DatabaseDebugTarget.class, CALLS_REAL_METHODS);
        DBGController controller = mock(DBGController.class);
        DBPDataSourceContainer container = mock(DBPDataSourceContainer.class);
        when(target.getController()).thenReturn(controller);
        when(controller.getDataSourceContainer()).thenReturn(container);
        when(container.getId()).thenReturn("gaussdb-acceptance");
        IDatabaseBreakpoint breakpoint = mock(IDatabaseBreakpoint.class);
        when(breakpoint.getModelIdentifier()).thenReturn(DBGConstants.MODEL_IDENTIFIER_DATABASE);
        when(breakpoint.getDatasourceId()).thenReturn("gaussdb-acceptance");
        assertTrue(target.supportsBreakpoint(breakpoint));
        when(breakpoint.getDatasourceId()).thenReturn("other-connection");
        assertFalse(target.supportsBreakpoint(breakpoint));
        when(breakpoint.getDatasourceId()).thenReturn("gaussdb-acceptance");
        when(breakpoint.getModelIdentifier()).thenReturn(DBGConstants.BREAKPOINT_ID_DATABASE_LINE);
        assertFalse(target.supportsBreakpoint(breakpoint));
    }
}
