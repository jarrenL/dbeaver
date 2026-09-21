/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.dbeaver.debug.DBGController;
import org.jkiss.dbeaver.debug.DBGControllerFactory;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;

import java.util.Map;

public class GaussDBDebugControllerFactory implements DBGControllerFactory {
    @Override
    public DBGController createController(DBPDataSourceContainer dataSource, Map<String, Object> context) {
        return new GaussDBDebugController(dataSource, context);
    }
}
