/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.eclipse.core.runtime.IAdapterFactory;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.debug.DBGBreakpointDescriptor;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugCore;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;

public class GaussDBBreakpointAdapterFactory implements IAdapterFactory {
    private static final Log log = Log.getLog(GaussDBBreakpointAdapterFactory.class);
    private static final Class<?>[] ADAPTERS = {DBGBreakpointDescriptor.class};

    @Override
    public <T> T getAdapter(Object adaptableObject, Class<T> adapterType) {
        if (adapterType == DBGBreakpointDescriptor.class && adaptableObject instanceof GaussDBProcedure routine &&
            "gaussdb".equals(routine.getDataSource().getContainer().getDriver().getProviderId())) {
            try {
                if (GaussDBDebugCore.getRoutineEligibilityError(new VoidProgressMonitor(), routine) != null) {
                    return null;
                }
            } catch (DBException e) {
                log.debug("Unable to determine GaussDB breakpoint eligibility", e);
                return null;
            }
            return adapterType.cast(new GaussDBDebugBreakpointDescriptor(
                routine.getObjectId(), -1, routine.getDatabase().getName()));
        }
        return null;
    }

    @Override
    public Class<?>[] getAdapterList() {
        return ADAPTERS;
    }
}
