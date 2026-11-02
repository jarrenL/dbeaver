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

import org.eclipse.core.resources.IMarker;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorNested;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerObjectOpen;

import java.util.LinkedHashMap;
import java.util.Map;

/** Describes a breakpoint's source without opening a workbench editor. */
final class DatabaseBreakpointEditorInput {
    private DatabaseBreakpointEditorInput() {
    }

    @NotNull
    static IDatabaseEditorInput create(@NotNull DBNDatabaseNode node, @Nullable String sourceFolderId) {
        return create(node, sourceFolderId, null);
    }

    @NotNull
    static IDatabaseEditorInput create(
        @NotNull DBNDatabaseNode node,
        @Nullable String sourceFolderId,
        @Nullable IMarker marker
    ) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(DBPScriptObject.OPTION_DEBUGGER_SOURCE, true);
        // Source folders are created asynchronously, so IDE.gotoMarker may run
        // before the nested text editor exists. Carry this one Open request to it.
        if (marker != null) {
            attributes.put(SQLEditorNested.ATTRIBUTE_PENDING_SOURCE_MARKER, marker);
        }
        // ObjectEditorInput belongs to the navigator's non-exported split package.
        // Let its exported factory create the correct input and page metadata.
        return NavigatorHandlerObjectOpen.createEntityEditorInput(node, null, sourceFolderId, attributes);
    }
}
