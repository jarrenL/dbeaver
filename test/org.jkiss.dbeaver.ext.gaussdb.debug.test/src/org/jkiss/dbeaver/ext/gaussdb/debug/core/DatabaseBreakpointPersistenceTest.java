/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IProcess;
import org.jkiss.dbeaver.debug.DBGConstants;
import org.jkiss.dbeaver.debug.DBGSession;
import org.jkiss.dbeaver.debug.core.breakpoints.DatabaseLineBreakpoint;
import org.jkiss.dbeaver.debug.core.model.DatabaseDebugTarget;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugBreakpointDescriptor;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugController;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DatabaseBreakpointPersistenceTest {
    @Test
    void storedBreakpointIsPushedAgainAfterTargetTerminationAndRestart() throws Exception {
        String datasourceId = "breakpoint-restart-" + UUID.randomUUID();
        String databaseName = "review_db";
        var descriptor = new GaussDBDebugBreakpointDescriptor(8201L, 7, databaseName);
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(datasourceId);
        IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();
        WorkspaceBreakpoint breakpoint = new WorkspaceBreakpoint();
        DatabaseDebugTarget firstTarget = null;
        DatabaseDebugTarget restartedTarget = null;
        try {
            project.create(null);
            project.open(null);
            IMarker marker = project.createMarker(DBGConstants.BREAKPOINT_ID_DATABASE_LINE);
            Map<String, Object> attributes = descriptor.toMap();
            // Eclipse resource markers support Integer, but not Long, attributes.
            attributes.put(IMarker.LINE_NUMBER, Math.toIntExact(descriptor.getLineNumber()));
            attributes.put(DBGConstants.BREAKPOINT_ATTRIBUTE_DATASOURCE_ID, datasourceId);
            attributes.put(IBreakpoint.ID, DBGConstants.MODEL_IDENTIFIER_DATABASE);
            attributes.put(IBreakpoint.ENABLED, true);
            marker.setAttributes(attributes);
            breakpoint.assign(marker);
            manager.addBreakpoint(breakpoint);

            DBPDataSourceContainer container = mock(DBPDataSourceContainer.class);
            when(container.getId()).thenReturn(datasourceId);
            GaussDBDebugController controller = mock(GaussDBDebugController.class);
            when(controller.getDataSourceContainer()).thenReturn(container);
            when(controller.describeBreakpoint(any())).thenAnswer(invocation -> {
                Map<String, Object> markerAttributes = invocation.getArgument(0);
                return datasourceId.equals(markerAttributes.get(DBGConstants.BREAKPOINT_ATTRIBUTE_DATASOURCE_ID))
                    ? GaussDBDebugBreakpointDescriptor.fromMap(markerAttributes)
                    : null;
            });
            DBGSession firstSession = mock(DBGSession.class);
            DBGSession restartedSession = mock(DBGSession.class);
            when(controller.openSession(any())).thenReturn(firstSession, restartedSession);

            firstTarget = target(controller);
            firstTarget.connect(new NullProgressMonitor());
            verify(firstSession).addBreakpoint(any(), argThat(candidate ->
                candidate instanceof GaussDBDebugBreakpointDescriptor actual &&
                    actual.getRoutineOid() == 8201L && actual.getLineNumber() == 7));

            firstTarget.terminate();
            assertTrue(manager.isRegistered(breakpoint));
            assertTrue(marker.exists());
            verify(firstSession).closeSession(any());

            restartedTarget = target(controller);
            restartedTarget.connect(new NullProgressMonitor());
            verify(restartedSession).addBreakpoint(any(), argThat(candidate ->
                candidate instanceof GaussDBDebugBreakpointDescriptor actual &&
                    actual.getRoutineOid() == 8201L && actual.getLineNumber() == 7));
            assertTrue(manager.isRegistered(breakpoint));
        } finally {
            if (firstTarget != null && !firstTarget.isTerminated()) {
                firstTarget.terminate();
            }
            if (restartedTarget != null && !restartedTarget.isTerminated()) {
                restartedTarget.terminate();
            }
            if (manager.isRegistered(breakpoint)) {
                manager.removeBreakpoint(breakpoint, true);
            }
            if (project.exists()) {
                project.delete(true, true, null);
            }
        }
    }

    private static DatabaseDebugTarget target(GaussDBDebugController controller) {
        IProcess process = mock(IProcess.class);
        when(process.isTerminated()).thenReturn(false);
        return new DatabaseDebugTarget(
            DBGConstants.MODEL_IDENTIFIER_DATABASE, mock(ILaunch.class), process, controller);
    }

    private static final class WorkspaceBreakpoint extends DatabaseLineBreakpoint {
        void assign(IMarker marker) throws org.eclipse.core.runtime.CoreException {
            setMarker(marker);
        }
    }
}
