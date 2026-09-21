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
package org.jkiss.dbeaver.ext.gaussdb.ui.actions;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISaveablePart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.ext.gaussdb.GaussDBConstants;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.gaussdb.ui.internal.GaussDBMessages;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBPackage;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBPackageCompileBatch;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBPackageCompileTarget;
import org.jkiss.dbeaver.model.exec.compile.DBCSourceHost;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.IRefreshablePart;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.utils.RuntimeUtils;

import java.util.ArrayList;
import java.util.List;

public class GaussDBPackageCompileHandler extends AbstractHandler {
    private static final Log log = Log.getLog(GaussDBPackageCompileHandler.class);
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchPart activePart = HandlerUtil.getActivePart(event);
        if (activePart instanceof ISaveablePart saveablePart && saveablePart.isDirty()) {
            UIUtils.showMessageBox(
                HandlerUtil.getActiveShell(event),
                "Save package",
                "Save the package source before compiling it.",
                SWT.ICON_WARNING
            );
            return null;
        }

        List<GaussDBPackage> packages = getSelectedPackages(event);
        if (packages.isEmpty()) {
            return null;
        }

        GaussDBPackageCompileTarget target = getTarget(event.getCommand().getId());
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        // A navigator command can run while the corresponding editor is dirty.
        for (var reference : HandlerUtil.getActiveWorkbenchWindow(event).getActivePage().getEditorReferences()) {
            IEditorPart editor = reference.getEditor(false);
            if (editor != null && editor.isDirty()
                && editor.getEditorInput() instanceof IDatabaseEditorInput input
                && packages.contains(input.getDatabaseObject())) {
                UIUtils.showMessageBox(HandlerUtil.getActiveShell(event), "Save package",
                    "Save the package source before compiling it.", SWT.ICON_WARNING);
                return null;
            }
        }

        // The generic progress service only sets a canceled flag during JDBC IO.
        // AbstractJob additionally cancels the monitor's active JDBC blocking object.
        AbstractJob job = new AbstractJob("Compile GaussDB package") {
            @Override
            protected IStatus run(DBRProgressMonitor monitor) {
                monitor.beginTask("Compile GaussDB package", packages.size());
                try {
                    GaussDBPackageCompileBatch.Result batch = GaussDBPackageCompileBatch.compile(monitor, packages, target);
                    if (batch.canceled() && batch.failure() != null) {
                        // Cancellation stays cancellation in the UI, but preserve a
                        // coincident failure (including driver cancellation details).
                        log.debug("Package compilation interrupted during cancellation", batch.failure());
                    }
                    List<GaussDBPackageCompileResultsDialog.Result> results = batch.diagnostics().stream()
                        .map(item -> new GaussDBPackageCompileResultsDialog.Result(item.object(), item.error())).toList();
                    UIUtils.asyncExec(() -> {
                        if (window.getShell().isDisposed() || window.getActivePage() == null) {
                            return;
                        }
                        if (batch.interrupted()) {
                            String summary = NLS.bind(GaussDBMessages.package_compile_partial_summary, new Object[]{
                                batch.completed(), batch.total(), batch.canceled() ? GaussDBMessages.package_compile_canceled
                                    : GaussDBMessages.package_compile_failed,
                                batch.stoppedAt() == null ? "-" : batch.stoppedAt().getName()});
                            if (batch.failure() != null && !batch.canceled()) {
                                DBWorkbench.getPlatformUI().showError(GaussDBMessages.package_compile_interrupted, summary, batch.failure());
                            } else {
                                UIUtils.showMessageBox(window.getShell(), GaussDBMessages.package_compile_interrupted, summary, SWT.ICON_WARNING);
                            }
                        }
                        showResults(window, packages, results, !batch.interrupted());
                    });
                    return batch.canceled() ? Status.CANCEL_STATUS : batch.failure() == null ? Status.OK_STATUS
                        : new Status(IStatus.ERROR, "org.jkiss.dbeaver.ext.gaussdb.ui", "Package compilation interrupted", batch.failure());
                } finally {
                    monitor.done();
                }
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }

    private void showResults(IWorkbenchWindow window, List<GaussDBPackage> packages,
        List<GaussDBPackageCompileResultsDialog.Result> results, boolean completed) {
        if (window.getShell().isDisposed() || window.getActivePage() == null) {
            return;
        }
        DBCSourceHost sourceHost = packages.size() == 1
            ? getSourceHost(window.getActivePage().getActiveEditor(), packages.get(0)) : null;
        // OBJECT_UPDATE refreshes the navigator icon, but property forms only
        // reload values on an explicit refresh. Never discard newly typed edits.
        for (var reference : window.getActivePage().getEditorReferences()) {
            IEditorPart editor = reference.getEditor(false);
            if (editor != null && !editor.isDirty()
                && editor.getEditorInput() instanceof IDatabaseEditorInput input
                && packages.contains(input.getDatabaseObject())
                && editor instanceof IRefreshablePart refreshable) {
                refreshable.refreshPart(this, true);
            }
        }
        if (!results.isEmpty()) {
            new GaussDBPackageCompileResultsDialog(window.getShell(), results).open();
        } else if (completed) {
            String message = packages.size() == 1
                ? packages.get(0).getName() + " compiled successfully"
                : packages.size() + " packages compiled successfully";
            if (sourceHost != null) {
                sourceHost.getCompileLog().clearLog();
                sourceHost.setCompileInfo(message, false);
            }
            UIUtils.showMessageBox(window.getShell(), "Compile package", message, SWT.ICON_INFORMATION);
        }
    }

    private static GaussDBPackageCompileTarget getTarget(String commandId) {
        if (GaussDBConstants.CMD_COMPILE_PACKAGE_SPECIFICATION.equals(commandId)) {
            return GaussDBPackageCompileTarget.SPECIFICATION;
        }
        if (GaussDBConstants.CMD_COMPILE_PACKAGE_BODY.equals(commandId)) {
            return GaussDBPackageCompileTarget.BODY;
        }
        return GaussDBPackageCompileTarget.ALL;
    }

    private static DBCSourceHost getSourceHost(IWorkbenchPart activePart, GaussDBPackage object) {
        if (activePart == null) {
            return null;
        }
        DBCSourceHost sourceHost = activePart instanceof DBCSourceHost host
            ? host : activePart.getAdapter(DBCSourceHost.class);
        return sourceHost != null && sourceHost.getSourceObject() == object ? sourceHost : null;
    }

    private static List<GaussDBPackage> getSelectedPackages(ExecutionEvent event) {
        List<GaussDBPackage> packages = new ArrayList<>();
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (selection instanceof IStructuredSelection structuredSelection) {
            for (Object item : structuredSelection.toList()) {
                DBSObject selectedObject = RuntimeUtils.getObjectAdapter(item, DBSObject.class);
                if (selectedObject instanceof GaussDBPackage object) {
                    packages.add(object);
                }
            }
        }
        if (packages.isEmpty()) {
            IEditorPart editor = HandlerUtil.getActiveEditor(event);
            if (editor != null && editor.getEditorInput() instanceof IDatabaseEditorInput input) {
                DBSObject object = input.getDatabaseObject();
                if (object instanceof GaussDBPackage gaussDBPackage) {
                    packages.add(gaussDBPackage);
                }
            }
        }
        return packages;
    }
}
