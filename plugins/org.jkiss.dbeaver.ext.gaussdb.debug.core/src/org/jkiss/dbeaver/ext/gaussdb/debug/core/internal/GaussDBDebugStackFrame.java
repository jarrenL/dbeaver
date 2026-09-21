/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.dbeaver.debug.DBGStackFrame;

public record GaussDBDebugStackFrame(int level, String name, int lineNumber, long routineOid, String query)
    implements DBGStackFrame {
    @Override
    public int getLevel() {
        return level;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getLineNumber() {
        return lineNumber;
    }

    @Override
    public Object getSourceIdentifier() {
        return routineOid;
    }
}
