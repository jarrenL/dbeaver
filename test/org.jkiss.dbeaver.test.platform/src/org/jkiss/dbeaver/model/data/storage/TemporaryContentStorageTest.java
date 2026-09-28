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

package org.jkiss.dbeaver.model.data.storage;

import org.jkiss.dbeaver.model.app.DBPPlatform;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TemporaryContentStorageTest {
    @TempDir Path directory;
    @ParameterizedTest
    @ValueSource(strings = {"success", "cancel-before", "cancel-during"})
    void cloningPreservesOriginalAndRejectsCanceledPartialCopy(String stage) throws Exception {
        var source = directory.resolve("original.bin");
        var bytes = new byte[100003];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        Files.write(source, bytes);
        var copies = Files.createDirectory(directory.resolve("copies"));
        var platform = mock(DBPPlatform.class);
        var monitor = mock(DBRProgressMonitor.class);
        when(platform.getTempFolder(any(), anyString())).thenReturn(copies);
        if (stage.equals("cancel-before")) when(monitor.isCanceled()).thenReturn(true);
        if (stage.equals("cancel-during")) {
            doAnswer(call -> { when(monitor.isCanceled()).thenReturn(true); return null; })
                .when(monitor).worked(anyInt());
        }
        var original = new TemporaryContentStorage(platform, source, "UTF-8", false);
        if (!stage.equals("success")) {
            assertThrows(IOException.class, () -> original.cloneStorage(monitor));
            assertArrayEquals(bytes, Files.readAllBytes(source));
            try (var files = Files.list(copies)) { assertEquals(0, files.count()); }
            reset(monitor);
        }
        var clone = (TemporaryContentStorage) original.cloneStorage(monitor);
        assertNotEquals(source, clone.getDataFile());
        assertEquals("UTF-8", clone.getCharset());
        assertArrayEquals(bytes, Files.readAllBytes(clone.getDataFile()));
        original.release();
        assertArrayEquals(bytes, Files.readAllBytes(source));
        assertArrayEquals(bytes, Files.readAllBytes(clone.getDataFile()));
        clone.release();
        assertFalse(Files.exists(clone.getDataFile()));
        assertTrue(Files.exists(source));
        try (var files = Files.list(copies)) { assertEquals(0, files.count()); }
    }
}
