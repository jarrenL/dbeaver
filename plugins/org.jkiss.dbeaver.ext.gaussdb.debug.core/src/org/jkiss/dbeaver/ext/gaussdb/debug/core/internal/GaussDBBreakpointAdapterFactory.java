/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.eclipse.core.runtime.IAdapterFactory;
import org.jkiss.dbeaver.debug.DBGBreakpointDescriptor;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure;
import org.jkiss.dbeaver.ext.gaussdb.model.DBCompatibilityEnum;

public class GaussDBBreakpointAdapterFactory implements IAdapterFactory {
    private static final Class<?>[] ADAPTERS = {DBGBreakpointDescriptor.class};

    @Override
    public <T> T getAdapter(Object adaptableObject, Class<T> adapterType) {
        if (adapterType == DBGBreakpointDescriptor.class && adaptableObject instanceof GaussDBProcedure routine &&
            "gaussdb".equals(routine.getDataSource().getContainer().getDriver().getProviderId())) {
            if (routine.getDatabase().getCompatibility() == null
                || routine.getDatabase().getCompatibility() == DBCompatibilityEnum.M
                || !routine.isPersisted() || routine.getObjectId() <= 0) {
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
