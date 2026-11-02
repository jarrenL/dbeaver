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

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.ide.IGotoMarker;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Routes explicit breakpoint navigation through an existing object's Properties page. */
class BreakpointSourceRoutingTest {
    private static final String PENDING_MARKER = "source.pendingMarker";

    @Test
    void requestingTheFallbackAdapterDoesNotSwitchOrOpenAnyEditor() throws Exception {
        Fixture f = new Fixture();
        // Reading the real extension descriptor must not start DebugUIPlugin:
        // that plug-in's factory also advertises actions requiring a workbench.
        assertTrue(Arrays.stream(Platform.getExtensionRegistry().getConfigurationElementsFor(
            "org.eclipse.core.runtime.adapters"))
            .filter(factory -> "org.jkiss.dbeaver.ui.editors.entity.EntityEditor".equals(
                factory.getAttribute("adaptableType")))
            .filter(factory -> "org.jkiss.dbeaver.debug.ui.actions.DebugActionAdapterFactory".equals(
                factory.getAttribute("class")))
            .flatMap(factory -> Arrays.stream(factory.getChildren("adapter")))
            .anyMatch(adapter -> IGotoMarker.class.getName().equals(adapter.getAttribute("type"))));

        assertInstanceOf(IGotoMarker.class, f.actualAdapter());
        verifyNoInteractions(f.editor, f.marker, f.warning);
    }

