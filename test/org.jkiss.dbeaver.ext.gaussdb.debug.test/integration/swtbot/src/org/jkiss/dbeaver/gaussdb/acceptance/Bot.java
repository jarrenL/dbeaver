/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.gaussdb.acceptance;

import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swtbot.swt.finder.SWTBot;
import org.eclipse.swtbot.swt.finder.widgets.*;
import org.eclipse.swtbot.swt.finder.utils.SWTBotPreferences;
import org.eclipse.jface.bindings.keys.KeyStroke;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

/** Opt-in, test-only UI driver. Never included in a product feature. */
public final class Bot implements IStartup {
    private final Map<Integer, Widget> widgets = new LinkedHashMap<>();
    private Display display;
    private Path queue;
    private final Map<String, java.util.concurrent.CompletableFuture<String>> sourceRequests =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService sourceWorkers = java.util.concurrent.Executors.newCachedThreadPool(runnable -> {
        Thread worker = new Thread(runnable, "GaussDB source navigation acceptance");
        worker.setDaemon(true);
        return worker;
    });
    private org.jkiss.dbeaver.debug.ui.DatabaseDebugModelPresentation sourcePresentation;

    @Override
    public void earlyStartup() {
        String directory = System.getProperty("gaussdb.swtbot.queue");
        if (directory == null) {
            return;
        }
        queue = Path.of(directory);
        display = PlatformUI.getWorkbench().getDisplay();
        Thread worker = new Thread(this::run, "GaussDB SWTBot acceptance");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        SWTBotPreferences.TIMEOUT = 10000;
        SWTBotPreferences.KEYBOARD_LAYOUT = "EN_US";
        SWTBotPreferences.KEYBOARD_STRATEGY = "org.eclipse.swtbot.swt.finder.keyboard.SWTKeyboardStrategy";
        try {
            Files.createDirectories(queue);
            Files.writeString(queue.resolve("ready"), "SWTBot ready\n");
            while (!display.isDisposed()) {
                try (var paths = Files.list(queue)) {
                    for (Path path : paths.filter(p -> p.toString().endsWith(".cmd")).sorted().toList()) {
                        StringWriter result = new StringWriter();
                        try (PrintWriter out = new PrintWriter(result)) {
                            try {
                                for (String line : Files.readAllLines(path)) {
                                    if (!line.isBlank() && !line.startsWith("#")) {
                                        execute(line.split("\\t", -1), out);
                                    }
                                }
                                out.println("OK");
                            } catch (Throwable e) {
                                out.println("FAIL");
                                e.printStackTrace(out);
                            }
                        }
                        Files.writeString(Path.of(path + ".result"), result.toString());
                        Files.move(path, Path.of(path + ".done"));
                    }
                }
                Thread.sleep(200);
            }
        } catch (Exception e) {
            try {
                Files.writeString(queue.resolve("fatal"), e.toString());
            } catch (IOException ignored) {
                // The launcher also captures application diagnostics.
            }
        }
    }

