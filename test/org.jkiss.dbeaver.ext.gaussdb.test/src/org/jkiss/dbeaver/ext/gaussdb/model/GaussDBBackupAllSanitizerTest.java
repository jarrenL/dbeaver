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
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.dbeaver.ext.postgresql.tasks.PostgreDatabaseBackupAllHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class GaussDBBackupAllSanitizerTest {
    @Test
    public void removesQuotedAndTokenRolePasswords() throws Exception {
        Path dump = Files.createTempFile("gaussdb-dumpall-test-", ".sql");
        try {
            Files.writeString(
                dump,
                "CREATE ROLE one PASSWORD 'sha256secret';\n" +
                    "CREATE ROLE two PASSWORD encryptedSecret;\n" +
                    "CREATE ROLE disabled PASSWORD DISABLE;\n",
                StandardCharsets.ISO_8859_1);

            TestableBackupAllHandler.sanitize(dump);

            String result = Files.readString(dump, StandardCharsets.ISO_8859_1);
            Assertions.assertFalse(result.contains("sha256secret"));
            Assertions.assertFalse(result.contains("encryptedSecret"));
            Assertions.assertEquals(3, result.lines().filter(line -> line.contains("PASSWORD DISABLE")).count());
            Assertions.assertTrue(result.lines().allMatch(line -> line.endsWith(";")));
        } finally {
            Files.deleteIfExists(dump);
        }
    }

    private static class TestableBackupAllHandler extends PostgreDatabaseBackupAllHandler {
        private static void sanitize(Path dump) throws Exception {
            suppressRolePasswords(dump);
        }
    }
}
