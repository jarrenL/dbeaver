/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.app.config;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.osgi.util.NLS;
import org.jkiss.dbeaver.ui.app.config.nls.ProductConfigMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.utils.GeneralUtils;

public final class ProductConfigWizardHandler extends AbstractHandler {
    @Nullable
    @Override
    public Object execute(@NotNull ExecutionEvent event) throws ExecutionException {
        var dialog = new ProductConfigWizardDialog(
            HandlerUtil.getActiveWorkbenchWindow(event),
            ProductConfigWizard.Origin.BY_USER
        );
        if (dialog.open() != IDialogConstants.OK_ID) {
            return null;
        }
        if (dialog.isRestartRequired()) {
            confirmRestart(HandlerUtil.getActiveWorkbenchWindow(event));
        }
        return null;
    }

    public static void confirmRestart(@NotNull IWorkbenchWindow window) {
        if (UIUtils.confirmAction(window.getShell(),
            NLS.bind(ProductConfigMessages.restart_title, GeneralUtils.getProductName()),
            NLS.bind(ProductConfigMessages.restart_message, GeneralUtils.getProductName()))) {
            UIUtils.asyncExec(() -> window.getWorkbench().restart());
        }
    }
}
