/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Copyright (C) 2017-2018 Alexander Fedorov (alexander.fedorov@jkiss.org)
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

package org.jkiss.dbeaver.debug.ui;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugModelPresentationExtension;
import org.eclipse.debug.ui.IValueDetailListener;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.model.WorkbenchLabelProvider;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.debug.core.DebugUtils;
import org.jkiss.dbeaver.debug.core.breakpoints.DatabaseLineBreakpoint;
import org.jkiss.dbeaver.debug.core.breakpoints.IDatabaseBreakpoint;
import org.jkiss.dbeaver.debug.core.model.*;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNModel;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UITask;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.entity.EntityEditor;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorNested;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerObjectOpen;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public class DatabaseDebugModelPresentation extends LabelProvider implements IDebugModelPresentationExtension {

    private static Log log = Log.getLog(DatabaseDebugModelPresentation.class);

    private final Map<String, Object> attributes = new HashMap<>();

    private final ILabelProvider labelProvider;

    private final AtomicLong sourceRequest = new AtomicLong();

    public DatabaseDebugModelPresentation() {
        this(WorkbenchLabelProvider.getDecoratingWorkbenchLabelProvider());
    }

    public DatabaseDebugModelPresentation(ILabelProvider labelProvider) {
        this.labelProvider = labelProvider;
    }

    public Object getAttribute(String attribute) {
        return attributes.get(attribute);
    }

    @Override
    public void setAttribute(String attribute, Object value) {
        attributes.put(attribute, value);
    }

    @Override
    public Image getImage(Object element) {
        return labelProvider.getImage(element);
    }

    @Override
    public String getText(Object element) {
        // FIXME:AF: register adapters
        try {
            if (element instanceof IDatabaseDebugTarget) {
                IDatabaseDebugTarget databaseDebugTarget = (IDatabaseDebugTarget) element;
                return databaseDebugTarget.getName();
            }
            if (element instanceof DatabaseProcess) {
                DatabaseProcess process = (DatabaseProcess) element;
                return process.getLabel();
            }
            if (element instanceof DatabaseThread) {
                DatabaseThread thread = (DatabaseThread) element;
                return thread.getName();
            }
            if (element instanceof DatabaseStackFrame) {
                DatabaseStackFrame stackFrame = (DatabaseStackFrame) element;
                return stackFrame.getName();
            }
            if (element instanceof DatabaseVariable) {
                DatabaseVariable variable = (DatabaseVariable) element;
                return variable.getName();
            }
            if (element instanceof DatabaseLineBreakpoint) {
                DatabaseLineBreakpoint breakpoint = (DatabaseLineBreakpoint) element;
                // The same schema/routine/line can exist on several connections. Include
                // the persisted identity even when a connection has been removed or is offline.
                return breakpoint.getDisplayLabel();
            }
        } catch (CoreException e) {
            return "<not responding>";
        }
        return labelProvider.getText(element);
    }

    @Override
    public void dispose() {
        sourceRequest.incrementAndGet();
        attributes.clear();
        super.dispose();
    }

    @Override
    public void computeDetail(IValue value, IValueDetailListener listener) {
        try {
            String valueString = value.getValueString();
            listener.detailComputed(value, valueString);
        } catch (DebugException e) {
            String message = NLS.bind("Unable to compute valie for {0}", value);
            IStatus status = DebugUtils.newErrorStatus(message, e);
            log.log(status);
            listener.detailComputed(value, e.getMessage());
        }
    }

    @Override
    public IEditorInput getEditorInput(Object element) {
        if (!DebugSourceDisplayState.currentLookup().getAsBoolean()) {
            throw new OperationCanceledException();
        }
        if (element instanceof DBNDatabaseNode) {
            DBNDatabaseNode databaseNode = (DBNDatabaseNode) element;
            return createEditorInput(databaseNode);
        }
        if (element instanceof IDatabaseBreakpoint) {
            IDatabaseBreakpoint breakpoint = (IDatabaseBreakpoint) element;
            try {
                String nodePath = breakpoint.getNodePath();
                DBNModel navigatorModel = DBWorkbench.getPlatform().getNavigatorModel();
                DBNNode node = navigatorModel.getNodeByPath(new VoidProgressMonitor(), nodePath);
                if (node instanceof DBNDatabaseNode) {
                    DBNDatabaseNode databaseNode = (DBNDatabaseNode) node;
                    DBGEditorAdvisor advisor = DebugUI.findEditorAdvisor(databaseNode.getDataSourceContainer());
                    // Eclipse queries this while merely selecting a breakpoint.
                    // Only the explicit Open action may open or activate its editor.
                    return DatabaseBreakpointEditorInput.create(databaseNode,
                        advisor == null ? null : advisor.getSourceFolderId(), breakpoint.getMarker());
                }
            } catch (OperationCanceledException e) {
                throw e;
            } catch (Exception e) {
                String message = NLS.bind("Unable to resolve editor input for breakpoint {0}", breakpoint);
                log.error(message, e);
            }
            
        }
        
        return null;
    }

    protected IEditorInput createEditorInput(DBNDatabaseNode dbnNode) {
        BooleanSupplier lookupCurrent = DebugSourceDisplayState.currentLookup();
        if (!lookupCurrent.getAsBoolean()) {
            throw new OperationCanceledException();
        }
        long requestId = sourceRequest.incrementAndGet();
        DBGEditorAdvisor editorAdvisor = DebugUI.findEditorAdvisor(dbnNode.getDataSourceContainer());
        String sourceFolderId = editorAdvisor == null ? null : editorAdvisor.getSourceFolderId();
        Map<String, Object> editorAttrs = new HashMap<>();
        editorAttrs.put(DBPScriptObject.OPTION_DEBUGGER_SOURCE, true);

        DebugSourceEditorRequest request = new UITask<DebugSourceEditorRequest>() {
            @Override
            protected DebugSourceEditorRequest runTask() {
                if (sourceRequest.get() != requestId || !lookupCurrent.getAsBoolean()) {
                    return null;
                }
                var window = UIUtils.getActiveWorkbenchWindow();
                if (window == null || window.getActivePage() == null) {
                    return null;
                }
                IWorkbenchPage page = window.getActivePage();
                IWorkbenchPart activePart = page.getActivePart();
                // Reusing the same source editor in a later launch must reload its
                // document even when OPTION_DEBUGGER_SOURCE is already true.
                // Keep the token stable while stepping within one launch.
                var context = DebugUITools.getDebugContext();
                IDebugElement debugElement = context instanceof IDebugElement element
                    ? element : context == null ? null : context.getAdapter(IDebugElement.class);
                if (debugElement != null && debugElement.getLaunch() != null) {
                    editorAttrs.put("debugger.source.launch", debugElement.getLaunch()); //$NON-NLS-1$
                }
                IEditorPart editor = NavigatorHandlerObjectOpen.openEntityEditor(
                    dbnNode, null, sourceFolderId, editorAttrs, window, false);
                return editor == null ? null : new DebugSourceEditorRequest(sourceRequest, requestId, page, activePart, editor);
            }
        }.execute();

        if (request == null) {
            throw new OperationCanceledException();
        }

        // Eclipse positions the instruction pointer immediately after this method
        // returns. On first open the nested editor still contains a loading
        // placeholder, so that position is otherwise lost when source arrives.
        // Source lookup runs off the UI thread; never wait on the display thread.
        try {
            if (Display.getCurrent() == null) {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < deadline) {
                    Boolean loaded = new UITask<Boolean>() {
                        @Override
                        protected Boolean runTask() {
                            if (!request.isCurrent() || !lookupCurrent.getAsBoolean()) {
                                return null;
                            }
                            SQLEditorNested<?> sourceEditor = request.editor.getAdapter(SQLEditorNested.class);
                            // Restored editors initially expose a lazy input and no
                            // nested source editor. Returning that input makes Eclipse
                            // open a second editor before restoration has completed.
                            return sourceEditor != null && sourceEditor.isDocumentLoaded();
                        }
                    }.execute();
                    if (loaded == null) {
                        throw new OperationCanceledException();
                    }
                    if (loaded) {
                        break;
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new OperationCanceledException();
                    }
                }
            }
            IEditorInput resolvedInput = new UITask<IEditorInput>() {
                @Override
                protected IEditorInput runTask() {
                    return request.isCurrent() && lookupCurrent.getAsBoolean() ? request.editor.getEditorInput() : null;
                }
            }.execute();
            if (resolvedInput == null) {
                throw new OperationCanceledException();
            }
            return resolvedInput;
        } finally {
            new UITask<Void>() {
                @Override
                protected Void runTask() {
                    request.page.removePartListener(request);
                    return null;
                }
            }.execute();
        }
    }

    @Override
    public String getEditorId(IEditorInput input, Object element) {
        return EntityEditor.ID;
    }

    @Override
    public boolean requiresUIThread(Object element) {
        return false;
    }

}
