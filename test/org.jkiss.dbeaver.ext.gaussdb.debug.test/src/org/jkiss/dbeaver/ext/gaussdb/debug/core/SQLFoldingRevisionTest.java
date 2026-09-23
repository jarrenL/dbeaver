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
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.ui.editors.sql.syntax.SQLReconcilingStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Deterministic editor replacement during parsing; no timing or display dependency. */
class SQLFoldingRevisionTest {
    @Test
    void shortenedDocumentDiscardsOldParseWithoutPublishingAnnotations() throws Exception {
        assertReplacementDiscarded("select\n  111111111111111111111111111111;", "select 1;", false);
    }

    @Test
    void sameLengthReplacementAlsoDiscardsOldParse() throws Exception {
        assertReplacementDiscarded("select\n  111;", "select\n  222;", false);
    }

    @Test
    void replacingDocumentObjectDiscardsOldParse() throws Exception {
        assertReplacementDiscarded("select\n  111;", "select\n  111;", true);
    }

    @Test
    void editingAndRestoringOriginalTextStillInvalidatesParse() throws Exception {
        assertReplacementDiscarded("select\n  111;", "select\n  111;", false);
    }

    @Test
    void unchangedMultilineQueryPublishesWholeFoldingRange() throws Exception {
        assertParsedRanges(false);
    }

    @Test
    void oneInvalidCachedRangeRejectsEntireUpdate() throws Exception {
        assertParsedRanges(true);
    }

    private void assertParsedRanges(boolean invalid) throws Exception {
        String sql = "select\n  111;";
        SQLEditorBase editor = mock(SQLEditorBase.class);
        ProjectionAnnotationModel model = mock(ProjectionAnnotationModel.class);
        when(editor.isFoldingEnabled()).thenReturn(true);
        when(editor.getProjectionAnnotationModel()).thenReturn(model);
        SQLReconcilingStrategy strategy = new SQLReconcilingStrategy(editor);
        var field = SQLReconcilingStrategy.class.getDeclaredField("document");
        field.setAccessible(true);
        field.set(strategy, new Document(sql));
        SQLScriptElement valid = mock(SQLScriptElement.class);
        when(valid.getLength()).thenReturn(sql.length());
        SQLScriptElement stale = mock(SQLScriptElement.class);
        when(stale.getOffset()).thenReturn(Integer.MAX_VALUE);
        when(stale.getLength()).thenReturn(100);
        when(editor.extractScriptQueries(anyInt(), anyInt(), eq(false), eq(true), eq(false)))
            .thenReturn(invalid ? List.of(valid, stale) : List.of(valid));
        assertDoesNotThrow(() -> strategy.reconcile(new Region(0, sql.length())));
        if (invalid) {
            verifyNoInteractions(model);
        } else {
            var additions = org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(model).modifyAnnotations(any(), additions.capture(), isNull());
            assertEquals(1, additions.getValue().size());
            Position position = (Position) additions.getValue().values().iterator().next();
            assertEquals(0, position.offset);
            assertEquals(sql.length(), position.length);
        }
    }

    private void assertReplacementDiscarded(String before, String after, boolean replaceDocument) throws Exception {
        SQLEditorBase editor = mock(SQLEditorBase.class);
        ProjectionAnnotationModel model = mock(ProjectionAnnotationModel.class);
        when(editor.isFoldingEnabled()).thenReturn(true);
        when(editor.getProjectionAnnotationModel()).thenReturn(model);
        Document document = new Document(before);
        SQLReconcilingStrategy strategy = new SQLReconcilingStrategy(editor);
        // Avoid setDocument's unrelated workbench spelling service initialization.
        var field = SQLReconcilingStrategy.class.getDeclaredField("document");
        field.setAccessible(true);
        field.set(strategy, document);
        SQLScriptElement element = mock(SQLScriptElement.class);
        when(element.getOffset()).thenReturn(0);
        when(element.getLength()).thenReturn(before.length());
        when(editor.extractScriptQueries(anyInt(), anyInt(), eq(false), eq(true), eq(false))).thenAnswer(invocation -> {
            if (replaceDocument) {
                field.set(strategy, new Document(after));
            } else {
                if (before.equals(after)) {
                    document.set("temporary edit");
                }
                document.set(after);
            }
            return List.of(element);
        });
        assertDoesNotThrow(() -> strategy.reconcile(new Region(0, before.length())));
        verifyNoInteractions(model);
    }
}
