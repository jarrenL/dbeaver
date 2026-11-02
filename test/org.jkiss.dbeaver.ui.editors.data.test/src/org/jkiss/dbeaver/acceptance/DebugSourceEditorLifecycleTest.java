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
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DebugSourceEditorLifecycleTest {
    private static final class PendingSource {
        final AtomicLong requestSequence = new AtomicLong();
        final Object request;
        final Class<?> requestType;
        final IWorkbenchPage page = mock(IWorkbenchPage.class);
        final IWorkbenchWindow window = mock(IWorkbenchWindow.class);
        final IWorkbenchPart activePart = mock(IWorkbenchPart.class);
        final IEditorPart editor = mock(IEditorPart.class);
        final IEditorInput input = mock(IEditorInput.class);
        final IEditorReference editorReference = mock(IEditorReference.class);
        final Shell shell = mock(Shell.class);

        PendingSource() throws Exception {
            when(page.getWorkbenchWindow()).thenReturn(window);
            when(window.getShell()).thenReturn(shell);
            when(window.getActivePage()).thenReturn(page);
            when(page.getActivePart()).thenReturn(activePart);
            when(editor.getEditorInput()).thenReturn(input);
            when(editorReference.getEditor(false)).thenReturn(editor);
            when(page.getEditorReferences()).thenReturn(new IEditorReference[] {editorReference});
            requestType = Platform.getBundle("org.jkiss.dbeaver.debug.ui").loadClass(
                "org.jkiss.dbeaver.debug.ui.DebugSourceEditorRequest");
            var constructor = requestType.getDeclaredConstructor(
                AtomicLong.class, long.class, IWorkbenchPage.class, IWorkbenchPart.class, IEditorPart.class);
            constructor.setAccessible(true);
            request = constructor.newInstance(requestSequence, 0L, page, activePart, editor);
        }

        boolean current() throws Exception {
            var method = requestType.getDeclaredMethod("isCurrent");
            method.setAccessible(true);
            return (boolean) method.invoke(request);
        }

        void event(String name, IWorkbenchPart part) throws Exception {
            var reference = mock(IWorkbenchPartReference.class);
            when(reference.getPart(false)).thenReturn(part);
            var method = requestType.getDeclaredMethod(name, IWorkbenchPartReference.class);
            method.setAccessible(true);
            method.invoke(request, reference);
        }
    }

    @Test
    void pendingSourceKeepsOriginalViewActive() throws Exception {
        var source = new PendingSource();
        assertTrue(source.current());
        assertNotSame(source.editor, source.activePart);
    }

    @Test
    void ownSourceEditorProgrammaticActivationDoesNotCancelInitialPositioning() throws Exception {
        var source = new PendingSource();
        when(source.page.getActivePart()).thenReturn(source.editor);
        source.event("partActivated", source.editor);
        assertTrue(source.current());
    }

    @Test
    void activatingAnotherViewStillCancelsPendingPresentationLookup() throws Exception {
        var source = new PendingSource();
        var otherView = mock(IWorkbenchPart.class);
        when(source.page.getActivePart()).thenReturn(otherView);
        source.event("partActivated", otherView);
        assertFalse(source.current());
    }

    @Test
    void closedEditorCannotBeReturnedByLateLookup() throws Exception {
        var source = new PendingSource();
        when(source.page.getEditorReferences()).thenReturn(new IEditorReference[0]);
        assertFalse(source.current());
    }

    @Test
    void tabSwitchCancelsLookupEvenAfterSwitchingBack() throws Exception {
        var source = new PendingSource();
        source.event("partActivated", mock(IWorkbenchPart.class));
        source.event("partActivated", source.activePart);
        assertFalse(source.current());
    }

    @Test
    void sourceCloseEventCancelsEvenBeforeReferenceIsRemoved() throws Exception {
        var source = new PendingSource();
        source.event("partClosed", source.editor);
        assertFalse(source.current());
    }

    @Test
    void replacedEditorInputCancelsOldLookup() throws Exception {
        var source = new PendingSource();
        when(source.editor.getEditorInput()).thenReturn(mock(IEditorInput.class));
        assertFalse(source.current());
    }

    @Test
    void newerSourceRequestSupersedesOlderLookup() throws Exception {
        var source = new PendingSource();
        source.requestSequence.incrementAndGet();
        assertFalse(source.current());
    }

    @Test
    void closedWindowCancelsLookup() throws Exception {
        var source = new PendingSource();
        when(source.shell.isDisposed()).thenReturn(true);
        assertFalse(source.current());
    }

    @Test
    void switchingWorkbenchPageCancelsLookup() throws Exception {
        var source = new PendingSource();
        when(source.window.getActivePage()).thenReturn(mock(IWorkbenchPage.class));
        assertFalse(source.current());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingEditorHonorsRequestedActivation(boolean activate) throws Exception {
        var source = new PendingSource();
        var nodeType = Platform.getBundle("org.jkiss.dbeaver.model").loadClass("org.jkiss.dbeaver.model.navigator.DBNNode");
        var node = mock(nodeType);
        var inputType = Platform.getBundle("org.jkiss.dbeaver.ui.editors.base").loadClass(
            "org.jkiss.dbeaver.ui.editors.INavigatorEditorInput");
        var input = mock(inputType);
        when(inputType.getMethod("getNavigatorNode").invoke(input)).thenReturn(node);
        when(source.editorReference.getEditorInput()).thenReturn((IEditorInput) input);
        when(source.editorReference.getEditor(true)).thenReturn(source.editor);
        when(source.editor.getEditorInput()).thenReturn((IEditorInput) input);
        var navigatorType = Platform.getBundle("org.jkiss.dbeaver.ui.navigator").loadClass(
            "org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerObjectOpen");
        Object opened = navigatorType.getMethod("openEntityEditor", nodeType, String.class, String.class, Map.class,
            IWorkbenchWindow.class, boolean.class).invoke(null, node, null, null, null, source.window, activate);
        assertSame(source.editor, opened);
        verify(source.page, times(activate ? 1 : 0)).activate(source.editor);
    }
}
