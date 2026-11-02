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
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBSchema;
import org.jkiss.dbeaver.model.navigator.DBNModel;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
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
    public void prepareSourceNavigation(DBSObject object, DBNModel navigator, DBRProgressMonitor monitor) throws DBException {
        if (!(object instanceof GaussDBProcedure routine) || routine.getPropackageid() == 0 || monitor.isCanceled()) {
            return;
        }
        // A Package member's model parent is its schema, but its navigator parent is
        // the Package. Loading only the schema's standalone routines cannot find it.
        var owner = (GaussDBSchema) routine.getSchema();
        var gaussPackage = owner.getPackages(monitor).stream()
            .filter(candidate -> candidate.getObjectId() == routine.getPropackageid()).findFirst()
            .orElseThrow(() -> new DBException("Package " + routine.getPropackageid() + " for debug source is unavailable"));
        var packageNode = navigator.getNodeByObject(monitor, gaussPackage, false);
        if (packageNode == null) {
            return;
        }
        for (var child : packageNode.getChildren(monitor)) {
            if (monitor.isCanceled()) {
                return;
            }
            if (child instanceof DBNDatabaseFolder folder && folder.getChildrenClass() != null
                && folder.getChildrenClass().isAssignableFrom(routine.getClass())) {
                folder.getChildren(monitor);
            }
        }
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
