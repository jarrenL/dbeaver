/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.ui.internal;

import org.eclipse.core.runtime.IAdapterFactory;
import org.jkiss.dbeaver.debug.ui.DBGEditorAdvisor;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;

public class GaussDBDebugUIAdapterFactory implements IAdapterFactory {
    private static final Class<?>[] ADAPTERS = {DBGEditorAdvisor.class};
    private final DBGEditorAdvisor advisor = new GaussDBSourceEditorAdvisor();

    @Override
    public <T> T getAdapter(Object adaptableObject, Class<T> adapterType) {
        if (adapterType == DBGEditorAdvisor.class && adaptableObject instanceof DBPDataSourceContainer container &&
            container.getDataSource() instanceof GaussDBDataSource &&
            "gaussdb".equals(container.getDriver().getProviderId())) {
            return adapterType.cast(advisor);
        }
        return null;
    }

    @Override
    public Class<?>[] getAdapterList() {
        return ADAPTERS;
    }
}
