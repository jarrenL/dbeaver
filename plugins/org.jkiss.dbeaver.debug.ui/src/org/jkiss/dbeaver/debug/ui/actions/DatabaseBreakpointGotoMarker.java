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
package org.jkiss.dbeaver.debug.ui.actions;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.SWT;
import org.eclipse.ui.ide.IGotoMarker;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.debug.DBGConstants;
import org.jkiss.dbeaver.debug.ui.DBGEditorAdvisor;
import org.jkiss.dbeaver.debug.ui.DebugUI;
import org.jkiss.dbeaver.debug.ui.internal.DebugUIMessages;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.ui.editors.entity.EntityEditor;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorNested;

/** Opens the source folder only for an explicit breakpoint Go to Source action. */
final class DatabaseBreakpointGotoMarker implements IGotoMarker {
    private static final Log log = Log.getLog(DatabaseBreakpointGotoMarker.class);
    private final EntityEditor editor;

    DatabaseBreakpointGotoMarker(@NotNull EntityEditor editor) {
        this.editor = editor;
    }

    @Override
    public void gotoMarker(IMarker marker) {
        try {
            if (!marker.exists()) {
                return;
            }
            if (!marker.isSubtypeOf(DBGConstants.BREAKPOINT_ID_DATABASE_LINE)) {
                IGotoMarker target = editor.getNestedAdapter(IGotoMarker.class);
                if (target != null) {
                    target.gotoMarker(marker);
                }
                return;
            }
        } catch (CoreException e) {
            log.debug("Could not inspect breakpoint marker", e);
            return;
        }
        DBGEditorAdvisor advisor = DebugUI.findEditorAdvisor(editor.getDataSourceContainer());
        navigate(editor, advisor == null ? null : advisor.getSourceFolderId(), marker,
            () -> UIUtils.showMessageBox(editor.getSite().getShell(),
                DebugUIMessages.BreakpointSource_unsaved_title,
                DebugUIMessages.BreakpointSource_unsaved_message, SWT.ICON_WARNING));
    }

    static void navigate(
        @NotNull EntityEditor editor,
        @Nullable String sourceFolder,
        @NotNull IMarker marker,
        @NotNull Runnable unsavedWarning
    ) {
        if (sourceFolder == null || !marker.exists()) {
            return;
        }
        IDatabaseEditorInput input = editor.getEditorInput();
        boolean debuggerSource = Boolean.TRUE.equals(input.getAttribute(DBPScriptObject.OPTION_DEBUGGER_SOURCE));
        if (!debuggerSource && editor.isDirty()) {
            // Switching source formats can reload text. Never discard an edit.
            unsavedWarning.run();
            return;
        }
        input.setAttribute(DBPScriptObject.OPTION_DEBUGGER_SOURCE, true);
        input.setAttribute(SQLEditorNested.ATTRIBUTE_PENDING_SOURCE_MARKER, marker);
        editor.switchFolder(sourceFolder);

        // Use the nested adapter directly: getAdapter would return this fallback
        // again while Properties is active or the source is still being created.
        SQLEditorNested<?> source = editor.getNestedAdapter(SQLEditorNested.class);
        if (source != null) {
            if (!debuggerSource && source.isDocumentLoaded()) {
                source.refreshPart(editor, true);
            }
            input.setAttribute(SQLEditorNested.ATTRIBUTE_PENDING_SOURCE_MARKER, null);
            source.gotoMarker(marker);
            return;
        }
        IGotoMarker target = editor.getNestedAdapter(IGotoMarker.class);
        if (target != null) {
            input.setAttribute(SQLEditorNested.ATTRIBUTE_PENDING_SOURCE_MARKER, null);
            target.gotoMarker(marker);
        }
        // A source folder created later consumes the one-time input attribute.
    }
}
