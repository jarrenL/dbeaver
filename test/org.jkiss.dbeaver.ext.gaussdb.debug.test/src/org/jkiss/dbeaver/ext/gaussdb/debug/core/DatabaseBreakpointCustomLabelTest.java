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
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.model.IBreakpoint;
import org.jkiss.dbeaver.debug.DBGConstants;
import org.jkiss.dbeaver.debug.core.breakpoints.DatabaseLineBreakpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseBreakpointCustomLabelTest {
    private static final String ROUTINE_LABEL = "ui_acceptance.ui_parent - [line:6]";
    private IProject project;

    @BeforeEach
    void createMarkerProject() throws Exception {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("breakpoint-label-" + UUID.randomUUID());
        project.create(null);
        project.open(null);
    }

    @AfterEach
    void deleteMarkerProject() throws Exception {
        if (project != null && project.exists()) {
            project.delete(true, true, null);
        }
    }

    @Test
    void persistedLegacyCustomLabelGetsConnectionContextEvenWhenPresentationIsBypassed() throws Exception {
        var breakpoint = breakpoint("connection-a");
        breakpoint.setBreakpointLabel(ROUTINE_LABEL);

        assertEquals("[connection-a] " + ROUTINE_LABEL, breakpoint.getBreakpointLabel());
        assertEquals(ROUTINE_LABEL, breakpoint.getMarker().getAttribute("breakpointLabel", ""));
    }

    @Test
    void userProvidedCustomTextIsPreserved() throws Exception {
        var breakpoint = breakpoint("connection-a");
        breakpoint.setBreakpointLabel("check transfer balance");

        assertEquals("[connection-a] check transfer balance", breakpoint.getBreakpointLabel());
        assertEquals("check transfer balance", breakpoint.getMarker().getAttribute("breakpointLabel", ""));
    }

    @Test
    void missingConnectionIsExplicitInBothCustomAndGeneratedLabels() throws Exception {
        var breakpoint = breakpoint(null);
        breakpoint.setBreakpointLabel(ROUTINE_LABEL);

        assertEquals("[connection: ?] " + ROUTINE_LABEL, breakpoint.getBreakpointLabel());
        assertEquals("[connection: ?] " + ROUTINE_LABEL, breakpoint.getDisplayLabel());
    }

    @Test
    void emptyConnectionIsNotPresentedAsABoundBreakpoint() throws Exception {
        var breakpoint = breakpoint("");
        breakpoint.setBreakpointLabel(ROUTINE_LABEL);

        assertEquals("[connection: ?] " + ROUTINE_LABEL, breakpoint.getBreakpointLabel());
    }

    @Test
    void alreadyPrefixedCustomLabelsAreNotDecoratedTwice() throws Exception {
        var bound = breakpoint("connection-a");
        bound.setBreakpointLabel("[connection-a] custom label");
        var unbound = breakpoint(null);
        unbound.setBreakpointLabel("[connection: ?] old label");

        assertEquals("[connection-a] custom label", bound.getBreakpointLabel());
        assertEquals("[connection: ?] old label", unbound.getBreakpointLabel());
    }

    @Test
    void noCustomLabelKeepsEclipseModelPresentationFallback() throws Exception {
        var breakpoint = breakpoint("connection-a");

        assertNull(breakpoint.getBreakpointLabel());
        assertEquals("[connection-a] " + ROUTINE_LABEL, breakpoint.getDisplayLabel());
    }

    @Test
    void repeatedRenderingDoesNotRewriteAnyPersistedMarkerAttribute() throws Exception {
        var breakpoint = breakpoint("connection-a");
        breakpoint.setBreakpointLabel(ROUTINE_LABEL);
        Map<String, Object> before = new HashMap<>(breakpoint.getMarker().getAttributes());

        for (int call = 0; call < 10; call++) {
            assertEquals("[connection-a] " + ROUTINE_LABEL, breakpoint.getBreakpointLabel());
            assertEquals("[connection-a] " + ROUTINE_LABEL, breakpoint.getDisplayLabel());
        }

        assertEquals(before, breakpoint.getMarker().getAttributes());
    }

    @Test
    void identicalCustomLabelsOnDifferentConnectionsRemainDistinguishable() throws Exception {
        var first = breakpoint("connection-a");
        var second = breakpoint("connection-b");
        first.setBreakpointLabel(ROUTINE_LABEL);
        second.setBreakpointLabel(ROUTINE_LABEL);

        assertNotEquals(first.getBreakpointLabel(), second.getBreakpointLabel());
    }

    private WorkspaceBreakpoint breakpoint(String datasourceId) throws CoreException {
        IMarker marker = project.createMarker(DBGConstants.BREAKPOINT_ID_DATABASE_LINE);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(IMarker.LINE_NUMBER, 6);
        attributes.put(IBreakpoint.ID, DBGConstants.MODEL_IDENTIFIER_DATABASE);
        attributes.put(IBreakpoint.ENABLED, true);
        attributes.put(DBGConstants.BREAKPOINT_ATTRIBUTE_OBJECT_NAME, "ui_acceptance.ui_parent");
        if (datasourceId != null) {
            attributes.put(DBGConstants.BREAKPOINT_ATTRIBUTE_DATASOURCE_ID, datasourceId);
        }
        marker.setAttributes(attributes);
        var breakpoint = new WorkspaceBreakpoint();
        breakpoint.assign(marker);
        return breakpoint;
    }

    private static final class WorkspaceBreakpoint extends DatabaseLineBreakpoint {
        void assign(IMarker marker) throws CoreException {
            setMarker(marker);
        }
    }
}
