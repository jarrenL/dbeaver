/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.dbeaver.debug.DBGBaseController;
import org.jkiss.dbeaver.debug.DBGBreakpointDescriptor;
import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.util.Map;

public class GaussDBDebugController extends DBGBaseController {
    public GaussDBDebugController(DBPDataSourceContainer container, Map<String, Object> configuration) {
        super(container, configuration);
    }

    @Override
    public GaussDBDebugSession createSession(DBRProgressMonitor monitor, Map<String, Object> configuration) throws DBGException {
        GaussDBDebugSession session = new GaussDBDebugSession(monitor, this, configuration);
        try {
            session.attach(monitor);
            return session;
        } catch (DBGException e) {
            try {
                session.closeSession(monitor);
            } catch (DBGException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    @Override
    public DBGBreakpointDescriptor describeBreakpoint(Map<String, Object> attributes) {
        return GaussDBDebugBreakpointDescriptor.fromMap(attributes);
    }
}
