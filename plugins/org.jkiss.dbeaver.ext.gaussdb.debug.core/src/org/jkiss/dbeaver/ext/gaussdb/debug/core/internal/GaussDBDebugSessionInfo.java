/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.dbeaver.debug.DBGSessionInfo;

import java.util.Map;

public record GaussDBDebugSessionInfo(long processId, String nodeName, int port) implements DBGSessionInfo {
    @Override
    public Object getID() {
        return processId;
    }

    @Override
    public String getTitle() {
        return "GaussDB PL/SQL " + processId;
    }

    @Override
    public Map<String, Object> toMap() {
        return Map.of("pid", processId, "node", nodeName, "port", port);
    }
}
