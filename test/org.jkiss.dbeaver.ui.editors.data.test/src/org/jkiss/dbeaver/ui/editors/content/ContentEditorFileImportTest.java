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
package org.jkiss.dbeaver.ui.editors.content;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.IEditorPart;
import org.jkiss.dbeaver.ui.data.IValueController;
import org.jkiss.dbeaver.ui.editors.StringEditorInput;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.app.DBPApplicationWorkbench;
import org.jkiss.dbeaver.model.app.DBPPlatform;
import org.jkiss.dbeaver.model.data.DBDContent;
import org.jkiss.dbeaver.model.data.DBDContentStorage;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBytes;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentEditorFileImportTest {
    private static class UncanceledMonitor extends VoidProgressMonitor {
        @Override public boolean isCanceled() {
            return false;
        }
    }

    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void canceledSaveKeepsImportedValuePendingForRetry(boolean binary) throws Exception {
        var controller = mock(IValueController.class);
        var input = input(controller, "UTF-8");
        var original = new byte[] {9, 8, 7};
        var content = spy(new JDBCContentBytes(mock(DBCExecutionContext.class), original));
        if (binary) {
            when(controller.getValue()).thenReturn(content);
            field(input, "stringStorage", null);
        }
        var selected = directory.resolve("pending-canceled-save.txt");
        Files.writeString(selected, "中文 pending\n");
        var monitor = mock(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor.class);
        when(monitor.isCanceled()).thenReturn(true);
        try (var workbench = mockStatic(DBWorkbench.class)) {
            workbench.when(DBWorkbench::getPlatform).thenReturn(mock(DBPPlatform.class));
            input.loadFromExternalFile(selected.toFile(), new NullProgressMonitor());
            assertThrows(DBException.class,
                () -> input.updateContentFromFile(monitor, binary ? content : "original"));
            verify(controller, never()).updateValue(any(), anyBoolean());
            verify(content, never()).updateContents(any(), any());
            if (binary) {
                assertEquals(Boolean.TRUE, field(input, "externalContentPending"));
                assertArrayEquals(original, content.getContentStream().readAllBytes());
            } else {
                assertEquals(Files.readString(selected),
                    ((StringEditorInput.StringStorage) field(input, "stringStorage")).getString());
            }
            when(monitor.isCanceled()).thenReturn(false);
            input.updateContentFromFile(monitor, binary ? content : "original");
            if (binary) {
                assertEquals(Boolean.FALSE, field(input, "externalContentPending"));
                assertArrayEquals(Files.readAllBytes(selected), content.getContentStream().readAllBytes());
            } else {
                verify(controller).updateValue(Files.readString(selected), false);
            }
            input.release();
        }
        assertEquals("中文 pending\n", Files.readString(selected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text-canceled", "text-missing", "binary-canceled", "binary-missing"})
    void failedSecondImportPreservesFirstPendingValueAndAllowsRetry(String scenario) throws Exception {
        boolean binary = scenario.startsWith("binary");
        var controller = mock(IValueController.class);
        var input = input(controller, "UTF-8");
        var original = new byte[] {1, 2, 3};
        var content = spy(new JDBCContentBytes(mock(DBCExecutionContext.class), original));
        if (binary) {
            when(controller.getValue()).thenReturn(content);
            field(input, "stringStorage", null);
        }
        var first = directory.resolve("first.txt");
        var second = directory.resolve("second.txt");
        Files.writeString(first, "中文 first\n");
        Files.writeString(second, "second replacement\n");
        try (var workbench = mockStatic(DBWorkbench.class)) {
            workbench.when(DBWorkbench::getPlatform).thenReturn(mock(DBPPlatform.class));
            input.loadFromExternalFile(first.toFile(), new NullProgressMonitor());
            if (scenario.endsWith("canceled")) {
                var checks = new java.util.concurrent.atomic.AtomicInteger();
                var canceled = new NullProgressMonitor() {
                    @Override public boolean isCanceled() {
                        return checks.incrementAndGet() == 2;
                    }
                };
                assertThrows(InterruptedException.class,
                    () -> input.loadFromExternalFile(second.toFile(), canceled));
                assertEquals(2, checks.get());
            } else {
                assertThrows(CoreException.class, () -> input.loadFromExternalFile(
                    directory.resolve("missing.txt").toFile(), new NullProgressMonitor()));
            }
            verify(controller, never()).updateValue(any(), anyBoolean());
            verify(content, never()).updateContents(any(), any());
            if (binary) {
                assertEquals(first.toFile(), field(input, "contentFile"));
                assertEquals(Boolean.TRUE, field(input, "externalContentPending"));
                assertArrayEquals(original, content.getContentStream().readAllBytes());
            } else {
                assertEquals(Files.readString(first),
                    ((StringEditorInput.StringStorage) field(input, "stringStorage")).getString());
            }
            input.updateContentFromFile(new UncanceledMonitor(), binary ? content : "original");
            if (binary) {
                assertArrayEquals(Files.readAllBytes(first), content.getContentStream().readAllBytes());
            } else {
                verify(controller).updateValue(Files.readString(first), false);
            }
            input.loadFromExternalFile(second.toFile(), new NullProgressMonitor());
            input.updateContentFromFile(new UncanceledMonitor(), binary ? content : "original");
            if (binary) {
                assertArrayEquals(Files.readAllBytes(second), content.getContentStream().readAllBytes());
                verify(content, times(2)).updateContents(any(), any());
            } else {
                verify(controller).updateValue(Files.readString(second), false);
                verify(controller, times(2)).updateValue(any(), eq(false));
            }
            input.release();
        }
        assertEquals("中文 first\n", Files.readString(first));
        assertEquals("second replacement\n", Files.readString(second));
    }

    @ParameterizedTest
    @ValueSource(strings = {"binary", "text-before-read", "text-between-chunks"})
    void cancellationDuringImportPreservesInput(String stage) throws Exception {
        var controller = mock(IValueController.class);
        var input = input(controller, "UTF-8");
        var selected = directory.resolve("cancel-during.bin");
        var replacement = "中文 replacement\n".repeat(10000);
        Files.writeString(selected, replacement);
        var original = directory.resolve("cancel-owned.bin");
        Files.writeString(original, "original");
        boolean binary = stage.equals("binary");
        if (binary) {
            field(input, "stringStorage", null);
            field(input, "contentFile", original.toFile());
            field(input, "contentDetached", false);
            when(controller.getValue()).thenReturn(mock(DBDContent.class));
        }
        int cancelAt = stage.equals("text-between-chunks") ? 4 : 2;
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var monitor = new NullProgressMonitor() {
            @Override public boolean isCanceled() {
                return checks.incrementAndGet() >= cancelAt;
            }
        };
        assertThrows(InterruptedException.class, () -> input.loadFromExternalFile(selected.toFile(), monitor));
        assertEquals(cancelAt, checks.get());
        verify(controller, never()).updateValue(any(), anyBoolean());
        assertEquals("original", Files.readString(original));
        assertEquals(replacement, Files.readString(selected));
        if (binary) {
            assertEquals(original.toFile(), field(input, "contentFile"));
            assertEquals(Boolean.FALSE, field(input, "externalContentPending"));
        } else {
            assertEquals("original", ((StringEditorInput.StringStorage) field(input, "stringStorage")).getString());
        }
        input.loadFromExternalFile(selected.toFile(), new NullProgressMonitor());
        if (binary) {
            assertEquals(selected.toFile(), field(input, "contentFile"));
            assertEquals(Boolean.TRUE, field(input, "externalContentPending"));
        } else {
            assertEquals(replacement, ((StringEditorInput.StringStorage) field(input, "stringStorage")).getString());
        }
        verify(controller, never()).updateValue(any(), anyBoolean());
        input.release();
        assertEquals(replacement, Files.readString(selected));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void canceledImportLeavesOriginalInputUntouched(boolean binary) throws Exception {
        var controller = mock(IValueController.class);
        var input = input(controller, "UTF-8");
        var selected = directory.resolve("canceled-import.bin");
        Files.writeString(selected, "replacement");
        var original = directory.resolve("owned-original.bin");
        Files.writeString(original, "original");
        var content = mock(DBDContent.class);
        if (binary) {
            field(input, "stringStorage", null);
            field(input, "contentFile", original.toFile());
            field(input, "contentDetached", false);
            when(controller.getValue()).thenReturn(content);
        }
        var monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        assertThrows(InterruptedException.class, () -> input.loadFromExternalFile(selected.toFile(), monitor));
        verify(controller, never()).updateValue(any(), anyBoolean());
        verifyNoInteractions(content);
        assertEquals("original", Files.readString(original));
        assertEquals("replacement", Files.readString(selected));
        if (binary) {
            assertEquals(original.toFile(), field(input, "contentFile"));
            assertEquals(Boolean.FALSE, field(input, "externalContentPending"));
        } else {
            assertEquals("original", ((StringEditorInput.StringStorage) field(input, "stringStorage")).getString());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void textSaveRechecksReadOnlyAfterImport(boolean discard) throws Exception {
        var controller = mock(IValueController.class);
        var input = input(controller, "UTF-8");
        var selected = directory.resolve("read-only-text.txt");
        Files.writeString(selected, "中文 pending");
        input.loadFromExternalFile(selected.toFile(), new NullProgressMonitor());
        when(controller.isReadOnly()).thenReturn(true);
        assertThrows(DBException.class,
            () -> input.updateContentFromFile(new UncanceledMonitor(), "original"));
        verify(controller, never()).updateValue(any(), anyBoolean());
        assertEquals("中文 pending", ((StringEditorInput.StringStorage) field(input, "stringStorage")).getString());
        if (discard) {
            input.release();
            verify(controller, never()).updateValue(any(), anyBoolean());
        } else {
            when(controller.isReadOnly()).thenReturn(false);
            input.updateContentFromFile(new UncanceledMonitor(), "original");
            verify(controller).updateValue("中文 pending", false);
            input.release();
        }
        assertEquals("中文 pending", Files.readString(selected));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void binarySaveRechecksReadOnlyAfterImport(boolean discard) throws Exception {
        var controller = mock(IValueController.class);
        var input = input(controller, "UTF-8");
        var original = new byte[] {1, 2, 3};
        var content = spy(new JDBCContentBytes(mock(DBCExecutionContext.class), original));
        when(controller.getValue()).thenReturn(content);
        field(input, "stringStorage", null);
        var oldFile = directory.resolve("read-only-original.bin");
        Files.write(oldFile, original);
        field(input, "contentFile", oldFile.toFile());
        field(input, "contentDetached", true);
        var selected = directory.resolve("read-only-selected.bin");
        var replacement = new byte[] {0, -1, 7, 9};
        Files.write(selected, replacement);
        try (var workbench = mockStatic(DBWorkbench.class)) {
            workbench.when(DBWorkbench::getPlatform).thenReturn(mock(DBPPlatform.class));
            input.loadFromExternalFile(selected.toFile(), new NullProgressMonitor());
            when(controller.isReadOnly()).thenReturn(true);
            assertThrows(DBException.class,
                () -> input.updateContentFromFile(new UncanceledMonitor(), content));
            verify(content, never()).updateContents(any(), any());
            assertArrayEquals(original, content.getContentStream().readAllBytes());
            assertEquals(Boolean.TRUE, field(input, "externalContentPending"));
            if (discard) {
                input.release();
                verify(content, never()).updateContents(any(), any());
                assertArrayEquals(original, content.getContentStream().readAllBytes());
            } else {
                when(controller.isReadOnly()).thenReturn(false);
                input.updateContentFromFile(new UncanceledMonitor(), content);
                verify(content).updateContents(any(), any());
                assertArrayEquals(replacement, content.getContentStream().readAllBytes());
                assertEquals(Boolean.FALSE, field(input, "externalContentPending"));
                input.release();
            }
        }
        verify(controller, never()).updateValue(any(), anyBoolean());
        assertArrayEquals(original, Files.readAllBytes(oldFile));
        assertArrayEquals(replacement, Files.readAllBytes(selected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"selected", "picker-canceled", "task-canceled"})
    void toolbarImportOnlyMarksEditorDirtyAndNeverPublishes(String mode) throws Exception {
        var contributor = mock(ContentEditorContributor.class);
        var editor = mock(ContentEditor.class);
        var controller = mock(IValueController.class);
        var input = mock(ContentEditorInput.class);
        var site = mock(org.eclipse.ui.IWorkbenchPartSite.class);
        var window = mock(org.eclipse.ui.IWorkbenchWindow.class);
        when(contributor.getEditor()).thenReturn(editor);
        when(editor.getSite()).thenReturn(site);
        when(editor.getEditorInput()).thenReturn(input);
        when(editor.getValueController()).thenReturn(controller);
        when(site.getWorkbenchWindow()).thenReturn(window);
        doAnswer(invocation -> {
            if (mode.equals("task-canceled")) {
                throw new InterruptedException();
            }
            ((org.eclipse.jface.operation.IRunnableWithProgress) invocation.getArgument(2))
                .run(new NullProgressMonitor());
            return null;
        }).when(window).run(eq(true), eq(true), any());
        var actionType = Class.forName(ContentEditorContributor.class.getName() + "$FileImportAction");
        var action = mock(actionType, CALLS_REAL_METHODS);
        var outer = actionType.getDeclaredField("this$0");
        outer.setAccessible(true);
        outer.set(action, contributor);
        var selected = directory.resolve("selected.bin").toFile();
        try (var workbench = mockStatic(DBWorkbench.class)) {
            workbench.when(DBWorkbench::getPlatform).thenReturn(mock(DBPPlatform.class, RETURNS_DEEP_STUBS));
            try (var dialogs = mockStatic(org.jkiss.dbeaver.ui.dialogs.DialogUtils.class)) {
                dialogs.when(() -> org.jkiss.dbeaver.ui.dialogs.DialogUtils.openFile(null))
                    .thenReturn(mode.equals("picker-canceled") ? null : selected);
                var run = actionType.getDeclaredMethod("run");
                run.setAccessible(true);
                run.invoke(action);
            }
        }
        verify(controller, never()).updateValue(any(), anyBoolean());
        if (!mode.equals("selected")) {
            verifyNoInteractions(input);
            verify(editor, never()).setDirty(anyBoolean());
            verify(editor, never()).fireContentChanged();
        } else {
            verify(input).loadFromExternalFile(eq(selected), any());
            verify(editor).setDirty(true);
            verify(editor).fireContentChanged();
        }
    }


    @ParameterizedTest
    @ValueSource(ints = {0, 4, 65537})
    void binaryImportDoesNotPublishBeforeExplicitSave(int size) throws Exception {
        var controller = mock(IValueController.class);
        var input = input(controller, "UTF-8");
        var content = mock(DBDContent.class);
        when(controller.getValue()).thenReturn(content);
        field(input, "stringStorage", null);
        var original = directory.resolve("original.bin");
        Files.write(original, new byte[] {1, 2, 3});
        field(input, "contentFile", original.toFile());
        field(input, "contentDetached", true);
        var selected = directory.resolve("selected.bin");
        var bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) i;
        Files.write(selected, bytes);
        var workbenchField = DBWorkbench.class.getDeclaredField("applicationWorkbench");
        workbenchField.setAccessible(true);
        var originalWorkbench = workbenchField.get(null);
        var workbench = mock(DBPApplicationWorkbench.class);
        when(workbench.getPlatform()).thenReturn(mock(DBPPlatform.class));
        workbenchField.set(null, workbench);
        try {
            input.loadFromExternalFile(selected.toFile(), new NullProgressMonitor());
        } finally {
            workbenchField.set(null, originalWorkbench);
        }
        verify(content, never()).updateContents(any(), any());
        verify(controller, never()).updateValue(any(), anyBoolean());
        assertEquals(size, input.getContentLength());
        input.release();
        verify(content, never()).updateContents(any(), any());
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(original));
        assertArrayEquals(bytes, Files.readAllBytes(selected));
    }

    @Test
    void importingTextDoesNotModifyResultSetBeforeExplicitSave() throws Exception {
        var controller = mock(IValueController.class);
        var input = input(controller, "UTF-8");
        var path = directory.resolve("pending.txt");
        Files.writeString(path, "pending replacement");
        input.loadFromExternalFile(path.toFile(), new NullProgressMonitor());
        assertEquals("pending replacement", ((StringEditorInput.StringStorage) field(input, "stringStorage")).getString());
        verify(controller, never()).updateValue(any(), anyBoolean());
        input.release(); // Closing without saving must only release editor-local content.
        verify(controller, never()).updateValue(any(), anyBoolean());
        assertEquals("pending replacement", Files.readString(path));
    }

    private ContentEditorInput input(IValueController controller, String charset) throws Exception {
        var input = mock(ContentEditorInput.class, CALLS_REAL_METHODS);
        field(input, "valueController", controller);
        field(input, "fileCharset", charset);
        field(input, "editorParts", new IEditorPart[0]);
        field(input, "stringStorage", new StringEditorInput("text", "original", false, charset).getStorage());
        when(controller.getValue()).thenReturn("original");
        return input;
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, 65537})
    void binaryExplicitSavePublishesSelectedFileAndRetriesAfterRejection(int size) throws Exception {
        var workbenchField = DBWorkbench.class.getDeclaredField("applicationWorkbench");
        workbenchField.setAccessible(true);
        var originalWorkbench = workbenchField.get(null);
        var workbench = mock(DBPApplicationWorkbench.class);
        when(workbench.getPlatform()).thenReturn(mock(DBPPlatform.class));
        workbenchField.set(null, workbench);
        try {
            var controller = mock(IValueController.class);
            var input = input(controller, "UTF-8");
            var originalBytes = new byte[] {1, 2, 3};
            var content = spy(new JDBCContentBytes(mock(DBCExecutionContext.class), originalBytes));
            when(controller.getValue()).thenReturn(content);
            field(input, "stringStorage", null);
            var original = directory.resolve("before.bin");
            Files.write(original, new byte[] {1, 2, 3});
            field(input, "contentFile", original.toFile());
            field(input, "contentDetached", true);
            var selected = directory.resolve("after.bin");
            var bytes = new byte[size];
            for (int i = 0; i < size; i++) bytes[i] = (byte) i;
            Files.write(selected, bytes);
            input.loadFromExternalFile(selected.toFile(), new NullProgressMonitor());
            verify(content, never()).updateContents(any(), any());
            assertArrayEquals(originalBytes, content.getContentStream().readAllBytes());
            var rejection = new DBException("Rejected save");
            doThrow(rejection).doCallRealMethod().when(content).updateContents(any(), any());
            assertSame(rejection, assertThrows(DBException.class,
                () -> input.updateContentFromFile(new UncanceledMonitor(), content)));
            assertEquals(selected.toString(), input.getPath().toOSString());
            assertArrayEquals(originalBytes, content.getContentStream().readAllBytes());
            input.updateContentFromFile(new UncanceledMonitor(), content);
            assertFalse(content.isNull(), "Zero-byte content must remain distinct from SQL NULL");
            assertArrayEquals(bytes, content.getContentStream().readAllBytes());
            var storage = ArgumentCaptor.forClass(DBDContentStorage.class);
            verify(content, times(2)).updateContents(any(), storage.capture());
            verify(content, never()).getContents(any()); // Do not save stale original local storage.
            for (var attempt : storage.getAllValues()) {
                assertEquals("UTF-8", attempt.getCharset());
                try (var stream = attempt.getContentStream()) {
                    assertArrayEquals(bytes, stream.readAllBytes());
                }
            }
            input.release();
            assertArrayEquals(bytes, content.getContentStream().readAllBytes());
            content.resetContents();
            assertArrayEquals(originalBytes, content.getContentStream().readAllBytes());
            assertArrayEquals(bytes, Files.readAllBytes(selected));
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(original));
        } finally {
            workbenchField.set(null, originalWorkbench);
        }
    }

    private static void field(ContentEditorInput input, String name, Object value) throws Exception {
        var field = ContentEditorInput.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(input, value);
    }

    private static Object field(ContentEditorInput input, String name) throws Exception {
        var field = ContentEditorInput.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTF-8", "UTF-16LE", "GB18030"})
    void textImportUsesEditorEncodingAndCanReplaceWithEmptyFile(String charset) throws Exception {
        var controller = mock(IValueController.class);
        var input = input(controller, charset);
        var storage = field(input, "stringStorage");
        var path = directory.resolve("导入 文本.txt");
        var expected = "中文\nquote'\\end";
        Files.writeString(path, expected, Charset.forName(charset));
        var bytes = Files.readAllBytes(path);
        input.loadFromExternalFile(path.toFile(), new NullProgressMonitor());
        verify(controller, never()).updateValue(any(), anyBoolean());
        assertSame(storage, field(input, "stringStorage"));
        assertEquals(expected, ((StringEditorInput.StringStorage) storage).getString());
        assertEquals(expected.length(), input.getContentLength());
        assertArrayEquals(bytes, Files.readAllBytes(path));
        var empty = directory.resolve("empty.txt");
        Files.write(empty, new byte[0]);
        input.loadFromExternalFile(empty.toFile(), new NullProgressMonitor());
        verify(controller, never()).updateValue(any(), anyBoolean());
        assertEquals("", ((StringEditorInput.StringStorage) storage).getString());
        assertEquals(0, input.getContentLength());
        input.updateContentFromFile(new UncanceledMonitor(), input.getValue());
        verify(controller).updateValue("", false);
        input.release();
        assertArrayEquals(bytes, Files.readAllBytes(path));
        assertTrue(Files.exists(empty));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readFailurePreservesInputAndAllowsRetry(boolean useDirectory) throws Exception {
        var controller = mock(IValueController.class);
        var input = input(controller, "UTF-8");
        var storage = field(input, "stringStorage");
        var originalPath = input.getPath();
        var bad = useDirectory ? directory : directory.resolve("missing.txt");
        assertThrows(CoreException.class, () -> input.loadFromExternalFile(bad.toFile(), new NullProgressMonitor()));
        assertSame(storage, field(input, "stringStorage"));
        assertEquals(originalPath, input.getPath());
        assertEquals("original", ((StringEditorInput.StringStorage) storage).getString());
        assertEquals(8, input.getContentLength());
        verify(controller, never()).updateValue(any(), anyBoolean());
        var valid = directory.resolve("retry.txt");
        Files.writeString(valid, "retry");
        input.loadFromExternalFile(valid.toFile(), new NullProgressMonitor());
        verify(controller, never()).updateValue(any(), anyBoolean());
        assertEquals("retry", ((StringEditorInput.StringStorage) storage).getString());
        assertEquals(5, input.getContentLength());
    }

    @Test
    void rejectedExplicitSavePreservesPendingTextAndAllowsRetry() throws Exception {
        var controller = mock(IValueController.class);
        var input = input(controller, "UTF-8");
        var storage = field(input, "stringStorage");
        var path = directory.resolve("valid.txt");
        Files.writeString(path, "replacement");
        doThrow(new IllegalStateException("Rejected update")).doNothing().when(controller).updateValue("replacement", false);
        input.loadFromExternalFile(path.toFile(), new NullProgressMonitor());
        verify(controller, never()).updateValue(any(), anyBoolean());
        assertThrows(IllegalStateException.class,
            () -> input.updateContentFromFile(new UncanceledMonitor(), input.getValue()));
        assertSame(storage, field(input, "stringStorage"));
        assertEquals("replacement", ((StringEditorInput.StringStorage) storage).getString());
        assertEquals(11, input.getContentLength());
        input.updateContentFromFile(new UncanceledMonitor(), input.getValue());
        verify(controller, times(2)).updateValue("replacement", false);
        assertEquals("replacement", ((StringEditorInput.StringStorage) storage).getString());
        assertEquals(11, input.getContentLength());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "directory", "same", "alias"})
    void binaryImportPreservesOwnedFileUntilReplacementSucceeds(String scenario) throws Exception {
        var workbenchField = DBWorkbench.class.getDeclaredField("applicationWorkbench");
        workbenchField.setAccessible(true);
        var originalWorkbench = workbenchField.get(null);
        var workbench = mock(DBPApplicationWorkbench.class);
        when(workbench.getPlatform()).thenReturn(mock(DBPPlatform.class));
        workbenchField.set(null, workbench);
        try {
            var controller = mock(IValueController.class);
            var input = input(controller, "UTF-8");
            var content = mock(DBDContent.class);
            when(controller.getValue()).thenReturn(content);
            field(input, "stringStorage", null);
            var original = directory.resolve("owned.bin");
            var originalBytes = new byte[] {1, 2, 3};
            Files.write(original, originalBytes);
            field(input, "contentFile", original.toFile());
            field(input, "contentDetached", false);
            var replacement = directory.resolve("new.bin");
            Files.write(replacement, new byte[] {9, 8});
            boolean same = scenario.equals("same") || scenario.equals("alias");
            if (same) {
                var selected = original;
                if (scenario.equals("alias")) {
                    selected = directory.resolve("link.bin");
                    Files.createSymbolicLink(selected, original);
                }
                input.loadFromExternalFile(selected.toFile(), new NullProgressMonitor());
                assertArrayEquals(originalBytes, Files.readAllBytes(original));
                verify(content, never()).updateContents(any(), any());
                input.updateContentFromFile(new UncanceledMonitor(), content);
                var storage = ArgumentCaptor.forClass(DBDContentStorage.class);
                verify(content).updateContents(any(), storage.capture());
                try (var stream = storage.getValue().getContentStream()) {
                    assertArrayEquals(originalBytes, stream.readAllBytes());
                }
                input.release();
                assertArrayEquals(originalBytes, Files.readAllBytes(original));
            } else {
                var selected = scenario.equals("missing") ? directory.resolve("missing.bin")
                    : scenario.equals("directory") ? directory : replacement;
                assertThrows(CoreException.class,
                    () -> input.loadFromExternalFile(selected.toFile(), new NullProgressMonitor()));
                assertArrayEquals(originalBytes, Files.readAllBytes(original));
                assertEquals(original.toString(), input.getPath().toOSString());
                assertEquals(false, field(input, "contentDetached"));
                verify(content, never()).updateContents(any(), any());
                input.loadFromExternalFile(replacement.toFile(), new NullProgressMonitor());
                assertFalse(Files.exists(original), "Successful replacement releases the old owned file");
                assertEquals(replacement.toString(), input.getPath().toOSString());
                assertEquals(2, input.getContentLength());
                input.release();
                assertArrayEquals(new byte[] {9, 8}, Files.readAllBytes(replacement));
            }
        } finally {
            workbenchField.set(null, originalWorkbench);
        }
    }
}
