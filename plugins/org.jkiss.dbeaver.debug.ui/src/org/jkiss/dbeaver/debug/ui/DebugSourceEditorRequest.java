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
package org.jkiss.dbeaver.debug.ui;

import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.jkiss.dbeaver.ui.editors.DatabaseLazyEditorInput;
import org.jkiss.dbeaver.ui.editors.INavigatorEditorInput;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks user navigation while the initial debugger source document is loading. */
final class DebugSourceEditorRequest implements IPartListener2 {
    final IWorkbenchPage page;
    final IEditorPart editor;
    private final AtomicLong sourceRequest;
    private final long id;
    private final IWorkbenchPart activePart;
    private final IEditorInput input;
    private boolean cancelled;

    DebugSourceEditorRequest(AtomicLong sourceRequest, long id, IWorkbenchPage page, IWorkbenchPart activePart, IEditorPart editor) {
        this.sourceRequest = sourceRequest;
        this.id = id;
        this.page = page;
        this.activePart = activePart;
        this.editor = editor;
        this.input = editor.getEditorInput();
        page.addPartListener(this);
    }

    @Override
    public void partActivated(IWorkbenchPartReference partRef) {
        IWorkbenchPart activated = partRef.getPart(false);
        if (activated != activePart && activated != editor) {
            cancelled = true;
        }
    }

    @Override
    public void partClosed(IWorkbenchPartReference partRef) {
        if (partRef.getPart(false) == editor) {
            cancelled = true;
        }
    }

    boolean isCurrent() {
        return !(cancelled || sourceRequest.get() != id || page.getWorkbenchWindow().getShell().isDisposed()
            || page.getWorkbenchWindow().getActivePage() != page
            || page.getActivePart() != activePart && page.getActivePart() != editor
            || Arrays.stream(page.getEditorReferences()).noneMatch(ref -> ref.getEditor(false) == editor)
            || !sameInput());
    }

    private boolean sameInput() {
        IEditorInput current = editor.getEditorInput();
        // Editor restoration replaces its lazy input with the corresponding real input.
        return current == input || input instanceof DatabaseLazyEditorInput lazy
            && current instanceof INavigatorEditorInput navigatorInput
            && navigatorInput.getNavigatorNode() != null
            && lazy.getNodePath().equals(navigatorInput.getNavigatorNode().getNodeUri());
    }
}
