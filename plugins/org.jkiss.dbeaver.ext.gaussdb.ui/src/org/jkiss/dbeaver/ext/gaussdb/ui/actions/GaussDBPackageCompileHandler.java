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
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISaveablePart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.gaussdb.GaussDBConstants;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBPackage;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBPackageCompileTarget;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBPackageCompiler;
import org.jkiss.dbeaver.model.exec.compile.DBCCompileError;
import org.jkiss.dbeaver.model.exec.compile.DBCCompileLog;
import org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase;
import org.jkiss.dbeaver.model.exec.compile.DBCSourceHost;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.dbeaver.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

public class GaussDBPackageCompileHandler extends AbstractHandler {
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
        DBCSourceHost sourceHost = packages.size() == 1 ? getSourceHost(activePart, packages.get(0)) : null;
        if (sourceHost == null && packages.size() == 1) {
            sourceHost = getSourceHost(HandlerUtil.getActiveEditor(event), packages.get(0));
        }
        DBCCompileLog compileLog = sourceHost == null ? new DBCCompileLogBase() : sourceHost.getCompileLog();
        compileLog.clearLog();

        try {
            UIUtils.runInProgressService(monitor -> {
                monitor.beginTask("Compile GaussDB package", packages.size());
                try {
                    for (GaussDBPackage object : packages) {
                        if (monitor.isCanceled()) {
                            break;
                        }
                        monitor.subTask(object.getFullyQualifiedName(org.jkiss.dbeaver.model.DBPEvaluationContext.UI));
                        try {
                            GaussDBPackageCompiler.compile(monitor, compileLog, object, target);
                        } catch (DBException e) {
                            throw new InvocationTargetException(e);
                        }
                        monitor.worked(1);
                    }
                } finally {
                    monitor.done();
                }
            });
        } catch (InterruptedException e) {
            return null;
        } catch (InvocationTargetException e) {
            DBWorkbench.getPlatformUI().showError(
                "GaussDB package compilation failed",
                null,
                e.getTargetException()
            );
            return null;
        }

        if (!CommonUtils.isEmpty(compileLog.getErrorStack())) {
            DBCCompileError firstError = compileLog.getErrorStack().iterator().next();
            StringBuilder message = new StringBuilder();
            for (DBCCompileError error : compileLog.getErrorStack()) {
                if (!message.isEmpty()) {
                    message.append(GeneralUtils.getDefaultLineSeparator());
                }
                message.append(error);
            }
            if (sourceHost != null && firstError.getLine() > 0) {
                sourceHost.positionSource(firstError.getLine(), Math.max(1, firstError.getPosition()));
                sourceHost.setCompileInfo(packages.get(0).getName() + " compilation failed", true);
                sourceHost.showCompileLog();
            } else {
                DBWorkbench.getPlatformUI().showError("GaussDB package compilation failed", message.toString());
            }
        } else {
            String message = packages.size() == 1
                ? packages.get(0).getName() + " compiled successfully"
                : packages.size() + " packages compiled successfully";
            if (sourceHost != null) {
                sourceHost.setCompileInfo(message, false);
            }
            UIUtils.showMessageBox(HandlerUtil.getActiveShell(event), "Compile package", message, SWT.ICON_INFORMATION);
        }
        return null;
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
