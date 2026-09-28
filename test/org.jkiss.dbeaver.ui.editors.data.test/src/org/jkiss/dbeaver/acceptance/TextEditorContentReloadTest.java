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
package org.jkiss.dbeaver.acceptance;

import org.eclipse.core.resources.IStorage;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IEditorInput;
import org.jkiss.dbeaver.ui.IRefreshablePart;
import org.jkiss.dbeaver.ui.IRefreshablePart.RefreshResult;
import org.jkiss.dbeaver.ui.data.managers.stream.TextEditorPart;
import org.jkiss.dbeaver.ui.editors.StringEditorInput;
import org.jkiss.dbeaver.ui.editors.text.FileRefDocumentProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TextEditorContentReloadTest {
    private RefreshResult reload(TextEditorPart editor) throws Exception {
        var method = TextEditorPart.class.getDeclaredMethod("reloadDocument");
        method.setAccessible(true);
        return (RefreshResult) method.invoke(editor);
    }

    @Test
    void participatesInContentRefresh() {
        assertTrue(IRefreshablePart.class.isAssignableFrom(TextEditorPart.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "replacement", "中文导入'quote\\end\n"})
    void reloadReplacesDocumentAndFailedReadPreservesText(String replacement) throws Exception {
        var editor = mock(TextEditorPart.class, CALLS_REAL_METHODS);
        var input = mock(IEditorInput.class);
        var storage = new StringEditorInput("text", "original", false, "UTF-8").getStorage();
        when(input.getAdapter(IStorage.class)).thenReturn(storage);
        var provider = new FileRefDocumentProvider();
        doReturn(input).when(editor).getEditorInput();
        doReturn(provider).when(editor).getDocumentProvider();
        provider.connect(input);
        try {
            var document = provider.getDocument(input);
            assertEquals("original", document.get());
            storage.setString(replacement);
            assertEquals(RefreshResult.REFRESHED, reload(editor));
            assertSame(document, provider.getDocument(input));
            assertEquals(replacement, document.get());
            var failure = mock(IStorage.class);
            when(failure.getContents()).thenThrow(new CoreException(Status.error("Read failed")));
            when(input.getAdapter(IStorage.class)).thenReturn(failure);
            assertEquals(RefreshResult.CANCELED, reload(editor));
            assertEquals(replacement, document.get());
            when(input.getAdapter(IStorage.class)).thenReturn(storage);
            storage.setString("retry");
            assertEquals(RefreshResult.REFRESHED, reload(editor));
            assertEquals("retry", document.get());
        } finally {
            provider.disconnect(input);
        }
        assertEquals(RefreshResult.IGNORED, reload(editor));
    }
}
