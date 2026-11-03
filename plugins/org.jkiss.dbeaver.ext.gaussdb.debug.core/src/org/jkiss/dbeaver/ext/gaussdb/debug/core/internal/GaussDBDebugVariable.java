/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.debug.DBGVariable;
import org.jkiss.dbeaver.debug.DBGVariableType;

public class GaussDBDebugVariable implements DBGVariable<String> {
    private final String name;
    private final String dataType;
    private final String packageName;
    private final boolean constant;
    private final int frameNumber;
    private String value;

    public GaussDBDebugVariable(
        String name,
        String dataType,
        String value,
        String packageName,
        boolean constant,
        int frameNumber
    ) {
        this.name = name;
        this.dataType = dataType;
        this.value = value;
        this.packageName = packageName;
        this.constant = constant;
        this.frameNumber = frameNumber;
    }

    @Override
    public String getVal() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public DBGVariableType getType() {
        return DBGVariableType.TEXT;
    }

    @Override
    public int getLineNumber() {
        return frameNumber;
    }

    @Override
    public boolean isReadOnly() {
        // set_var has no frame selector and always writes the currently executing frame.
        // A cursor value is a target-session portal, not an editable scalar expression.
        return constant || frameNumber != 0 || isCursorType(dataType);
    }

    @NotNull
    @Override
    public String getDataTypeName() {
        return dataType == null || dataType.isBlank() ? getType().toString() : dataType;
    }

    public static boolean isCursorType(@Nullable String dataType) {
        if (dataType == null) {
            return false;
        }
        String type = dataType.trim();
        return "refcursor".equalsIgnoreCase(type) || "sys_refcursor".equalsIgnoreCase(type)
            || "pg_catalog.refcursor".equalsIgnoreCase(type) || "pg_catalog.sys_refcursor".equalsIgnoreCase(type);
    }

    public String getDataType() {
        return dataType;
    }

    public String getPackageName() {
        return packageName;
    }

    @Nullable
    @Override
    public String getScopeName() {
        return packageName;
    }
}
