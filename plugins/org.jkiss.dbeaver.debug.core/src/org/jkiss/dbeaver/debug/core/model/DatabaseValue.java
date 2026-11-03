/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Copyright (C) 2017-2018 Alexander Fedorov (alexander.fedorov@jkiss.org)
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

package org.jkiss.dbeaver.debug.core.model;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;
import org.jkiss.dbeaver.debug.DBGVariable;

import java.util.List;

public class DatabaseValue extends DatabaseDebugElement implements IValue {

    private final DBGVariable<?> dbgVariable;
    private final List<? extends DBGVariable<?>> children;
    private volatile IVariable[] variables;

    public DatabaseValue(IDatabaseDebugTarget target, DBGVariable<?> dbgObject) {
        super(target);
        this.dbgVariable = dbgObject;
        this.children = List.copyOf(dbgObject.getChildren());
    }

    @Override
    public String getReferenceTypeName() throws DebugException {
        return dbgVariable.getDataTypeName();
    }

    @Override
    public String getValueString() throws DebugException {
        return String.valueOf(dbgVariable.getVal());
    }

    @Override
    public boolean isAllocated() throws DebugException {
        return false;
    }

    @Override
    public IVariable[] getVariables() throws DebugException {
        IVariable[] snapshot = variables;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = variables;
                if (snapshot == null) {
                    // Eclipse loads ranges asynchronously. Keep each child wrapper stable for
                    // this suspended-frame snapshot, including across separate range requests.
                    snapshot = children.stream()
                        .map(child -> new DatabaseVariable(getDatabaseDebugTarget(), child))
                        .toArray(IVariable[]::new);
                    variables = snapshot;
                }
            }
        }
        return snapshot.clone();
    }

    @Override
    public boolean hasVariables() throws DebugException {
        return !children.isEmpty();
    }

}
