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
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.debug.DBGVariable;

import java.util.List;

/** A read-only cursor-state snapshot returned by info_locals, without accessing the target portal. */
public final class GaussDBCursorVariable extends GaussDBDebugVariable {
    private final String rawValue;
    private final String displayValue;
    private final List<? extends DBGVariable<?>> children;

    GaussDBCursorVariable(
        @NotNull GaussDBDebugVariable source,
        @Nullable String displayValue,
        @NotNull List<? extends DBGVariable<?>> children
    ) {
        super(source.getName(), source.getDataType(), source.getVal(), source.getPackageName(), true, source.getLineNumber());
        rawValue = source.getVal();
        this.displayValue = displayValue;
        this.children = List.copyOf(children);
    }

    @Nullable
    @Override
    public String getVal() {
        return displayValue;
    }

    @Nullable
    public String getRawValue() {
        return rawValue;
    }

    @NotNull
    @Override
    public List<? extends DBGVariable<?>> getChildren() {
        return children;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public void setValue(@Nullable String value) {
        throw new UnsupportedOperationException("Cursor state is read-only");
    }
}
