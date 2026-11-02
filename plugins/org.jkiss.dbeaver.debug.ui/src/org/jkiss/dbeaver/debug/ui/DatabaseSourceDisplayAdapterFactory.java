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

import org.eclipse.core.runtime.IAdapterFactory;
import org.eclipse.debug.ui.sourcelookup.ISourceDisplay;
import org.jkiss.dbeaver.debug.core.model.DatabaseStackFrame;

/** Keeps database source lookup and its eventual UI display in one cancellable request. */
public final class DatabaseSourceDisplayAdapterFactory implements IAdapterFactory {
    private final ISourceDisplay sourceDisplay = new DatabaseSourceDisplay();

    @Override
    public <T> T getAdapter(Object adaptableObject, Class<T> adapterType) {
        return adaptableObject instanceof DatabaseStackFrame && adapterType == ISourceDisplay.class
            ? adapterType.cast(sourceDisplay) : null;
    }

    @Override
    public Class<?>[] getAdapterList() {
        return new Class<?>[] {ISourceDisplay.class};
    }
}
