/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.eclipse.core.resources.IMarker;
import org.jkiss.dbeaver.debug.DBGBreakpointDescriptor;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugConstants;
import org.jkiss.utils.CommonUtils;

import java.util.HashMap;
import java.util.Map;

public class GaussDBDebugBreakpointDescriptor implements DBGBreakpointDescriptor {
    private static final String ATTR_ENABLED = "enabled"; //$NON-NLS-1$
    private final long routineOid;
    private final long lineNumber;
    private final String databaseName;
    private int serverId = -1;
    private boolean enabled = true;

    public GaussDBDebugBreakpointDescriptor(long routineOid, long lineNumber) {
        this(routineOid, lineNumber, null);
    }

    public GaussDBDebugBreakpointDescriptor(long routineOid, long lineNumber, String databaseName) {
        this.routineOid = routineOid;
        this.lineNumber = lineNumber;
        this.databaseName = databaseName;
    }

    public long getRoutineOid() {
        return routineOid;
    }

    public long getLineNumber() {
        return lineNumber;
    }

    public int getServerId() {
        return serverId;
    }

    public void setServerId(int serverId) {
        this.serverId = serverId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put(GaussDBDebugConstants.ATTR_ROUTINE_OID, String.valueOf(routineOid));
        result.put(IMarker.LINE_NUMBER, lineNumber);
        result.put(ATTR_ENABLED, enabled);
        if (databaseName != null) {
            result.put(GaussDBDebugConstants.ATTR_DATABASE_NAME, databaseName);
        }
        return result;
    }

    public static GaussDBDebugBreakpointDescriptor fromMap(Map<String, Object> attributes) {
        GaussDBDebugBreakpointDescriptor descriptor = new GaussDBDebugBreakpointDescriptor(
            CommonUtils.toLong(attributes.get(GaussDBDebugConstants.ATTR_ROUTINE_OID)),
            CommonUtils.toLong(attributes.get(IMarker.LINE_NUMBER)),
            (String) attributes.get(GaussDBDebugConstants.ATTR_DATABASE_NAME)
        );
        descriptor.enabled = CommonUtils.toBoolean(attributes.get(ATTR_ENABLED), true);
        return descriptor;
    }
}