    @Test
    void nonBreakpointMarkerDelegatesWithoutChangingTheSourceFormat() throws Exception {
        Fixture f = new Fixture();
        IGotoMarker nested = mock(IGotoMarker.class);
        when(f.editorType.getMethod("getNestedAdapter", Class.class).invoke(f.editor, IGotoMarker.class)).thenReturn(nested);

        f.actualAdapter().gotoMarker(f.marker);

        verify(nested).gotoMarker(f.marker);
        assertTrue(f.attributes.isEmpty());
        assertEquals(0, mockingDetails(f.editor).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("switchFolder")).count());
    }

    @Test
    void nonBreakpointMarkerOnPropertiesDoesNotCreateOrSelectSource() throws Exception {
        Fixture f = new Fixture();

        f.actualAdapter().gotoMarker(f.marker);

        assertTrue(f.attributes.isEmpty());
        assertEquals(0, mockingDetails(f.editor).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("switchFolder")).count());
    }

    @Test
    void unreadableMarkerDoesNotChangeAnExistingEditor() throws Exception {
        Fixture f = new Fixture();
        when(f.marker.isSubtypeOf(anyString())).thenThrow(new CoreException(
            new Status(IStatus.ERROR, "org.jkiss.dbeaver.test", "Marker was deleted")));

        f.actualAdapter().gotoMarker(f.marker);

        assertTrue(f.attributes.isEmpty());
        assertEquals(0, mockingDetails(f.editor).getInvocations().size());
    }

    @Test
    void existingDebuggerSourceIsSelectedWithoutRefreshingEvenWhenDirty() throws Exception {
        Fixture f = new Fixture();
        f.attributes.put(DBPScriptObject.OPTION_DEBUGGER_SOURCE, true);
        when(f.editorType.getMethod("isDirty").invoke(f.editor)).thenReturn(true);
        Object source = f.source(true);

        f.navigate("routine.source");

        f.verifySwitch();
        f.verifyMarker(source);
        assertFalse(f.wasRefreshed(source));
        assertNull(f.attributes.get(PENDING_MARKER));
        verifyNoInteractions(f.warning);
    }

    @Test
    void cleanDdlSourceIsReloadedAsDebuggerSourceBeforePositioning() throws Exception {
        Fixture f = new Fixture();
        Object source = f.source(true);

        f.navigate("routine.source");

        assertEquals(Boolean.TRUE, f.attributes.get(DBPScriptObject.OPTION_DEBUGGER_SOURCE));
        f.verifySwitch();
        var order = inOrder(f.editor, source);
        f.editorType.getMethod("switchFolder", String.class).invoke(order.verify(f.editor), "routine.source");
        f.sourceType.getMethod("refreshPart", Object.class, boolean.class).invoke(order.verify(source), f.editor, true);
        f.sourceType.getMethod("gotoMarker", IMarker.class).invoke(order.verify(source), f.marker);
        verifyNoInteractions(f.warning);
    }

    @Test
    void unsavedDdlChangesArePreservedAndTheUserIsAskedToSave() throws Exception {
        Fixture f = new Fixture();
        when(f.editorType.getMethod("isDirty").invoke(f.editor)).thenReturn(true);
        Object source = f.source(true);

        f.navigate("routine.source");

        verify(f.warning).run();
        assertTrue(f.attributes.isEmpty());
        assertFalse(f.wasRefreshed(source));
        assertEquals(0, mockingDetails(f.editor).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("switchFolder")).count());
        assertEquals(0, mockingDetails(source).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("gotoMarker")).count());
    }

    @Test
    void sourceFolderCreatedLaterReceivesOnlyTheRequestedMarker() throws Exception {
        Fixture f = new Fixture();

        f.navigate("routine.source");

        f.verifySwitch();
        assertSame(f.marker, f.attributes.get(PENDING_MARKER));
        assertEquals(Boolean.TRUE, f.attributes.get(DBPScriptObject.OPTION_DEBUGGER_SOURCE));
        verifyNoInteractions(f.warning);
    }

    @Test
    void alreadyLoadingSourceDefersNavigationWithoutRestartingItsLoad() throws Exception {
        Fixture f = new Fixture();
        Object source = f.source(false);

        f.navigate("routine.source");

        f.verifyMarker(source);
        assertFalse(f.wasRefreshed(source));
        assertNull(f.attributes.get(PENDING_MARKER));
        verifyNoInteractions(f.warning);
    }

    @Test
    void missingSourceAdvisorDoesNotSelectAnUnrelatedFolder() throws Exception {
        Fixture f = new Fixture();

        f.navigate(null);

        assertTrue(f.attributes.isEmpty());
        assertEquals(0, mockingDetails(f.editor).getInvocations().size());
        verifyNoInteractions(f.warning);
    }

    @Test
    void deletedBreakpointDoesNotReopenOrRepositionSource() throws Exception {
        Fixture f = new Fixture();
        when(f.marker.exists()).thenReturn(false);

        f.navigate("routine.source");

        assertTrue(f.attributes.isEmpty());
        assertEquals(0, mockingDetails(f.editor).getInvocations().size());
        verifyNoInteractions(f.warning);
    }

    private static final class Fixture {
        final Class<?> editorType;
        final Class<?> inputType;
        final Class<?> sourceType;
        final Object editor;
        final Object input;
        final IMarker marker = mock(IMarker.class);
        final Runnable warning = mock(Runnable.class);
        final Map<String, Object> attributes = new HashMap<>();

        Fixture() throws Exception {
            editorType = Platform.getBundle("org.jkiss.dbeaver.ui.navigator").loadClass(
                "org.jkiss.dbeaver.ui.editors.entity.EntityEditor");
            inputType = Platform.getBundle("org.jkiss.dbeaver.ui.editors.base").loadClass(
                "org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput");
            sourceType = Platform.getBundle("org.jkiss.dbeaver.ui.editors.sql").loadClass(
                "org.jkiss.dbeaver.ui.editors.sql.SQLEditorNested");
            editor = mock(editorType);
            input = mock(inputType, invocation -> switch (invocation.getMethod().getName()) {
                case "getAttribute" -> attributes.get(invocation.getArgument(0));
                case "setAttribute" -> attributes.put(invocation.getArgument(0), invocation.getArgument(1));
                default -> RETURNS_DEFAULTS.answer(invocation);
            });
            when(editorType.getMethod("getEditorInput").invoke(editor)).thenReturn(input);
            when(marker.exists()).thenReturn(true);
            clearInvocations(editor);
        }

        Object source(boolean loaded) throws Exception {
            Object source = mock(sourceType);
            when(sourceType.getMethod("isDocumentLoaded").invoke(source)).thenReturn(loaded);
            // Text editors expose an IGotoMarker adapter; the editor itself does
            // not implement that interface. Model its own typed adapter here.
            when(editorType.getMethod("getNestedAdapter", Class.class).invoke(editor, sourceType)).thenReturn(source);
            return source;
        }

        void navigate(String folder) throws Exception {
            Class<?> router = Platform.getBundle("org.jkiss.dbeaver.debug.ui").loadClass(
                "org.jkiss.dbeaver.debug.ui.actions.DatabaseBreakpointGotoMarker");
            Method method = router.getDeclaredMethod("navigate", editorType, String.class, IMarker.class, Runnable.class);
            method.setAccessible(true);
            method.invoke(null, editor, folder, marker, warning);
        }

        IGotoMarker actualAdapter() throws Exception {
            Class<?> router = Platform.getBundle("org.jkiss.dbeaver.debug.ui").loadClass(
                "org.jkiss.dbeaver.debug.ui.actions.DatabaseBreakpointGotoMarker");
            var constructor = router.getDeclaredConstructor(editorType);
            constructor.setAccessible(true);
            return (IGotoMarker) constructor.newInstance(editor);
        }

        void verifySwitch() throws Exception {
            editorType.getMethod("switchFolder", String.class).invoke(verify(editor), "routine.source");
        }

        void verifyMarker(Object source) throws Exception {
            sourceType.getMethod("gotoMarker", IMarker.class).invoke(verify(source), marker);
        }

        boolean wasRefreshed(Object source) {
            return mockingDetails(source).getInvocations().stream()
                .anyMatch(i -> i.getMethod().getName().equals("refreshPart"));
        }
    }
}
