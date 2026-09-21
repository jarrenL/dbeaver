/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.debug.DBGResolver;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugConstants;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugCore;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;

import java.util.HashMap;
import java.util.Map;

public class GaussDBDebugResolver implements DBGResolver {
    private final DBPDataSourceContainer container;

    public GaussDBDebugResolver(DBPDataSourceContainer container) {
        this.container = container;
    }

    @Override
    public DBSObject resolveObject(Map<String, Object> context, Object identifier, DBRProgressMonitor monitor) throws DBException {
        Map<String, Object> resolved = context;
        if (identifier != null) {
            resolved = new HashMap<>(context);
            resolved.put(GaussDBDebugConstants.ATTR_ROUTINE_OID, String.valueOf(identifier));
        }
        return GaussDBDebugCore.resolveRoutine(monitor, container, resolved);
    }

    @Override
    public Map<String, Object> resolveContext(DBSObject databaseObject) {
        Map<String, Object> context = new HashMap<>();
        if (databaseObject instanceof GaussDBProcedure routine) {
            GaussDBDebugCore.saveRoutine(routine, context);
        }
        return context;
    }
}