    private void execute(String[] args, PrintWriter out) throws Exception {
        if (args[0].equals("launch-saved")) {
            var manager = org.eclipse.debug.core.DebugPlugin.getDefault().getLaunchManager();
            var configurations = Arrays.stream(manager.getLaunchConfigurations())
                .filter(configuration -> configuration.getName().equals(args[1])).toList();
            if (configurations.size() != 1) {
                throw new IllegalArgumentException("Expected one saved launch named " + args[1] + ", got " + configurations.size());
            }
            var configuration = configurations.get(0);
            display.asyncExec(() -> org.eclipse.debug.ui.DebugUITools.launch(configuration, "debug", false));
            out.println("SCHEDULED saved launch=" + configuration.getName());
            return;
        }
        if (args[0].equals("launch-state")) {
            var launches = org.eclipse.debug.core.DebugPlugin.getDefault().getLaunchManager().getLaunches();
            int targets = 0;
            int processes = 0;
            for (var launch : launches) {
                String name = launch.getLaunchConfiguration() == null ? "none" : launch.getLaunchConfiguration().getName();
                out.println("launch name=" + name + " terminated=" + launch.isTerminated());
                for (var target : launch.getDebugTargets()) {
                    out.println("target name=" + target.getName() + " terminated=" + target.isTerminated()
                        + " suspended=" + target.isSuspended());
                    if (!target.isTerminated()) targets++;
                }
                for (var process : launch.getProcesses()) {
                    out.println("process label=" + process.getLabel() + " terminated=" + process.isTerminated());
                    if (!process.isTerminated()) processes++;
                }
            }
            out.println("live-targets=" + targets + " live-processes=" + processes);
            return;
        }
        if (args[0].equals("editor-state")) {
            display.syncExec(() -> {
                var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                if (args.length > 1 && args[1].equals("close-active")) {
                    var editor = page.getActiveEditor();
                    out.println("closed-active=" + (editor != null && page.closeEditor(editor, false)));
                } else if (args.length > 1 && args[1].equals("close-all")) {
                    out.println("closed-all=" + page.closeAllEditors(false));
                } else if (args.length > 1 && args[1].equals("breakpoints")) {
                    try {
                        page.showView("org.eclipse.debug.ui.BreakpointView");
                    } catch (org.eclipse.ui.PartInitException e) {
                        throw new IllegalStateException(e);
                    }
                }
                var active = page.getActivePart();
                out.println("activePart=" + (active == null ? "none" : active.getSite().getId() + "/" + active.getTitle()));
                var activeEditor = page.getActiveEditor();
                out.println("activeEditor=" + (activeEditor == null ? "none" : activeEditor.getClass().getName() + "/" + activeEditor.getTitle()));
                for (var reference : page.getEditorReferences()) {
                    out.println("editor id=" + reference.getId() + " name=" + reference.getName()
                        + " restored=" + (reference.getEditor(false) != null));
                }
                out.println("editor-count=" + page.getEditorReferences().length);
            });
            return;
        }
        if (args[0].equals("source-position") || args[0].equals("source-caret")) {
            inspectSourcePosition(args.length > 1 ? Integer.parseInt(args[1]) : -1,
                args[0].equals("source-position"), out);
            return;
        }
        if (args[0].equals("toggle-source-breakpoint")) {
            Throwable[] failure = new Throwable[1];
            display.syncExec(() -> {
                try {
                    var editor = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
                    Class<?> textType = org.eclipse.core.runtime.Platform.getBundle("org.eclipse.ui.workbench.texteditor")
                        .loadClass("org.eclipse.ui.texteditor.ITextEditor");
                    Object nested = editor.getAdapter(textType);
                    Object provider = textType.getMethod("getDocumentProvider").invoke(nested);
                    var providerType = textType.getMethod("getDocumentProvider").getReturnType();
                    Object input = textType.getMethod("getEditorInput").invoke(nested);
                    var documentMethod = providerType.getMethod("getDocument", Object.class);
                    Object document = documentMethod.invoke(provider, input);
                    Class<?> documentType = documentMethod.getReturnType();
                    int line = Integer.parseInt(args[1]);
                    int offset = ((Number) documentType.getMethod("getLineOffset", int.class).invoke(document, line - 1)).intValue();
                    Class<?> selectionType = org.eclipse.core.runtime.Platform.getBundle("org.eclipse.jface.text")
                        .loadClass("org.eclipse.jface.text.TextSelection");
                    var selection = (org.eclipse.jface.viewers.ISelection) selectionType
                        .getConstructor(documentType, int.class, int.class).newInstance(document, offset, 0);
                    var target = editor.getAdapter(org.eclipse.debug.ui.actions.IToggleBreakpointsTarget.class);
                    if (target == null) throw new AssertionError("Source editor has no production breakpoint toggle target");
                    target.toggleLineBreakpoints(editor, selection);
                    out.println("PASS production breakpoint toggle at source line " + line);
                } catch (Throwable e) { failure[0] = e; }
            });
            if (failure[0] != null) throw new AssertionError("Cannot toggle real source breakpoint", failure[0]);
            return;
        }
        if (args[0].equals("breakpoint-snapshot")) {
            var breakpoints = org.eclipse.debug.core.DebugPlugin.getDefault().getBreakpointManager()
                .getBreakpoints(org.jkiss.dbeaver.debug.DBGConstants.MODEL_IDENTIFIER_DATABASE);
            for (var breakpoint : breakpoints) {
                var database = (org.jkiss.dbeaver.debug.core.breakpoints.DatabaseLineBreakpoint) breakpoint;
                out.println("breakpoint " + database.getDisplayLabel() + " path=" + database.getNodePath());
            }
            out.println("breakpoint-count=" + breakpoints.length);
            return;
        }
        if (args[0].equals("source-request")) {
            startSourceRequest(args, out);
            return;
        }
        if (args[0].equals("source-status")) {
            var request = sourceRequests.get(args[1]);
            if (request == null) throw new IllegalArgumentException("No source request " + args[1]);
            out.println("source-request=" + args[1] + " done=" + request.isDone());
            if (request.isDone()) {
                try {
                    out.println("source-result=" + request.join());
                } catch (java.util.concurrent.CompletionException e) {
                    Throwable cause = e;
                    while (cause.getCause() != null) cause = cause.getCause();
                    out.println("source-error=" + cause.getClass().getName() + ": " + cause.getMessage());
                }
            }
            return;
        }
        if (args[0].equals("display-frame")) {
            display.asyncExec(() -> {
                var context = org.eclipse.debug.ui.DebugUITools.getDebugContext();
                if (!(context instanceof org.eclipse.debug.core.model.IStackFrame frame)) {
                    throw new IllegalStateException("Select a paused frame first");
                }
                var sourceDisplay = frame.getAdapter(org.eclipse.debug.ui.sourcelookup.ISourceDisplay.class);
                if (sourceDisplay == null) throw new IllegalStateException("Paused frame has no Eclipse source display adapter");
                sourceDisplay.displaySource(frame, PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), false);
            });
            out.println("SCHEDULED full Eclipse source display for selected frame");
            return;
        }
        if (args[0].equals("diagnostics")) {
            display.syncExec(() -> {
                try {
                    Object editor = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
                    Object provider = editor.getClass().getMethod("getDocumentProvider").invoke(editor);
                    Object input = editor.getClass().getMethod("getEditorInput").invoke(editor);
                    var modelMethod = Arrays.stream(provider.getClass().getMethods())
                        .filter(m -> m.getName().equals("getAnnotationModel") && m.getParameterCount() == 1)
                        .findFirst().orElseThrow();
                    Object model = modelMethod.invoke(provider, input);
                    Iterator<?> annotations = (Iterator<?>) model.getClass().getMethod("getAnnotationIterator").invoke(model);
                    int count = 0;
                    while (annotations.hasNext()) {
                        Object annotation = annotations.next();
                        if (!annotation.getClass().getSimpleName().equals("SQLSemanticErrorAnnotation")) {
                            continue;
                        }
                        var positionMethod = Arrays.stream(model.getClass().getMethods())
                            .filter(m -> m.getName().equals("getPosition") && m.getParameterCount() == 1)
                            .findFirst().orElseThrow();
                        Object position = positionMethod.invoke(model, annotation);
                        out.println("diagnostic offset=" + position.getClass().getMethod("getOffset").invoke(position)
                            + " length=" + position.getClass().getMethod("getLength").invoke(position)
                            + " severity=" + annotation.getClass().getMethod("getProblemMarkerSeverity").invoke(annotation)
                            + " text=" + annotation.getClass().getMethod("getText").invoke(annotation));
                        count++;
                    }
                    out.println("diagnostic-count=" + count);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            return;
        }
        if (args[0].equals("folding")) {
            display.syncExec(() -> {
                try {
                    Object editor = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
                    Object model = editor.getClass().getMethod("getProjectionAnnotationModel").invoke(editor);
                    if (model == null) {
                        throw new IllegalStateException("Active editor has no projection model");
                    }
                    Iterator<?> annotations = (Iterator<?>) model.getClass().getMethod("getAnnotationIterator").invoke(model);
                    int count = 0;
                    while (annotations.hasNext()) {
                        Object annotation = annotations.next();
                        var positionMethod = Arrays.stream(model.getClass().getMethods())
                            .filter(method -> method.getName().equals("getPosition") && method.getParameterCount() == 1)
                            .findFirst().orElseThrow();
                        Object position = positionMethod.invoke(model, annotation);
                        out.println("fold offset=" + position.getClass().getMethod("getOffset").invoke(position)
                            + " length=" + position.getClass().getMethod("getLength").invoke(position)
                            + " collapsed=" + annotation.getClass().getMethod("isCollapsed").invoke(annotation));
                        count++;
                    }
                    out.println("fold-count=" + count);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            return;
        }
        if (args[0].equals("breakpoint-fixture")) {
            String connection = "synthetic-breakpoint-fixture-a";
            String secondConnection = "synthetic-breakpoint-fixture-b";
            var project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot()
                .getProject("synthetic-breakpoint-fixture");
            var manager = org.eclipse.debug.core.DebugPlugin.getDefault().getBreakpointManager();
            if (args[1].equals("setup")) {
                if (project.exists()) throw new IllegalStateException("Fixture already exists");
                project.create(null);
                project.open(null);
                for (int index = 0; index < 3; index++) {
                    var marker = project.createMarker(org.jkiss.dbeaver.debug.DBGConstants.BREAKPOINT_ID_DATABASE_LINE);
                    var attributes = breakpointFixtureDescriptor(8201, 6, "synthetic_db").toMap();
                    attributes.put(org.eclipse.core.resources.IMarker.LINE_NUMBER, 6);
                    attributes.put(org.jkiss.dbeaver.debug.DBGConstants.BREAKPOINT_ATTRIBUTE_DATASOURCE_ID,
                        index == 2 ? secondConnection : connection);
                    attributes.put(org.jkiss.dbeaver.debug.DBGConstants.BREAKPOINT_ATTRIBUTE_NODE_PATH, "restored/path/" + index);
                    attributes.put(org.jkiss.dbeaver.debug.DBGConstants.BREAKPOINT_ATTRIBUTE_OBJECT_NAME, "synthetic.same_name");
                    attributes.put(org.eclipse.debug.core.model.IBreakpoint.ID, org.jkiss.dbeaver.debug.DBGConstants.MODEL_IDENTIFIER_DATABASE);
                    attributes.put(org.eclipse.debug.core.model.IBreakpoint.ENABLED, index != 1);
                    marker.setAttributes(attributes);
                    var breakpoint = new org.jkiss.dbeaver.debug.core.breakpoints.DatabaseLineBreakpoint();
                    var setter = org.eclipse.debug.core.model.Breakpoint.class.getDeclaredMethod(
                        "setMarker", org.eclipse.core.resources.IMarker.class);
                    setter.setAccessible(true);
                    setter.invoke(breakpoint, marker);
                    breakpoint.setBreakpointLabel("synthetic.same_name - [line:6]");
                    manager.addBreakpoint(breakpoint);
                }
            }
            var all = manager.getBreakpoints(org.jkiss.dbeaver.debug.DBGConstants.MODEL_IDENTIFIER_DATABASE);
            if (args[1].equals("clear-matches")) {
                var matches = org.jkiss.dbeaver.debug.core.breakpoints.DatabaseBreakpoint.findMatchingBreakpoints(
                    all, connection, "current/path", 6,
                    breakpointFixtureDescriptor(8201, -1, "synthetic_db"));
                if (matches.length != 2) throw new AssertionError("Expected two restored matching markers, got " + matches.length);
                display.asyncExec(() -> {
                    try {
                        org.eclipse.debug.ui.DebugUITools.deleteBreakpoints(matches,
                            PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), null);
                    } catch (org.eclipse.core.runtime.CoreException e) {
                        throw new IllegalStateException(e);
                    }
                });
                out.println("PASS selected both duplicate markers for the production UI delete action");
            } else if (args[1].equals("cleanup")) {
                for (var breakpoint : all) {
                    if (breakpoint.getMarker() != null && project.equals(breakpoint.getMarker().getResource())) {
                        manager.removeBreakpoint(breakpoint, true);
                    }
                }
                if (project.exists()) project.delete(true, true, null);
                out.println("PASS removed synthetic fixture only");
                return;
            }
            if (!args[1].equals("clear-matches")) {
                int count = 0;
                for (var breakpoint : all) {
                    if (breakpoint.getMarker() != null && project.equals(breakpoint.getMarker().getResource())) {
                        // Eclipse's BreakpointLabelProvider prefers this persisted-label path.
                        String label = ((org.jkiss.dbeaver.debug.core.breakpoints.DatabaseLineBreakpoint) breakpoint).getBreakpointLabel();
                        if (!label.contains("synthetic-breakpoint-fixture-")) throw new AssertionError(label);
                        if (!label.endsWith("synthetic.same_name - [line:6]")) throw new AssertionError(label);
                        out.println("marker " + label);
                        count++;
                    }
                }
                int expected = Integer.parseInt(args[2]);
                if (count != expected) throw new AssertionError("Expected " + expected + " markers, got " + count);
                out.println("PASS synthetic marker count=" + count);
            }
            return;
        }
        if (args[0].equals("debug-snapshot")) {
            Object[] context = new Object[2];
            display.syncExec(() -> {
                context[0] = org.eclipse.debug.ui.DebugUITools.getDebugContext();
                var editor = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
                context[1] = editor == null ? "none" : editor.getClass().getName();
            });
            out.println("source-editor=" + context[1]);
            if (String.valueOf(context[1]).contains("SourceNotFound")) throw new AssertionError("Source lookup failed");
            if (!(context[0] instanceof org.eclipse.debug.core.model.IStackFrame frame)) {
                throw new AssertionError("Select a paused stack frame first: " + context[0]);
            }
            out.println("frame=" + frame.getName());
            var sourceDisplay = frame.getAdapter(org.eclipse.debug.ui.sourcelookup.ISourceDisplay.class);
            out.println("source-adapter=" + (sourceDisplay == null ? "none" : sourceDisplay.getClass().getName()));
            if (sourceDisplay == null || !sourceDisplay.getClass().getName().equals("org.jkiss.dbeaver.debug.ui.DatabaseSourceDisplay")) {
                throw new AssertionError("Database frame did not resolve its guarded source-display adapter");
            }
            for (var variable : frame.getVariables()) {
                out.println("variable " + variable.getName() + "=" + variable.getValue().getValueString());
            }
            out.println("PASS selected frame source and variables available");
            return;
        }
        if (args[0].equals("open-debug-configurations")) {
            display.asyncExec(() -> org.eclipse.debug.ui.DebugUITools.openLaunchConfigurationDialogOnGroup(
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                (org.eclipse.jface.viewers.IStructuredSelection) null, "org.jkiss.dbeaver.debug.launchGroup"));
            return;
        }
        if (args[0].equals("open-run-configurations") || args[0].equals("open-standard-debug-configurations")) {
            String group = args[0].equals("open-run-configurations")
                ? org.eclipse.debug.ui.IDebugUIConstants.ID_RUN_LAUNCH_GROUP
                : org.eclipse.debug.ui.IDebugUIConstants.ID_DEBUG_LAUNCH_GROUP;
            display.asyncExec(() -> org.eclipse.debug.ui.DebugUITools.openLaunchConfigurationDialogOnGroup(
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                (org.eclipse.jface.viewers.IStructuredSelection) null, group));
            return;
        }
        if (args[0].equals("parameter-fixture")) {
            display.syncExec(() -> {
                try {
                    Class<?> type = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.ext.gaussdb.debug.ui")
                        .loadClass("org.jkiss.dbeaver.ext.gaussdb.debug.ui.internal.GaussDBDebugPanelRoutine");
                    Class<?> containerType = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.debug.ui")
                        .loadClass("org.jkiss.dbeaver.debug.ui.DBGConfigurationPanelContainer");
                    Object container = java.lang.reflect.Proxy.newProxyInstance(containerType.getClassLoader(),
                        new Class<?>[]{containerType}, (proxy, method, values) -> null);
                    Object panel = type.getConstructor().newInstance();
                    Shell shell = new Shell(display, SWT.SHELL_TRIM);
                    shell.setText("GaussDB parameter widget acceptance (synthetic row)");
                    shell.setLayout(new org.eclipse.swt.layout.GridLayout(1, false));
                    type.getMethod("createPanel", Composite.class, containerType).invoke(panel, shell, container);
                    var field = type.getDeclaredField("parametersTable"); field.setAccessible(true);
                    Table table = (Table) field.get(panel);
                    TableItem item = new TableItem(table, SWT.NONE);
                    item.setText(new String[]{"p", "", "integer", "Value"});
                    shell.setSize(780, 360); shell.open();
                    out.println("Production panel; synthetic parameter row; no database/launch lifecycle claim");
                } catch (Exception e) { throw new IllegalStateException(e); }
            });
            return;
        }
        if (args[0].equals("dump")) {
            display.syncExec(() -> {
                widgets.clear();
                for (Shell shell : display.getShells()) {
                    if (shell.isVisible()) {
                        dump(shell, "", out);
                    }
                }
            });
            return;
        }
        if (args[0].equals("screenshot")) {
            new SWTBot().captureScreenshot(args[1]);
            return;
        }
        if (args[0].equals("view")) {
            display.syncExec(() -> {
                try {
                    PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().showView(args[1]);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            return;
        }
        Widget widget = widgets.get(Integer.parseInt(args[1]));
        if (widget == null || widget.isDisposed()) {
            throw new IllegalStateException("Stale widget; take a fresh dump");
        }
        int[] style = new int[1];
        display.syncExec(() -> style[0] = widget.getStyle());
        switch (args[0]) {
            case "maximize" -> display.syncExec(() -> ((Shell) widget).setMaximized(true));
            case "physical-click" -> {
                display.syncExec(() -> {
                    org.eclipse.swt.graphics.Point location;
                    if (widget instanceof CTabItem item) {
                        var bounds = item.getBounds();
                        location = item.getParent().toDisplay(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
                    } else if (widget instanceof TreeItem item) {
                        var bounds = item.getBounds();
                        location = item.getParent().toDisplay(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
                    } else if (widget instanceof Control control) {
                        var size = control.getSize();
                        location = control.toDisplay(size.x / 2, size.y / 2);
                    } else {
                        throw new IllegalArgumentException("No screen bounds for " + widget.getClass().getName());
                    }
                    Event move = new Event();
                    move.type = SWT.MouseMove;
                    move.x = location.x;
                    move.y = location.y;
                    if (!display.post(move)) throw new IllegalStateException("Cannot post native pointer movement");
                    for (int type : new int[] {SWT.MouseDown, SWT.MouseUp}) {
                        Event event = new Event();
                        event.type = type;
                        event.button = 1;
                        event.count = 1;
                        if (!display.post(event)) throw new IllegalStateException("Cannot post native pointer input");
                    }
                });
                Thread.sleep(150);
            }
            case "click" -> {
                if (widget instanceof Button w) {
                    if ((style[0] & SWT.CHECK) != 0) new SWTBotCheckBox(w).click();
                    else if ((style[0] & SWT.RADIO) != 0) new SWTBotRadio(w).click();
                    else new SWTBotButton(w).click();
                } else if (widget instanceof ToolItem w) {
                    if ((style[0] & SWT.DROP_DOWN) != 0) new SWTBotToolbarDropDownButton(w).click();
                    else if ((style[0] & SWT.CHECK) != 0) new SWTBotToolbarToggleButton(w).click();
                    else new SWTBotToolbarPushButton(w).click();
                }
                else if (widget instanceof MenuItem w) new SWTBotMenu(w).click();
                else if (widget instanceof CTabItem w) new SWTBotCTabItem(w).activate();
                else if (widget instanceof TreeItem w) new SWTBotTreeItem(w).select();
                else if (widget instanceof Control w) new CustomControl(w).click();
                else throw new IllegalArgumentException(widget.getClass().getName());
            }
            case "expand" -> new SWTBotTreeItem((TreeItem) widget).expand();
            case "double" -> new SWTBotTreeItem((TreeItem) widget).doubleClick();
            case "check" -> new SWTBotTreeItem((TreeItem) widget).check();
            case "uncheck" -> new SWTBotTreeItem((TreeItem) widget).uncheck();
            case "text" -> new SWTBotText((Text) widget).setText(args[2]);
            case "source" -> new SWTBotStyledText((StyledText) widget).setText(Files.readString(Path.of(args[2])));
            case "select" -> new SWTBotTree((Tree) widget).select(java.util.Arrays.stream(args[2].split("\\|"))
                .map(id -> new SWTBotTreeItem((TreeItem) widgets.get(Integer.parseInt(id))))
                .toArray(SWTBotTreeItem[]::new));
            case "secret" -> new SWTBotText((Text) widget).setText(Files.readString(Path.of(args[2])).strip());
            case "combo" -> new SWTBotCombo((Combo) widget).setSelection(args[2]);
            case "key" -> {
                Shell[] shell = new Shell[1];
                display.syncExec(() -> shell[0] = widget instanceof Shell s ? s : ((Control) widget).getShell());
                KeyStroke stroke = KeyStroke.getInstance(args[2]);
                new SWTBotShell(shell[0]).activate();
                // macOS 26 requires TISGetInputSourceProperty (called by Display.post)
                // on the main dispatch queue. SWTBot's default worker-thread posting traps.
                var keyboard = new org.eclipse.swtbot.swt.finder.keyboard.Keyboard(
                    new org.eclipse.swtbot.swt.finder.keyboard.AbstractKeyboardStrategy() {
                        @Override
                        protected void pressKey(KeyStroke key) { postKey(key, SWT.KeyDown); }
                        @Override
                        protected void releaseKey(KeyStroke key) { postKey(key, SWT.KeyUp); }
                    });
                keyboard.pressShortcut(stroke.getModifierKeys(), stroke.getNaturalKey(), (char) 0);
            }
            case "close" -> new SWTBotShell((Shell) widget).close();
            case "context" -> new SWTBotTreeItem((TreeItem) widget).contextMenu(args[2]).click();
            case "context-selection" -> new SWTBotTree((Tree) widget).contextMenu(args[2]).click();
            case "dropdown" -> new SWTBotToolbarDropDownButton((ToolItem) widget).menuItem(args[2]).click();
            case "model" -> inspectModel((TreeItem) widget, out);
            case "object-actions" -> display.syncExec(() -> {
                try {
                    Object node = ((TreeItem) widget).getData();
                    Object object = node.getClass().getMethod("getObject").invoke(node);
                    var model = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.model");
                    Class<?> objectType = model.loadClass("org.jkiss.dbeaver.model.struct.DBSObject");
                    Class<?> nodeType = model.loadClass("org.jkiss.dbeaver.model.navigator.DBNNode");
                    out.println("objectClass=" + object.getClass().getName());
                    out.println("parentClass=" + node.getClass().getMethod("getParentNode").invoke(node).getClass().getName());
                    out.println("objectReadOnly=" + model.loadClass("org.jkiss.dbeaver.model.DBUtils")
                        .getMethod("isReadOnly", objectType).invoke(null, object));
                    out.println("nodeReadOnly=" + model.loadClass("org.jkiss.dbeaver.model.navigator.DBNUtils")
                        .getMethod("isReadOnly", nodeType).invoke(null, node));
                    var registry = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.registry")
                        .loadClass("org.jkiss.dbeaver.registry.ObjectManagerRegistry");
                    Object manager = registry.getMethod("getObjectManager", Class.class)
                        .invoke(registry.getMethod("getInstance").invoke(null), object.getClass());
                    out.println("manager=" + (manager == null ? "null" : manager.getClass().getName()));
                    if (manager != null) {
                        var maker = model.loadClass("org.jkiss.dbeaver.model.edit.DBEObjectMaker");
                        out.println("maker=" + maker.isInstance(manager));
                        if (maker.isInstance(manager)) out.println("managerCanDelete=" + maker
                            .getMethod("canDeleteObject", objectType).invoke(manager, object));
                    }
                    Class<?> tester = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.ui.navigator")
                        .loadClass("org.jkiss.dbeaver.ui.actions.ObjectPropertyTester");
                    out.println("metadataChangeDisabled=" + tester.getMethod("isMetadataChangeDisabled",
                        model.loadClass("org.jkiss.dbeaver.model.navigator.DBNDatabaseNode")).invoke(null, node));
                    out.println("canDelete=" + tester.getMethod("test", Object.class, String.class, Object[].class, Object.class)
                        .invoke(tester.getConstructor().newInstance(), node, "canDelete", new Object[0], null));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Read-only object-action diagnostic failed", e);
                }
            });
            case "modes" -> inspectModes((TreeItem) widget, out);
            case "navigation-race" -> navigationRace((Shell) widget, args[2], out);
            case "resolve-frame" -> resolveFrame((TreeItem) widget, Long.parseLong(args[2]), args[3], false, out);
            case "resolve-missing" -> resolveFrame((TreeItem) widget, Long.parseLong(args[2]), "", true, out);
            case "metadata-load" -> metadataLoad((TreeItem) widget, args, out);
            case "metadata-many-schemas" -> metadataManySchemas((TreeItem) widget, args, out);
            case "line" -> new SWTBotStyledText((StyledText) widget).navigateTo(Integer.parseInt(args[2]), 0);
            case "cell" -> new SWTBotTable((Table) widget).doubleClick(Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            case "grid-click" -> {
                org.eclipse.swt.graphics.Rectangle[] bounds = new org.eclipse.swt.graphics.Rectangle[1];
                display.syncExec(() -> {
                    try {
                        bounds[0] = (org.eclipse.swt.graphics.Rectangle) widget.getClass()
                            .getMethod("getCellBounds", int.class, int.class)
                            .invoke(widget, Integer.parseInt(args[3]), Integer.parseInt(args[2]));
                        if (bounds[0].x < 0 || bounds[0].y < 0 || bounds[0].width <= 0 || bounds[0].height <= 0) {
                            throw new IllegalArgumentException("Grid cell is not visible");
                        }
                    } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                });
                new CustomControl((Control) widget).clickAt(bounds[0].x + bounds[0].width / 2, bounds[0].y + bounds[0].height / 2);
            }
            case "table-select" -> new SWTBotTable((Table) widget).select(Integer.parseInt(args[2]));
            case "table-context" -> new SWTBotTable((Table) widget).contextMenu(args[2]).click();
            case "focus" -> display.syncExec(() -> ((Control) widget).setFocus());
            default -> throw new IllegalArgumentException(args[0]);
        }
        out.println("ACTION " + args[0] + " " + args[1]);
    }

    /** Starts the production presentation lookup without blocking this command queue. */
    private void startSourceRequest(String[] args, PrintWriter out) {
        if (sourceRequests.containsKey(args[1])) throw new IllegalArgumentException("Duplicate source request " + args[1]);
        Object[] selected = new Object[1];
        Throwable[] error = new Throwable[1];
        display.syncExec(() -> {
            try {
                if (sourcePresentation == null) {
                    sourcePresentation = new org.jkiss.dbeaver.debug.ui.DatabaseDebugModelPresentation();
                }
                if (args[2].equals("node")) {
                    Widget widget = widgets.get(Integer.parseInt(args[3]));
                    if (!(widget instanceof TreeItem item) || item.isDisposed()) {
                        throw new IllegalArgumentException("Take a fresh dump and select a real routine tree item");
                    }
                    selected[0] = item.getData();
                } else if (args[2].equals("frame")) {
                    selected[0] = org.eclipse.debug.ui.DebugUITools.getDebugContext();
                    if (!(selected[0] instanceof org.eclipse.debug.core.model.IStackFrame)) {
                        throw new IllegalArgumentException("Select a paused frame first");
                    }
                } else if (args[2].equals("breakpoint")) {
                    var breakpoints = org.eclipse.debug.core.DebugPlugin.getDefault().getBreakpointManager()
                        .getBreakpoints(org.jkiss.dbeaver.debug.DBGConstants.MODEL_IDENTIFIER_DATABASE);
                    int index = Integer.parseInt(args[3]);
                    if (index < 0 || index >= breakpoints.length) throw new IllegalArgumentException("Breakpoint index " + index);
                    selected[0] = breakpoints[index];
                } else {
                    throw new IllegalArgumentException("Expected node, frame or breakpoint source");
                }
            } catch (Throwable e) {
                error[0] = e;
            }
        });
        if (error[0] != null) throw new IllegalStateException("Cannot start source request", error[0]);
        var request = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                Object source = selected[0];
                if (source instanceof org.eclipse.debug.core.model.IStackFrame frame) {
                    source = frame.getLaunch().getSourceLocator().getSourceElement(frame);
                }
                var input = sourcePresentation.getEditorInput(source);
                if (input == null) throw new AssertionError("Production source lookup returned no input");
                return input.getClass().getName() + "/" + input.getName();
            } catch (Throwable e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, sourceWorkers);
        sourceRequests.put(args[1], request);
        out.println("SCHEDULED production source request=" + args[1] + " kind=" + args[2]);
    }

    /** Verifies the actual nested document selection and Eclipse instruction-pointer annotation. */
    private void inspectSourcePosition(int requestedLine, boolean requirePointer, PrintWriter out) {
        Throwable[] failure = new Throwable[1];
        display.syncExec(() -> {
            try {
                var context = org.eclipse.debug.ui.DebugUITools.getDebugContext();
                var frame = context instanceof org.eclipse.debug.core.model.IStackFrame value ? value : null;
                if (frame == null && (requirePointer || requestedLine <= 0)) {
                    throw new AssertionError("Select a paused frame before checking source position");
                }
                int expected = requestedLine > 0 ? requestedLine : frame.getLineNumber();
                var editor = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
                if (editor == null) throw new AssertionError("No source editor");
                Class<?> textType = org.eclipse.core.runtime.Platform.getBundle("org.eclipse.ui.workbench.texteditor")
                    .loadClass("org.eclipse.ui.texteditor.ITextEditor");
                Object nested = editor.getAdapter(textType);
                if (nested == null) throw new AssertionError("Active editor has no nested text editor");
                Object provider = textType.getMethod("getDocumentProvider").invoke(nested);
                var providerType = textType.getMethod("getDocumentProvider").getReturnType();
                var input = (org.eclipse.ui.IEditorInput) textType.getMethod("getEditorInput").invoke(nested);
                var documentMethod = providerType.getMethod("getDocument", Object.class);
                Object document = documentMethod.invoke(provider, input);
                if (document == null) throw new AssertionError("No loaded source document");
                Class<?> documentType = documentMethod.getReturnType();
                var lineAtOffset = documentType.getMethod("getLineOfOffset", int.class);
                var selectionProvider = (org.eclipse.jface.viewers.ISelectionProvider)
                    textType.getMethod("getSelectionProvider").invoke(nested);
                Object selection = selectionProvider.getSelection();
                int caretOffset = ((Number) selection.getClass().getMethod("getOffset").invoke(selection)).intValue();
                int caretLine = ((Number) lineAtOffset.invoke(document, caretOffset)).intValue() + 1;
                out.println("source-position editor=" + editor.getTitle() + " frame-line=" + (frame == null ? "none" : frame.getLineNumber())
                    + " expected-line=" + expected + " caret-line=" + caretLine);
                var annotationMethod = providerType.getMethod("getAnnotationModel", Object.class);
                Object model = annotationMethod.invoke(provider, input);
                if (model == null) throw new AssertionError("Source has no annotation model");
                Class<?> modelType = annotationMethod.getReturnType();
                Iterator<?> annotations = (Iterator<?>) modelType.getMethod("getAnnotationIterator").invoke(model);
                var positionMethod = Arrays.stream(modelType.getMethods())
                    .filter(method -> method.getName().equals("getPosition") && method.getParameterCount() == 1)
                    .findFirst().orElseThrow();
                var annotationTypeMethod = positionMethod.getParameterTypes()[0].getMethod("getType");
                boolean pointerAtExpectedLine = false;
                int pointers = 0;
                while (annotations.hasNext()) {
                    Object annotation = annotations.next();
                    String type = String.valueOf(annotationTypeMethod.invoke(annotation));
                    if (!type.equals(org.eclipse.debug.ui.IDebugUIConstants.ANNOTATION_TYPE_INSTRUCTION_POINTER_CURRENT)
                        && !type.equals(org.eclipse.debug.ui.IDebugUIConstants.ANNOTATION_TYPE_INSTRUCTION_POINTER_SECONDARY)) continue;
                    Object position = positionMethod.invoke(model, annotation);
                    if (position == null) continue;
                    int offset = ((Number) position.getClass().getMethod("getOffset").invoke(position)).intValue();
                    int line = ((Number) lineAtOffset.invoke(document, offset)).intValue() + 1;
                    out.println("instruction-pointer type=" + type + " line=" + line);
                    pointers++;
                    pointerAtExpectedLine |= line == expected;
                }
                if (caretLine != expected || requirePointer && !pointerAtExpectedLine) {
                    throw new AssertionError("Expected source caret and instruction pointer at line " + expected
                        + ", actual caret=" + caretLine + " pointer-count=" + pointers);
                }
                out.println(requirePointer ? "PASS source caret and execution annotation match selected frame"
                    : "PASS source caret matches requested breakpoint line");
            } catch (Throwable e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) throw new AssertionError("Source-position verification failed", failure[0]);
    }

    private void inspectModel(TreeItem item, PrintWriter out) throws Exception {
        Object[] data = new Object[1];
        display.syncExec(() -> data[0] = item.getData());
        Object object = data[0].getClass().getMethod("getObject").invoke(data[0]);
        out.println("model=" + object.getClass().getName());
        for (String name : java.util.List.of("getName", "getCompatibility", "isStoredProcedureSupported", "isPackageSupported")) {
            try {
                out.println(name + "=" + object.getClass().getMethod(name).invoke(object));
            } catch (NoSuchMethodException ignored) {
            }
        }
        if (object.getClass().getSimpleName().equals("GaussDBProcedure")
            || object.getClass().getSimpleName().equals("GaussDBFunction")) {
            var modelBundle = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.model");
            Object monitor = modelBundle.loadClass("org.jkiss.dbeaver.model.runtime.VoidProgressMonitor").getConstructor().newInstance();
            var debugBundle = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.ext.gaussdb.debug.core");
            Class<?> core = debugBundle.loadClass("org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugCore");
            var eligibility = Arrays.stream(core.getMethods()).filter(m -> m.getName().equals("getRoutineEligibilityError")
                && m.getParameterCount() == 2).findFirst().orElseThrow();
            out.println("eligibility=" + eligibility.invoke(null, monitor, object));
            Class<?> utils = modelBundle.loadClass("org.jkiss.dbeaver.model.DBUtils");
            var contextMethod = Arrays.stream(utils.getMethods()).filter(m -> m.getName().equals("getDefaultContext")
                && m.getParameterCount() == 2).findFirst().orElseThrow();
            Object context = contextMethod.invoke(null, object, false);
            Class<?> detector = debugBundle.loadClass("org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugCapabilityDetector");
            var check = Arrays.stream(detector.getDeclaredMethods()).filter(m -> m.getName().equals("check")).findFirst().orElseThrow();
            check.setAccessible(true);
            try {
                out.println("capabilities=" + check.invoke(null, context, monitor, object.getClass().getMethod("getObjectId").invoke(object)));
            } catch (java.lang.reflect.InvocationTargetException e) {
                out.println("capabilities-denied=" + e.getTargetException().getMessage());
            }
        }
    }

    private void inspectModes(TreeItem item, PrintWriter out) throws Exception {
        Object[] data = new Object[1];
        display.syncExec(() -> data[0] = item.getData());
        Object object = data[0].getClass().getMethod("getObject").invoke(data[0]);
        Object source = object.getClass().getMethod("getDataSource").invoke(object);
        Class<?> type = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.ext.gaussdb")
            .loadClass("org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDatabase");
        Object monitor = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.model")
            .loadClass("org.jkiss.dbeaver.model.runtime.VoidProgressMonitor").getConstructor().newInstance();
        var constructor = Arrays.stream(type.getDeclaredConstructors()).filter(c -> c.getParameterCount() == 3
            && c.getParameterTypes()[2] == String.class).findFirst().orElseThrow();
        constructor.setAccessible(true);
        for (String mode : java.util.List.of("a", "b", "c", "pg", "m")) {
            Object database = constructor.newInstance(monitor, source, "dbeaver_ext_0909_" + mode);
            try {
                out.println("database=dbeaver_ext_0909_" + mode);
                for (String method : java.util.List.of("getCompatibility", "isStoredProcedureSupported", "isPackageSupported")) {
                    out.println(method + "=" + type.getMethod(method).invoke(database));
                }
            } finally {
                // These temporary database instances are outside the navigator cache.
                // The datasource will not discover them during its normal shutdown.
                Arrays.stream(type.getMethods()).filter(m -> m.getName().equals("shutdown")
                    && m.getParameterCount() == 2).findFirst().orElseThrow().invoke(database, monitor, false);
            }
        }
    }

    private static org.jkiss.dbeaver.debug.DBGBreakpointDescriptor breakpointFixtureDescriptor(long oid, long line, String database)
        throws Exception {
        Class<?> type = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.ext.gaussdb.debug.core")
            .loadClass("org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugBreakpointDescriptor");
        return (org.jkiss.dbeaver.debug.DBGBreakpointDescriptor) type.getConstructor(long.class, long.class, String.class)
            .newInstance(oid, line, database);
    }

    /** Synthetic catalog_NNN fixture: 40 procedures, 20 functions and two four-member packages per schema. */
    private void metadataManySchemas(TreeItem item, String[] args, PrintWriter out) throws Exception {
        Object[] node = new Object[1];
        display.syncExec(() -> node[0] = item.getData());
        Object source = node[0].getClass().getMethod("getDataSource").invoke(node[0]);
        Object container = source.getClass().getMethod("getContainer").invoke(source);
        Object driver = container.getClass().getMethod("getDriver").invoke(container);
        out.println("driverThreadSafe=" + driver.getClass().getMethod("isThreadSafeDriver").invoke(driver));
        Object database = source.getClass().getMethod("getDatabase", String.class).invoke(source, args[2]);
        var model = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.model");
        Class<?> monitorType = model.loadClass("org.jkiss.dbeaver.model.runtime.DBRProgressMonitor");
        Class<?> voidMonitor = model.loadClass("org.jkiss.dbeaver.model.runtime.VoidProgressMonitor");
        int count = Integer.parseInt(args[4]);
        int repeats = Integer.parseInt(args[5]);
        java.util.List<Object> schemas = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Object schema = database.getClass().getMethod("getSchema", monitorType, String.class).invoke(
                database, voidMonitor.getConstructor().newInstance(), args[3] + String.format("%03d", i));
            if (schema == null) throw new AssertionError("Missing fixture schema " + i);
            schemas.add(schema);
            if (args.length > 7 && args[7].equals("refresh")) {
                for (String method : new String[]{"getGaussDBProceduresCache", "getGaussDBFunctionsCache"}) {
                    Object cache = schema.getClass().getMethod(method).invoke(schema);
                    cache.getClass().getMethod("clearCache").invoke(cache);
                }
                Object packages = schema.getClass().getField("packageCache").get(schema);
                packages.getClass().getMethod("clearCache").invoke(packages);
            }
        }
        var gate = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(12, runnable -> {
            Thread thread = new Thread(runnable, "GaussDB multi-schema metadata acceptance");
            thread.setDaemon(true);
            return thread;
        });
        java.util.List<java.util.concurrent.Future<Long>> jobs = new ArrayList<>();
        long started = System.nanoTime();
        try {
            for (Object schema : schemas) {
                for (int repetition = 0; repetition < repeats; repetition++) {
                    jobs.add(pool.submit(() -> {
                        gate.await();
                        long begin = System.nanoTime();
                        Object progress = voidMonitor.getConstructor().newInstance();
                        for (String method : new String[]{"getGaussDBProcedures", "getGaussDBFunctions"}) {
                            int expected = method.equals("getGaussDBProcedures") ? 40 : 20;
                            if (((Collection<?>) schema.getClass().getMethod(method, monitorType)
                                .invoke(schema, progress)).size() != expected) throw new AssertionError(method + " count");
                        }
                        var packages = (Collection<?>) schema.getClass().getMethod("getPackages", monitorType)
                            .invoke(schema, progress);
                        if (packages.size() != 2) throw new AssertionError("Package count");
                        for (Object value : packages) {
                            if (((Collection<?>) value.getClass().getMethod("getPackageProcedures", monitorType)
                                .invoke(value, progress)).size() != 4) throw new AssertionError("Package member count");
                        }
                        return (System.nanoTime() - begin) / 1_000_000;
                    }));
                }
            }
            gate.countDown();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(Long.parseLong(args[6]));
            long longest = 0;
            for (var job : jobs) {
                longest = Math.max(longest, job.get(Math.max(1, deadline - System.nanoTime()),
                    java.util.concurrent.TimeUnit.NANOSECONDS));
            }
            out.println("PASS metadata schemas=" + count + " tasks=" + jobs.size() + " workers=12 longestTaskMs="
                + longest + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000);
        } finally {
            for (var job : jobs) job.cancel(true);
            pool.shutdownNow();
        }
    }

    /** Concurrent real model loads, including package members and cross-schema parameter types. */
    private void metadataLoad(TreeItem item, String[] args, PrintWriter out) throws Exception {
        Object[] node = new Object[1];
        display.syncExec(() -> node[0] = item.getData());
        Object source = node[0].getClass().getMethod("getDataSource").invoke(node[0]);
        Object database = source.getClass().getMethod("getDatabase", String.class).invoke(source, args[2]);
        var model = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.model");
        Class<?> monitorType = model.loadClass("org.jkiss.dbeaver.model.runtime.DBRProgressMonitor");
        Class<?> voidMonitor = model.loadClass("org.jkiss.dbeaver.model.runtime.VoidProgressMonitor");
        Object monitor = voidMonitor.getConstructor().newInstance();
        Object schema = database.getClass().getMethod("getSchema", monitorType, String.class)
            .invoke(database, monitor, args[3]);
        Object lookup = database.getClass().getMethod("getSchema", monitorType, String.class)
            .invoke(database, monitor, "lookup");
        int procedures = Integer.parseInt(args[4]);
        int functions = Integer.parseInt(args[5]);
        int packages = Integer.parseInt(args[6]);
        int members = Integer.parseInt(args[7]);
        var gate = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(6, runnable -> {
            Thread thread = new Thread(runnable, "GaussDB metadata acceptance");
            thread.setDaemon(true);
            return thread;
        });
        java.util.List<java.util.concurrent.Future<String>> jobs = new ArrayList<>();
        long started = System.nanoTime();
        try {
            for (int task = 0; task < 6; task++) {
                final int operation = task;
                jobs.add(pool.submit(() -> {
                    gate.await();
                    Object progress = voidMonitor.getConstructor().newInstance();
                    long begin = System.nanoTime();
                    if (operation == 0 || operation == 4 || operation == 1
                        || (operation == 3 && args.length > 9 && args[9].equals("routine-only"))) {
                        boolean isFunction = operation == 1;
                        var values = (Collection<?>) schema.getClass().getMethod(
                            isFunction ? "getGaussDBFunctions" : "getGaussDBProcedures", monitorType)
                            .invoke(schema, progress);
                        if (values.size() != (isFunction ? functions : procedures)) {
                            throw new AssertionError("Wrong routine count: " + values.size());
                        }
                    } else if (operation == 2 || operation == 5) {
                        var values = (Collection<?>) schema.getClass().getMethod("getPackages", monitorType)
                            .invoke(schema, progress);
                        if (values.size() != packages) throw new AssertionError("Wrong package count: " + values.size());
                        for (Object value : values) {
                            var children = (Collection<?>) value.getClass().getMethod(
                                operation == 2 ? "getPackageProcedures" : "getPackageFunctions", monitorType)
                                .invoke(value, progress);
                            if (children.size() != (operation == 2 ? members : 0)) {
                                throw new AssertionError("Wrong member count: " + children.size());
                            }
                        }
                    } else {
                        Object cache = lookup.getClass().getMethod("getDataTypeCache").invoke(lookup);
                        var method = Arrays.stream(cache.getClass().getMethods())
                            .filter(m -> m.getName().equals("getAllObjects") && m.getParameterCount() == 2)
                            .findFirst().orElseThrow();
                        if (((Collection<?>) method.invoke(cache, progress, lookup)).size() < 400) {
                            throw new AssertionError("Cross-schema types were not loaded");
                        }
                    }
                    return "PASS metadata task=" + operation + " elapsedMs=" + (System.nanoTime() - begin) / 1_000_000;
                }));
            }
            gate.countDown();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(Long.parseLong(args[8]));
            for (var job : jobs) {
                out.println(job.get(Math.max(1, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS));
            }
            out.println("PASS metadata concurrent loads complete elapsedMs=" + (System.nanoTime() - started) / 1_000_000);
        } finally {
            for (var job : jobs) job.cancel(true);
            pool.shutdownNow();
        }
    }

    private void resolveFrame(TreeItem item, long oid, String expectedSchema, boolean expectMissing, PrintWriter out)
        throws Exception {
        Object[] data = new Object[1];
        display.syncExec(() -> data[0] = item.getData());
        Object routine = data[0].getClass().getMethod("getObject").invoke(data[0]);
        Object source = routine.getClass().getMethod("getDataSource").invoke(routine);
        Object container = source.getClass().getMethod("getContainer").invoke(source);
        var bundle = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.ext.gaussdb.debug.core");
        Class<?> core = bundle.loadClass("org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugCore");
        Map<String, Object> configuration = new HashMap<>();
        Arrays.stream(core.getMethods()).filter(m -> m.getName().equals("saveRoutine")).findFirst().orElseThrow()
            .invoke(null, routine, configuration);
        Map<String, Object> original = Map.copyOf(configuration);
        Class<?> resolverType = bundle.loadClass("org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugResolver");
        Object resolver = resolverType.getConstructors()[0].newInstance(container);
        Object monitor = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.model")
            .loadClass("org.jkiss.dbeaver.model.runtime.VoidProgressMonitor").getConstructor().newInstance();
        Object resolved;
        try {
            resolved = Arrays.stream(resolverType.getMethods()).filter(m -> m.getName().equals("resolveObject"))
                .findFirst().orElseThrow().invoke(resolver, configuration, oid, monitor);
        } catch (java.lang.reflect.InvocationTargetException e) {
            String message = e.getCause().getMessage();
            if (!expectMissing || message == null || !message.contains("Routine " + oid + " not found in database")
                || !message.contains("Refresh the database navigator and reselect the routine")
                || !configuration.equals(original)) {
                throw e;
            }
            out.println("PASS missing routine OID=" + oid + "; actionable diagnostic; launch unchanged; no name fallback");
            return;
        }
        if (expectMissing) {
            throw new AssertionError("Missing OID unexpectedly resolved: " + oid);
        }
        Object schema = resolved.getClass().getMethod("getSchema").invoke(resolved);
        String actualSchema = (String) schema.getClass().getMethod("getName").invoke(schema);
        long actualOid = ((Number) resolved.getClass().getMethod("getObjectId").invoke(resolved)).longValue();
        if (!expectedSchema.equals(actualSchema) || actualOid != oid || !configuration.equals(original)) {
            throw new AssertionError("Wrong frame target or launch configuration mutated: " + actualSchema + "/" + actualOid);
        }
        out.println("PASS resolve-frame schema=" + actualSchema + " oid=" + actualOid + "; launch unchanged");
    }

    /** Replays a real late navigation callback after its lifecycle has changed. No production delay hooks. */
    private void navigationRace(Shell shell, String scenario, PrintWriter out) throws Exception {
        Throwable[] failure = new Throwable[1];
        display.syncExec(() -> {
            try {
                Object dialog = shell.getData();
                if (dialog == null || !dialog.getClass().getSimpleName().equals("GaussDBPackageCompileResultsDialog")) {
                    throw new AssertionError("Select the real package compilation results shell");
                }
                Class<?> type = dialog.getClass();
                var requests = type.getDeclaredField("navigationRequest");
                var results = type.getDeclaredField("results");
                var tableField = type.getDeclaredField("table");
                requests.setAccessible(true);
                results.setAccessible(true);
                tableField.setAccessible(true);
                int oldRequest = requests.getInt(dialog);
                Object oldResult = ((java.util.List<?>) results.get(dialog)).get(0);
                var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                var oldEditor = page.getActiveEditor();
                if (oldEditor == null || oldEditor.isDirty()) {
                    throw new AssertionError("Requires a clean active package editor; refusing to discard edits");
                }
                var oldInput = oldEditor.getEditorInput();
                var callback = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals("positionWhenLoaded"))
                    .findFirst().orElseThrow();
                callback.setAccessible(true);
                switch (scenario) {
                    case "closed-editor" -> {
                        if (!page.closeEditor(oldEditor, false)) throw new AssertionError("Editor did not close");
                    }
                    case "closed-dialog" -> shell.dispose();
                    case "stale-request" -> {
                        Table table = (Table) tableField.get(dialog);
                        if (table.getItemCount() < 2) throw new AssertionError("Requires two package errors");
                        table.setSelection(1);
                        table.notifyListeners(SWT.DefaultSelection, new Event());
                        if (requests.getInt(dialog) == oldRequest) throw new AssertionError("New navigation was not issued");
                    }
                    default -> throw new IllegalArgumentException(scenario);
                }
                var expectedEditor = page.getActiveEditor();
                // Invoke the exact callback captured before close/supersession, as a timer would.
                callback.invoke(dialog, oldEditor, oldResult, oldRequest, 100);
                if (page.getActiveEditor() != expectedEditor) throw new AssertionError("Late callback stole editor focus");
                if (scenario.equals("closed-editor") && page.findEditor(oldInput) != null) {
                    throw new AssertionError("Late callback reopened the closed editor");
                }
                out.println("PASS navigation-race " + scenario + "; late callback rejected without changing active editor");
            } catch (Throwable e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) throw new Exception("Navigation lifecycle assertion failed", failure[0]);
    }

    private void dump(Widget widget, String indent, PrintWriter out) {
        int id = widgets.size();
        widgets.put(id, widget);
        String text = "";
        try {
            // SWT exposes getText across unrelated widget hierarchies.
            var method = widget.getClass().getMethod("getText");
            method.trySetAccessible();
            text = String.valueOf(method.invoke(widget));
        } catch (ReflectiveOperationException ignored) {
        }
        if (widget instanceof Text t && (t.getStyle() & SWT.PASSWORD) != 0) text = "<redacted>";
        out.println(indent + id + " " + widget.getClass().getSimpleName() + " " + text.replace("\n", "\\n")
            + (widget instanceof Control c ? " visible=" + c.isVisible() + " enabled=" + c.isEnabled() : ""));
        if (widget instanceof Text t) out.println(indent + " editable=" + t.getEditable());
        if (widget instanceof MenuItem item) out.println(indent + " enabled=" + item.isEnabled());
        if (widget instanceof ToolItem t) out.println(indent + " tooltip=" + t.getToolTipText() + " enabled=" + t.isEnabled());
        if (widget instanceof StyledText t) out.println(indent + " caretLine="
            + (t.getLineAtOffset(t.getCaretOffset()) + 1) + " selection=" + t.getSelection());
        if (widget instanceof Shell s && s.getMenuBar() != null) dump(s.getMenuBar(), indent + " ", out);
        if (widget instanceof Menu m) for (MenuItem child : m.getItems()) dump(child, indent + " ", out);
        if (widget instanceof MenuItem m && m.getMenu() != null) dump(m.getMenu(), indent + " ", out);
        if (widget instanceof TreeItem t) {
            out.println(indent + " checked=" + t.getChecked());
            for (int i = 1; i < t.getParent().getColumnCount(); i++) out.println(indent + " column" + i + "=" + t.getText(i));
            for (TreeItem child : t.getItems()) dump(child, indent + " ", out);
        }
        if (widget instanceof Tree t) for (TreeItem child : t.getItems()) dump(child, indent + " ", out);
        if (widget instanceof Table t) for (TableItem row : t.getItems()) {
            out.println(indent + " row=" + java.util.stream.IntStream.range(0, Math.max(1, t.getColumnCount()))
                .mapToObj(row::getText).toList());
        }
        if (widget instanceof ToolBar t) for (ToolItem child : t.getItems()) dump(child, indent + " ", out);
        if (widget instanceof CTabFolder t) for (CTabItem child : t.getItems()) dump(child, indent + " ", out);
        if (widget instanceof Composite c) for (Control child : c.getChildren()) dump(child, indent + " ", out);
    }

    private void postKey(KeyStroke key, int type) {
        display.syncExec(() -> {
            Event event = new Event();
            event.type = type;
            event.keyCode = key.getModifierKeys() != 0 ? key.getModifierKeys() : key.getNaturalKey();
            if (!display.post(event)) throw new IllegalStateException("Native key posting failed");
        });
        // Let AppKit process modifier-down before posting the function key.
        // Posting an entire chord inside one syncExec can lose modifier state.
        try {
            Thread.sleep(60);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Send the same mouse sequence used by SWTBot to nonstandard SWT controls. */
    private static final class CustomControl extends AbstractSWTBotControl<Control> {
        CustomControl(Control control) {
            super(control);
        }

        void clickAt(int x, int y) {
            clickXY(x, y);
        }

        @Override
        public AbstractSWTBot<Control> click() {
            clickXY(2, 2);
            return this;
        }
    }
}
