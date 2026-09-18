/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.eclipse.core.resources.IMarkerDelta;
import org.jkiss.dbeaver.debug.DBGConstants;
import org.jkiss.dbeaver.debug.DBGController;
import org.jkiss.dbeaver.debug.DBGSession;
import org.jkiss.dbeaver.debug.core.breakpoints.DatabaseLineBreakpoint;
import org.jkiss.dbeaver.debug.core.model.DatabaseDebugTarget;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.*;

class GaussDBRemovedBreakpointTest {
    @Test
    void removedMarkerUsesDeltaWithoutReadingDeadMarker() throws Exception {
        var target = mock(DatabaseDebugTarget.class, CALLS_REAL_METHODS);
        var controller = mock(DBGController.class);
        var session = mock(DBGSession.class);
        var container = mock(DBPDataSourceContainer.class);
        when(controller.getDataSourceContainer()).thenReturn(container);
        when(container.getId()).thenReturn("ds");
        for (var entry : Map.of("controller", controller, "session", session,
            "breakpointIdentities", new java.util.concurrent.ConcurrentHashMap<>()).entrySet()) {
            var field = DatabaseDebugTarget.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(target, entry.getValue());
        }
        var breakpoint = mock(DatabaseLineBreakpoint.class);
        when(breakpoint.getModelIdentifier()).thenReturn(DBGConstants.MODEL_IDENTIFIER_DATABASE);
        var delta = mock(IMarkerDelta.class);
        var attributes = new java.util.HashMap<String, Object>(new GaussDBDebugBreakpointDescriptor(42, 3, "db").toMap());
        attributes.put(DBGConstants.BREAKPOINT_ATTRIBUTE_DATASOURCE_ID, "ds");
        when(delta.getAttributes()).thenReturn(attributes);
        var descriptor = GaussDBDebugBreakpointDescriptor.fromMap(attributes);
        when(controller.describeBreakpoint(attributes)).thenReturn(descriptor);
        target.breakpointRemoved(breakpoint, delta);
        verify(session).removeBreakpoint(any(), same(descriptor));
        verify(breakpoint, never()).getMarker();
        verify(breakpoint, never()).getDatasourceId();
    }
}
