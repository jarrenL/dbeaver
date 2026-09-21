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

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IEditorPart;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBPackage;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBPackageCompileError;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBPackageCompileTarget;
import org.jkiss.dbeaver.ext.gaussdb.ui.internal.GaussDBMessages;
import org.jkiss.dbeaver.ext.gaussdb.ui.editors.GaussDBPackageBodyViewEditor;
import org.jkiss.dbeaver.ext.gaussdb.ui.editors.GaussDBPackageDeclareViewEditor;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.exec.compile.DBCCompileError;
import org.jkiss.dbeaver.model.exec.compile.DBCSourceHost;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;
import org.jkiss.dbeaver.ui.editors.entity.EntityEditor;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorNested;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerObjectOpen;

import java.util.List;

/** Keeps diagnostics attached to their package and source part, including batch compilation. */
final class GaussDBPackageCompileResultsDialog extends BaseDialog {
    record Result(@NotNull GaussDBPackage object, @NotNull DBCCompileError error) {
        GaussDBPackageCompileTarget part() {
            return error instanceof GaussDBPackageCompileError packageError
                ? packageError.getSourcePart() : GaussDBPackageCompileTarget.ALL;
        }
    }

    private final List<Result> results;
    private Table table;
    private int navigationRequest;

    GaussDBPackageCompileResultsDialog(@NotNull Shell parent, @NotNull List<Result> results) {
        super(parent, GaussDBMessages.package_compile_results, null);
        this.results = List.copyOf(results);
        setShellStyle((getShellStyle() & ~(SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL)) | SWT.MODELESS | SWT.RESIZE);
        setBlockOnOpen(false);
    }

    @Override
    public int open() {
        int result = super.open();
        // Preserve automatic first-error navigation; other rows remain selectable.
        if (getShell() != null && !getShell().isDisposed()) {
            navigateSelected();
        }
        return result;
    }

    @Override
    protected Composite createDialogArea(Composite parent) {
        Composite area = super.createDialogArea(parent);
        table = new Table(area, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
        data.widthHint = 850;
        data.heightHint = 280;
        table.setLayoutData(data);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        String[] headers = {GaussDBMessages.package_compile_object, GaussDBMessages.package_compile_part,
            GaussDBMessages.package_compile_line, GaussDBMessages.package_compile_message};
        int[] widths = {210, 100, 60, 460};
        for (int i = 0; i < headers.length; i++) {
            TableColumn column = new TableColumn(table, SWT.NONE);
            column.setText(headers[i]);
            column.setWidth(widths[i]);
        }
        for (Result result : results) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setData(result);
            item.setText(new String[] {result.object().getFullyQualifiedName(DBPEvaluationContext.UI),
                result.part().name(), Integer.toString(result.error().getLine()), result.error().toString()});
        }
        table.addListener(SWT.DefaultSelection, event -> navigateSelected());
        table.setSelection(0);
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OPEN_ID, GaussDBMessages.package_compile_open_source, true);
        createButton(parent, IDialogConstants.CLOSE_ID, IDialogConstants.CLOSE_LABEL, false);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == IDialogConstants.OPEN_ID) {
            navigateSelected();
        } else {
            close();
        }
    }

    private void navigateSelected() {
        if (table.getSelectionCount() == 0) {
            return;
        }
        Result result = (Result) table.getSelection()[0].getData();
        IEditorPart editor = NavigatorHandlerObjectOpen.openEntityEditor(result.object());
        if (!(editor instanceof EntityEditor entityEditor) || result.part() == GaussDBPackageCompileTarget.ALL) {
            return;
        }
        String folder = result.part() == GaussDBPackageCompileTarget.BODY
            ? "gaussdb.package.body" : "gaussdb.package.declaration";
        entityEditor.switchFolder(folder);
        positionWhenLoaded(entityEditor, result, ++navigationRequest, 100);
    }

    private void positionWhenLoaded(EntityEditor editor, Result result, int request, int attempts) {
        if (request != navigationRequest || getShell() == null || getShell().isDisposed()
            || editor.getSite() == null || editor.getSite().getPage() == null
            || editor.getSite().getPage().findEditor(editor.getEditorInput()) != editor) {
            return;
        }
        // A newly opened entity editor may not have created its folders yet.
        editor.switchFolder(result.part() == GaussDBPackageCompileTarget.BODY
            ? "gaussdb.package.body" : "gaussdb.package.declaration");
        DBCSourceHost host = editor.getAdapter(DBCSourceHost.class);
        boolean correctPart = result.part() == GaussDBPackageCompileTarget.BODY
            ? host instanceof GaussDBPackageBodyViewEditor : host instanceof GaussDBPackageDeclareViewEditor;
        if (!correctPart || !(host instanceof SQLEditorNested nested) || !nested.isDocumentLoaded()) {
            if (attempts > 0) {
                getShell().getDisplay().timerExec(100, () -> positionWhenLoaded(editor, result, request, attempts - 1));
            } else {
                UIUtils.showMessageBox(getShell(), GaussDBMessages.package_compile_results,
                    GaussDBMessages.package_compile_load_failed, SWT.ICON_WARNING);
            }
            return;
        }
        if (host.getSourceObject() != result.object()) {
            return;
        }
        // The nested log must only contain diagnostics for the page it can navigate.
        host.getCompileLog().clearLog();
        for (Result entry : results) {
            if (entry.object() == result.object() && entry.part() == result.part()) {
                host.getCompileLog().error(entry.error());
            }
        }
        host.positionSource(result.error().getLine(), Math.max(1, result.error().getPosition()));
        host.setCompileInfo(result.object().getName() + " / " + result.part().name(), true);
        host.showCompileLog();
    }
}
