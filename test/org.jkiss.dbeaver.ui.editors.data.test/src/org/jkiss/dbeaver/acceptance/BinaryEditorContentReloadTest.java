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

import org.eclipse.ui.IPathEditorInput;
import org.jkiss.dbeaver.ui.IRefreshablePart;
import org.jkiss.dbeaver.ui.editors.binary.BinaryContent;
import org.jkiss.dbeaver.ui.editors.binary.BinaryEditor;
import org.jkiss.dbeaver.ui.editors.binary.HexManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BinaryEditorContentReloadTest {
    @TempDir Path directory;

    @Test
    void editorParticipatesInContentRefreshContract() {
        assertTrue(IRefreshablePart.class.isAssignableFrom(BinaryEditor.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 65537})
    void reloadUsesNewFileAndReadFailureDoesNotReplaceContent(int size) throws Exception {
        var editor = mock(BinaryEditor.class, CALLS_REAL_METHODS);
        var manager = mock(HexManager.class);
        var managerField = BinaryEditor.class.getDeclaredField("manager");
        managerField.setAccessible(true);
        managerField.set(editor, manager);
        var input = mock(IPathEditorInput.class);
        doReturn(input).when(editor).getEditorInput();
        var load = BinaryEditor.class.getDeclaredMethod("loadBinaryContent");
        load.setAccessible(true);
        var loaded = new ArrayList<BinaryContent>();
        doAnswer(invocation -> { loaded.add(invocation.getArgument(0)); return null; })
            .when(manager).setContent(any(BinaryContent.class), eq("UTF-8"));
        var first = directory.resolve("first.bin");
        Files.write(first, new byte[] {1, 2, 3});
        var replacement = directory.resolve("替换 文件.bin");
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 31);
        Files.write(replacement, bytes);
        try {
            when(input.getPath()).thenReturn(org.eclipse.core.runtime.Path.fromOSString(first.toString()));
            assertEquals(true, load.invoke(editor));
            assertEquals(3, loaded.getFirst().length());
            when(input.getPath()).thenReturn(org.eclipse.core.runtime.Path.fromOSString(replacement.toString()));
            assertEquals(true, load.invoke(editor));
            assertEquals(2, loaded.size());
            var actual = loaded.getLast();
            assertNotSame(loaded.getFirst(), actual);
            assertEquals(size, actual.length());
            var buffer = ByteBuffer.allocate(size);
            actual.get(buffer, 0);
            assertArrayEquals(bytes, buffer.array());
            assertArrayEquals(bytes, Files.readAllBytes(replacement));
            // A directory is unreadable as binary content on the supported Linux/macOS buffer path.
            when(input.getPath()).thenReturn(org.eclipse.core.runtime.Path.fromOSString(directory.toString()));
            assertEquals(false, load.invoke(editor));
            assertEquals(2, loaded.size(), "Failed read must not install empty or partial content");
            when(input.getPath()).thenReturn(org.eclipse.core.runtime.Path.fromOSString(first.toString()));
            assertEquals(true, load.invoke(editor));
            assertEquals(3, loaded.getLast().length());
        } finally {
            loaded.forEach(BinaryContent::dispose);
        }
    }
}
