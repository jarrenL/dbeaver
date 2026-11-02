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
package org.jkiss.dbeaver.acceptance;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.resources.IMarker;
import org.eclipse.ui.PlatformUI;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Queries breakpoint source descriptions in a process without opening an editor. */
class BreakpointEditorInputTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectingBreakpointOnlyDescribesItsObjectAndDebuggerSource(boolean objectNode) throws Exception {
        var model = Platform.getBundle("org.jkiss.dbeaver.model");
        Class<?> nodeType = model.loadClass("org.jkiss.dbeaver.model.navigator.DBNDatabaseNode");
        Class<?> concreteType = objectNode
            ? model.loadClass("org.jkiss.dbeaver.model.navigator.DBNDatabaseObject") : nodeType;
        Object node = mock(concreteType);
        when(nodeType.getMethod("getNodeId").invoke(node)).thenReturn("synthetic-breakpoint");
        boolean workbenchRunning = PlatformUI.isWorkbenchRunning();

        Object input = describe(nodeType, node, "synthetic.source.folder");

        assertEquals(workbenchRunning, PlatformUI.isWorkbenchRunning());
        assertEquals(objectNode ? "ObjectEditorInput" : "DatabaseNodeEditorInput", input.getClass().getSimpleName());
        assertSame(node, input.getClass().getMethod("getNavigatorNode").invoke(input));
        assertEquals("synthetic.source.folder", input.getClass().getMethod("getDefaultFolderId").invoke(input));
        assertEquals("default.object.editor", input.getClass().getMethod("getDefaultPageId").invoke(input));
        assertEquals(Boolean.TRUE, input.getClass().getMethod("getAttribute", String.class)
            .invoke(input, DBPScriptObject.OPTION_DEBUGGER_SOURCE));
    }

    @Test
    void missingEditorAdvisorDoesNotInventAnUnrelatedSourcePage() throws Exception {
        Class<?> nodeType = Platform.getBundle("org.jkiss.dbeaver.model").loadClass(
            "org.jkiss.dbeaver.model.navigator.DBNDatabaseNode");
        Object node = mock(nodeType);
        when(nodeType.getMethod("getNodeId").invoke(node)).thenReturn("synthetic-no-advisor");

        Object input = describe(nodeType, node, null);

        assertNull(input.getClass().getMethod("getDefaultFolderId").invoke(input));
        assertSame(node, input.getClass().getMethod("getNavigatorNode").invoke(input));
        assertEquals(Boolean.TRUE, input.getClass().getMethod("getAttribute", String.class)
            .invoke(input, DBPScriptObject.OPTION_DEBUGGER_SOURCE));
    }

    @Test
    void coldSourceFolderReceivesTheExplicitOpenMarkerWithoutOpeningAnEditor() throws Exception {
        Class<?> nodeType = Platform.getBundle("org.jkiss.dbeaver.model").loadClass(
            "org.jkiss.dbeaver.model.navigator.DBNDatabaseNode");
        Object node = mock(nodeType);
        when(nodeType.getMethod("getNodeId").invoke(node)).thenReturn("synthetic-cold-open");
        IMarker marker = mock(IMarker.class);
        Class<?> factory = Platform.getBundle("org.jkiss.dbeaver.debug.ui").loadClass(
            "org.jkiss.dbeaver.debug.ui.DatabaseBreakpointEditorInput");
        var method = factory.getDeclaredMethod("create", nodeType, String.class, IMarker.class);
        method.setAccessible(true);

        Object input = method.invoke(null, node, "synthetic.source.folder", marker);

        assertSame(marker, input.getClass().getMethod("getAttribute", String.class).invoke(input, "source.pendingMarker"));
        verifyNoInteractions(marker);
        assertEquals(Boolean.TRUE, input.getClass().getMethod("getAttribute", String.class)
            .invoke(input, DBPScriptObject.OPTION_DEBUGGER_SOURCE));
    }

    private static Object describe(Class<?> nodeType, Object node, String sourceFolder) throws Exception {
        Class<?> factory = Platform.getBundle("org.jkiss.dbeaver.debug.ui").loadClass(
            "org.jkiss.dbeaver.debug.ui.DatabaseBreakpointEditorInput");
        var method = factory.getDeclaredMethod("create", nodeType, String.class);
        method.setAccessible(true);
        return method.invoke(null, node, sourceFolder);
    }
}
