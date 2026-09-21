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
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.debug.DBGSession;
import org.jkiss.dbeaver.debug.DBGTransactionAction;
import org.jkiss.dbeaver.debug.DBGTransactionCompletion;
import org.jkiss.dbeaver.debug.core.model.IDatabaseDebugTarget;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DebugUIEventListener implements IDebugEventSetListener {

    private static final Log log = Log.getLog(DebugUIEventListener.class);
    private final Set<DBGSession> completingTransactions = ConcurrentHashMap.newKeySet();

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
        if (session == null || !session.isTransactionCompletionPending() || !completingTransactions.add(session)) {
            return;
        }
        UIUtils.asyncExec(() -> {
            if (target.isTerminated() || target.getSession() != session || !session.isTransactionCompletionPending()) {
                completingTransactions.remove(session);
                return;
            }
            int choice = new MessageDialog(
                UIUtils.getActiveWorkbenchShell(),
                DebugUIMessages.DebugTransaction_title,
                null,
                DebugUIMessages.DebugTransaction_question,
                MessageDialog.QUESTION,
                new String[] {DebugUIMessages.DebugTransaction_commit, DebugUIMessages.DebugTransaction_rollback},
                1
            ).open();
            DBGTransactionAction action = choice == 0 ? DBGTransactionAction.COMMIT : DBGTransactionAction.ROLLBACK;
            // Do not use runTask's timed wait: it neither propagates task exceptions
            // nor stops the JDBC operation when the wait expires.
            AbstractJob job = new AbstractJob(DebugUIMessages.DebugTransaction_title) {
                @Override
                protected IStatus run(DBRProgressMonitor monitor) {
                    try {
                        if (target.isTerminated() || target.getSession() != session) {
                            return Status.CANCEL_STATUS;
                        }
                        DBGTransactionCompletion.complete(monitor, session, action, target::terminate);
                        return Status.OK_STATUS;
                    } catch (Exception e) {
                        log.error("Error completing debug transaction", e);
                        UIUtils.asyncExec(() -> DBWorkbench.getPlatformUI().showError(
                            DebugUIMessages.DebugTransaction_unconfirmed, DebugUIMessages.DebugTransaction_failure_details, e));
                        return new Status(IStatus.ERROR, "org.jkiss.dbeaver.debug.ui", "Debug transaction was not confirmed", e);
                    } finally {
                        completingTransactions.remove(session);
                    }
                }
            };
            job.setUser(true);
            job.schedule();
        });
    }

    private void showDebugViews(boolean show) {
        UIUtils.asyncExec(() -> {
            IWorkbenchWindow window = UIUtils.getActiveWorkbenchWindow();
            IWorkbenchPage activePage = window == null ? null : window.getActivePage();
            if (activePage == null) {
                return;
            }
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
