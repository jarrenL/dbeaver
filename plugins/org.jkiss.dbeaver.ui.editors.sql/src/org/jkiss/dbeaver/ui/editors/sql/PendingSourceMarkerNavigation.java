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
package org.jkiss.dbeaver.ui.editors.sql;

import org.eclipse.core.resources.IMarker;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/** Keeps the latest marker until its own source document has finished loading. */
final class PendingSourceMarkerNavigation {
    private Object input;
    private IMarker marker;

    void remember(@NotNull Object sourceInput, @NotNull IMarker requestedMarker) {
        input = sourceInput;
        marker = requestedMarker;
    }

    void discardForOtherInput(@NotNull Object sourceInput) {
        if (input != sourceInput) {
            clear();
        }
    }

    @Nullable
    IMarker complete(@NotNull Object loadedInput, boolean liveEditor, boolean successfulLoad) {
        if (input != loadedInput) {
            // An older completion must not consume a newer input's navigation.
            return null;
        }
        IMarker result = marker;
        clear();
        return liveEditor && successfulLoad && result != null && result.exists() ? result : null;
    }

    void clear() {
        input = null;
        marker = null;
    }
}
