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
package org.jkiss.dbeaver.model;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.impl.app.DefaultValueEncryptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class DefaultValueEncryptorHistoricalTest {
    @TempDir
    Path directory;

    private DefaultValueEncryptor encryptor() {
        return new DefaultValueEncryptor(DefaultValueEncryptor.makeSecretKeyFromPassword("synthetic-key-01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "fixture-password", "中文𠀀\"'\\\n\u0000", "0123456789abcdef", "0123456789abcdefX"})
    void encryptedTemporaryFileCanBeReadByANewInstance(String text) throws Exception {
        byte[] clear = text.getBytes(StandardCharsets.UTF_8);
        byte[] before = clear.clone();
        byte[] encrypted = encryptor().encryptValue(clear);
        assertArrayEquals(before, clear);
        assertTrue(encrypted.length >= 32);
        assertEquals(0, encrypted.length % 16);
        assertFalse(Arrays.equals(clear, encrypted));
        Path file = directory.resolve("fixture-credentials.bin");
        Files.write(file, encrypted);
        assertArrayEquals(before, encryptor().decryptValue(Files.readAllBytes(file)));
        assertArrayEquals(encrypted, Files.readAllBytes(file));
    }

    @Test
    void repeatedEncryptionUsesDifferentInitializationVectors() throws Exception {
        var encryptor = encryptor();
        byte[] value = "synthetic secret".getBytes(StandardCharsets.UTF_8);
        byte[] first = encryptor.encryptValue(value);
        byte[] second = encryptor.encryptValue(value);
        assertFalse(Arrays.equals(Arrays.copyOf(first, 16), Arrays.copyOf(second, 16)));
        assertArrayEquals(value, encryptor.decryptValue(first));
        assertArrayEquals(value, encryptor.decryptValue(second));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 15, 16, 17, 31, 33, 47})
    void incompleteFileIsRejectedWithoutOverwritingIt(int length) throws Exception {
        byte[] complete = encryptor().encryptValue("fixture long enough for two blocks".getBytes(StandardCharsets.UTF_8));
        byte[] incomplete = Arrays.copyOf(complete, length);
        Path file = directory.resolve("truncated-credentials.bin");
        Files.write(file, incomplete);
        DBException error = assertThrows(DBException.class, () -> encryptor().decryptValue(Files.readAllBytes(file)));
        assertEquals("Error decrypting value", error.getMessage());
        assertArrayEquals(incomplete, Files.readAllBytes(file));
    }

    @Test
    void invalidPaddingIsRejectedAndValidFileRemainsReadable() throws Exception {
        var encryptor = encryptor();
        byte[] clear = new byte[16];
        byte[] good = encryptor.encryptValue(clear);
        byte[] damaged = good.clone();
        // The final clear block contains 16 padding bytes. Flip its last byte through CBC chaining.
        damaged[31] ^= 1;
        assertThrows(DBException.class, () -> encryptor.decryptValue(damaged));
        assertArrayEquals(clear, encryptor.decryptValue(good));
    }
}
