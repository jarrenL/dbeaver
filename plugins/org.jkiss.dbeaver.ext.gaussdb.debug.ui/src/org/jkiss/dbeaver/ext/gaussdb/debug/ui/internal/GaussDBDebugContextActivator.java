/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.ui.internal;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;
import org.jkiss.dbeaver.debug.core.model.IDatabaseDebugTarget;
import org.jkiss.dbeaver.ui.UIUtils;

public class GaussDBDebugContextActivator implements IStartup, IDebugEventSetListener {
    private static final String CONTEXT_ID = "org.jkiss.dbeaver.ext.gaussdb.debug.context"; //$NON-NLS-1$
    private IContextActivation activation;

    @Override
    public void earlyStartup() {
        DebugPlugin.getDefault().addDebugEventListener(this);
    }

    @Override
    public void handleDebugEvents(DebugEvent[] events) {
        for (DebugEvent event : events) {
            if (!isGaussDBEvent(event)) {
                continue;
            }
            if (event.getKind() == DebugEvent.TERMINATE) {
                UIUtils.asyncExec(this::deactivate);
            } else if (event.getKind() == DebugEvent.CREATE || event.getKind() == DebugEvent.SUSPEND ||
                event.getKind() == DebugEvent.RESUME) {
                UIUtils.asyncExec(this::activate);
            }
        }
    }

    private static boolean isGaussDBEvent(DebugEvent event) {
        if (!(event.getSource() instanceof IDebugElement element) ||
            !(element.getDebugTarget() instanceof IDatabaseDebugTarget target)) {
            return false;
        }
        return "gaussdb".equals(target.getController().getDataSourceContainer().getDriver().getProviderId());
    }

    private void activate() {
        if (activation == null) {
            IContextService service = PlatformUI.getWorkbench().getService(IContextService.class);
            activation = service.activateContext(CONTEXT_ID);
        }
    }

    private void deactivate() {
        if (activation != null) {
            IContextService service = PlatformUI.getWorkbench().getService(IContextService.class);
            service.deactivateContext(activation);
            activation = null;
        }
    }
}
