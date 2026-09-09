/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.eclipse.core.runtime.IAdapterFactory;
import org.jkiss.dbeaver.debug.DBGControllerFactory;
import org.jkiss.dbeaver.debug.DBGResolver;
import org.jkiss.dbeaver.ext.gaussdb.GaussDBDataSourceProvider;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;

public class GaussDBDebugAdapterFactory implements IAdapterFactory {
    private static final Class<?>[] ADAPTERS = {DBGControllerFactory.class, DBGResolver.class};

    @Override
    public <T> T getAdapter(Object adaptableObject, Class<T> adapterType) {
        if (!(adaptableObject instanceof DBPDataSourceContainer container) ||
            !"gaussdb".equals(container.getDriver().getProviderId())) {
            return null;
        }
        if (adapterType == DBGControllerFactory.class &&
            container.getDriver().getDataSourceProvider() instanceof GaussDBDataSourceProvider) {
            return adapterType.cast(new GaussDBDebugControllerFactory());
        }
        if (adapterType == DBGResolver.class) {
            return adapterType.cast(new GaussDBDebugResolver(container));
        }
        return null;
    }

    @Override
    public Class<?>[] getAdapterList() {
        return ADAPTERS;
    }
}
