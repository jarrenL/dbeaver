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
package org.jkiss.dbeaver.debug.ui;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.ISourceLocator;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.debug.ui.sourcelookup.ISourceDisplay;
import org.eclipse.debug.ui.sourcelookup.ISourceLookupResult;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IPageListener;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.debug.core.model.DatabaseStackFrame;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.ui.UIUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Owns the request through the final synchronous Eclipse source-display call. */
final class DatabaseSourceDisplay implements ISourceDisplay, IDebugEventSetListener {
    private static final Log log = Log.getLog(DatabaseSourceDisplay.class);
    private final Map<IWorkbenchPage, PageSourceDisplay> pages = new ConcurrentHashMap<>();

    DatabaseSourceDisplay() {
        DebugPlugin.getDefault().addDebugEventListener(this);
    }

    @Override
    public void displaySource(Object context, IWorkbenchPage page, boolean force) {
        if (!(context instanceof DatabaseStackFrame frame)) {
            return;
        }
        Runnable start = () -> {
            if (page.getWorkbenchWindow() == null || page.getWorkbenchWindow().getShell().isDisposed()) {
                return;
            }
            pages.computeIfAbsent(page, PageSourceDisplay::new).start(frame, force);
        };
        if (Display.getCurrent() != null) {
            start.run();
        } else {
            UIUtils.asyncExec(start);
        }
    }

    @Override
    public void handleDebugEvents(DebugEvent[] events) {
        for (DebugEvent event : events) {
            if (event.getKind() != DebugEvent.RESUME && event.getKind() != DebugEvent.TERMINATE
                && event.getKind() != DebugEvent.SUSPEND) {
                continue;
            }
            if (event.getSource() instanceof IDebugElement element) {
                for (PageSourceDisplay page : pages.values()) {
                    if (page.target == element.getDebugTarget()) {
                        page.state.executionEvent(event.getKind());
                    }
                }
            }
        }
    }

    private final class PageSourceDisplay implements IPartListener2, IPageListener, Listener {
        private final IWorkbenchPage page;
        private final Display display;
        private final DebugSourceDisplayState state = new DebugSourceDisplayState();
        private final DatabaseDebugModelPresentation sourcePresentation;
        private volatile Object target;
        private DatabaseStackFrame frame;
        private boolean displaying;

        private PageSourceDisplay(IWorkbenchPage page) {
            this.page = page;
            this.display = page.getWorkbenchWindow().getShell().getDisplay();
            this.sourcePresentation = new DatabaseDebugModelPresentation();
            page.addPartListener(this);
            page.getWorkbenchWindow().addPageListener(this);
            display.addFilter(SWT.MouseDown, this);
            display.addFilter(SWT.KeyDown, this);
        }

        private void start(DatabaseStackFrame nextFrame, boolean force) {
            if (nextFrame.isTerminated() || !nextFrame.isSuspended()) {
                return;
            }
            if (target != nextFrame.getDebugTarget()) {
                target = nextFrame.getDebugTarget();
                state.executionChanged();
            }
            IWorkbenchPart selectedPart = page.getActivePart();
            boolean debugViewActive = selectedPart != null && selectedPart.getSite() != null
                && IDebugUIConstants.ID_DEBUG_VIEW.equals(selectedPart.getSite().getId());
            long request = state.begin(force, debugViewActive);
            if (request == 0) {
                return;
            }
            this.frame = nextFrame;
            Job lookup = new Job("Database debug source lookup") {
                @Override
                protected IStatus run(IProgressMonitor monitor) {
                    if (!state.isCurrent(request) || nextFrame.isTerminated() || !nextFrame.isSuspended()) {
                        return Status.CANCEL_STATUS;
                    }
                    DebugSourceDisplayState.LOOKUP_REQUEST.set(() -> state.isCurrent(request)
                        && !nextFrame.isTerminated() && nextFrame.isSuspended());
                    try {
                        ISourceLocator locator = nextFrame.getLaunch().getSourceLocator();
                        // Public lookupSource caches by artifact + locator. A fresh
                        // delegating identity preserves force without internal APIs.
                        ISourceLocator lookupLocator = force ? locator::getSourceElement : locator;
                        ISourceLookupResult found = DebugUITools.lookupSource(nextFrame, lookupLocator);
                        ISourceLookupResult result = state.prepareSource(request, found, () -> {
                            if (found.getSourceElement() instanceof DBNDatabaseNode node) {
                                // A cache hit skips the presentation. Reopening its
                                // source still needs the real document before Eclipse
                                // places the instruction pointer and highlights the line.
                                sourcePresentation.getEditorInput(node);
                            }
                        });
                        if (state.isCurrent(request) && !monitor.isCanceled()) {
                            UIUtils.asyncExec(() -> publish(request, nextFrame, result));
                        }
                        return Status.OK_STATUS;
                    } catch (OperationCanceledException e) {
                        return Status.CANCEL_STATUS;
                    } catch (Exception e) {
                        log.error("Error resolving database debug source", e);
                        return new Status(IStatus.ERROR, "org.jkiss.dbeaver.debug.ui", "Error resolving database debug source", e);
                    } finally {
                        DebugSourceDisplayState.LOOKUP_REQUEST.remove();
                    }
                }
            };
            lookup.setSystem(true);
            lookup.setPriority(Job.INTERACTIVE);
            lookup.schedule();
        }

        private void publish(long request, DatabaseStackFrame requestedFrame, ISourceLookupResult result) {
            if (!state.isCurrent(request) || frame != requestedFrame || requestedFrame.isTerminated()
                || !requestedFrame.isSuspended() || page.getWorkbenchWindow() == null
                || page.getWorkbenchWindow().getShell().isDisposed() || page.getWorkbenchWindow().getActivePage() != page) {
                return;
            }
            displaying = true;
            try {
                // This public API is synchronous. No unchecked Eclipse display job
                // remains queued after our final lifecycle/user-intent check.
                DebugUITools.displaySource(result, page);
            } finally {
                displaying = false;
            }
        }

        @Override
        public void handleEvent(Event event) {
            if (!displaying) {
                if (event.type == SWT.MouseDown) {
                    state.pointerInput(event.count);
                } else {
                    state.userInput();
                }
            }
        }

        @Override
        public void partClosed(IWorkbenchPartReference partRef) {
            if (!displaying) {
                state.userNavigation();
            }
        }

        @Override
        public void pageActivated(IWorkbenchPage activated) {
            if (activated != page) {
                state.userNavigation();
            }
        }

        @Override
        public void pageOpened(IWorkbenchPage opened) {
        }

        @Override
        public void pageClosed(IWorkbenchPage closed) {
            if (closed == page) {
                state.dispose();
                sourcePresentation.dispose();
                page.removePartListener(this);
                page.getWorkbenchWindow().removePageListener(this);
                if (!display.isDisposed()) {
                    display.removeFilter(SWT.MouseDown, this);
                    display.removeFilter(SWT.KeyDown, this);
                }
                pages.remove(page, this);
            }
        }
    }
}
