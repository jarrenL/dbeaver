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
            case "modes" -> inspectModes((TreeItem) widget, out);
            case "line" -> new SWTBotStyledText((StyledText) widget).navigateTo(Integer.parseInt(args[2]), 0);
            case "cell" -> new SWTBotTable((Table) widget).doubleClick(Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            case "focus" -> display.syncExec(() -> ((Control) widget).setFocus());
            default -> throw new IllegalArgumentException(args[0]);
        }
        out.println("ACTION " + args[0] + " " + args[1]);
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
            out.println("database=dbeaver_ext_0909_" + mode);
            for (String method : java.util.List.of("getCompatibility", "isStoredProcedureSupported", "isPackageSupported")) {
                out.println(method + "=" + type.getMethod(method).invoke(database));
            }
        }
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

        @Override
        public AbstractSWTBot<Control> click() {
            clickXY(2, 2);
            return this;
        }
    }
}
