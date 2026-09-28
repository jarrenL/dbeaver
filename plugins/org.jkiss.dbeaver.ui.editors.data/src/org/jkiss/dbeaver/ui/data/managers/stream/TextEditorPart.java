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
package org.jkiss.dbeaver.ui.data.managers.stream;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.IVerticalRuler;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditorExtension3;
import org.eclipse.ui.texteditor.IDocumentProviderExtension;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.IRefreshablePart;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.text.BaseTextEditor;
import org.jkiss.dbeaver.ui.editors.text.FileRefDocumentProvider;

/**
 * CONTENT text editor
 */
public class TextEditorPart extends BaseTextEditor implements IEditorPart, IRefreshablePart {

    private static final Log log = Log.getLog(TextEditorPart.class);

    public TextEditorPart() {
        configureInsertMode(ITextEditorExtension3.SMART_INSERT, false);
        setDocumentProvider(new FileRefDocumentProvider());
    }

    @Override
    public String getTitle()
    {
        return "Text";
    }

    @Override
    public Image getTitleImage()
    {
        return DBeaverIcons.getImage(DBIcon.TYPE_TEXT);
    }

    @Override
    public void createPartControl(Composite parent) {
        super.createPartControl(parent);
        setSourceViewerConfiguration(new PlainTextViewerConfiguration(this));
    }

    @Override
    protected ISourceViewer createSourceViewer(Composite parent, IVerticalRuler ruler, int styles) {
        return new SourceViewer(parent, ruler, null, false, styles);
        //return new ProjectionViewer(parent, ruler, null, false, styles);
    }

    @Override
    protected void updateContributedRulerColumns(CompositeRuler ruler) {
        // do nothing
    }

    @Override
    public RefreshResult refreshPart(Object source, boolean force) {
        RefreshResult[] result = {RefreshResult.IGNORED};
        UIUtils.syncExec(() -> {
            ISourceViewer viewer = getSourceViewer();
            if (viewer != null && viewer.getTextWidget() != null && !viewer.getTextWidget().isDisposed()) {
                result[0] = reloadDocument();
            }
        });
        return result[0];
    }

    private RefreshResult reloadDocument() {
        var provider = getDocumentProvider();
        var input = getEditorInput();
        if (provider == null || input == null || provider.getDocument(input) == null) {
            return RefreshResult.IGNORED;
        }
        try {
            provider.resetDocument(input);
            // AbstractDocumentProvider can record a read failure without throwing it.
            if (provider instanceof IDocumentProviderExtension extension) {
                var status = extension.getStatus(input);
                if (status != null && !status.isOK()) {
                    throw new CoreException(status);
                }
            }
            return RefreshResult.REFRESHED;
        } catch (CoreException e) {
            log.error("Error refreshing text content", e);
            return RefreshResult.CANCELED;
        }
    }
}
