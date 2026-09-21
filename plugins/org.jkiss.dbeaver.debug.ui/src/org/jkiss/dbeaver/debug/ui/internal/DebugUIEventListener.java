/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package org.jkiss.dbeaver.debug.ui.internal;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.debug.DBGSession;
import org.jkiss.dbeaver.debug.DBGTransactionAction;
import org.jkiss.dbeaver.debug.core.model.IDatabaseDebugTarget;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.utils.RuntimeUtils;

import java.lang.reflect.InvocationTargetException;

public class DebugUIEventListener implements IDebugEventSetListener {

    private static final Log log = Log.getLog(DebugUIEventListener.class);

    @Override
    public void handleDebugEvents(DebugEvent[] events) {
        for (DebugEvent event : events) {
            switch (event.getKind()) {
                case DebugEvent.SUSPEND:
                    showDebugViews(true);
                    requestTransactionCompletion(event);
                    break;
                case DebugEvent.TERMINATE:
                    showDebugViews(false);
                    break;
            }
        }
    }

    private void requestTransactionCompletion(DebugEvent event) {
        if (!(event.getSource() instanceof IDebugElement debugElement) ||
            !(debugElement.getDebugTarget() instanceof IDatabaseDebugTarget target)) {
            return;
        }
        DBGSession session = target.getSession();
        if (session == null || !session.isTransactionCompletionPending()) {
            return;
        }
        UIUtils.asyncExec(() -> {
            int choice = new MessageDialog(
                UIUtils.getActiveWorkbenchShell(),
                "Complete debug transaction",
                null,
                "The debugged routine has finished. Commit or roll back its database changes?",
                MessageDialog.QUESTION,
                new String[] {"Commit", "Rollback"},
                1
            ).open();
            DBGTransactionAction action = choice == 0 ? DBGTransactionAction.COMMIT : DBGTransactionAction.ROLLBACK;
            try {
                RuntimeUtils.runTask(
                    monitor -> {
                        try {
                            session.completeTransaction(monitor, action);
                        } catch (DBGException e) {
                            throw new InvocationTargetException(e);
                        }
                    },
                    "Complete debug transaction",
                    20000
                );
                target.terminate();
            } catch (Exception e) {
                log.error("Error completing debug transaction", e);
            }
        });
    }

    private void showDebugViews(boolean show) {
        IWorkbenchWindow window = UIUtils.getActiveWorkbenchWindow();
        IWorkbenchPage activePage = window.getActivePage();

        UIUtils.asyncExec(() -> {
            try {
                if (show) {
                    activePage.showView(IDebugUIConstants.ID_VARIABLE_VIEW);
                    activePage.showView(IDebugUIConstants.ID_BREAKPOINT_VIEW);
                } else {
                    hideView(activePage, IDebugUIConstants.ID_VARIABLE_VIEW);
                    hideView(activePage, IDebugUIConstants.ID_BREAKPOINT_VIEW);
                }
            } catch (CoreException e) {
                log.log(e.getStatus());
            }
        });
    }

    private void hideView(IWorkbenchPage activePage, String viewId) {
        IViewPart view = activePage.findView(viewId);
        if (view != null) {
            activePage.hideView(view);
        }
    }
}
