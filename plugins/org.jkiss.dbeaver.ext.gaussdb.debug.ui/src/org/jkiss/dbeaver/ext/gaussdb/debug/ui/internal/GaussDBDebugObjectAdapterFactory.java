/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.ui.internal;

import org.eclipse.core.runtime.IAdapterFactory;
import org.eclipse.ui.IEditorPart;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.debug.DBGDebugObject;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugCore;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure;
import org.jkiss.dbeaver.ext.postgresql.ui.editors.PostgreSourceViewEditor;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;

public class GaussDBDebugObjectAdapterFactory implements IAdapterFactory {
    private static final Log log = Log.getLog(GaussDBDebugObjectAdapterFactory.class);
    private static final Class<?>[] ADAPTERS = {DBGDebugObject.class};
    private static final DBGDebugObject DEBUG_OBJECT = new DBGDebugObject() {};

    @Override
    public <T> T getAdapter(Object adaptableObject, Class<T> adapterType) {
        if (adapterType != DBGDebugObject.class) {
            return null;
        }
        if (adaptableObject instanceof IEditorPart editorPart) {
            adaptableObject = editorPart.getEditorInput();
        }
        GaussDBProcedure routine = null;
        if (adaptableObject instanceof PostgreSourceViewEditor sourceEditor &&
            sourceEditor.getSourceObject() instanceof GaussDBProcedure sourceRoutine) {
            routine = sourceRoutine;
        } else if (adaptableObject instanceof IDatabaseEditorInput input &&
            input.getDatabaseObject() instanceof GaussDBProcedure inputRoutine) {
            routine = inputRoutine;
        } else if (adaptableObject instanceof DBNDatabaseNode node && node.getObject() instanceof GaussDBProcedure nodeRoutine) {
            routine = nodeRoutine;
        }
        if (routine == null || !"gaussdb".equals(routine.getDataSource().getContainer().getDriver().getProviderId())) {
            return null;
        }
        try {
            return GaussDBDebugCore.getRoutineEligibilityError(new VoidProgressMonitor(), routine) == null
                ? adapterType.cast(DEBUG_OBJECT) : null;
        } catch (DBException e) {
            log.debug("Unable to determine whether the GaussDB routine can be debugged", e);
            return null;
        }
    }

    @Override
    public Class<?>[] getAdapterList() {
        return ADAPTERS;
    }
}
