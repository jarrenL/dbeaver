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


    @Test
    public void preservesCopyDataIncludingSqlLookingRowsAndCrLf() throws Exception {
        String dump = "\\connect example\r\nCOPY public.messages (message) FROM stdin;\r\n"
            + "reset PASSWORD tomorrow\r\nCREATE ROLE fake PASSWORD 'business data';\r\n"
            + "/* unterminated business comment\r\n$body$\r\n\\.\r\n"
            + "ALTER ROLE real PASSWORD 'secret';\r\n";
        Assertions.assertEquals(dump.replace("PASSWORD 'secret'", "PASSWORD DISABLE"), sanitize(dump));
    }

    @Test
    public void preservesSqlLiteralsCommentsQuotedIdentifiersAndFunctionBodies() throws Exception {
        String dump = "-- CREATE ROLE fake PASSWORD 'comment';\n"
            + "/* outer /* nested */ ALTER USER fake PASSWORD 'comment'; */\n"
            + "CREATE FUNCTION f() RETURNS text AS $body$\n"
            + "BEGIN\nALTER ROLE fake PASSWORD 'body';\nRETURN 'PASSWORD business';\nEND;\n$body$ LANGUAGE plpgsql;\n"
            + "INSERT INTO t VALUES ('PASSWORD business; CREATE USER x PASSWORD ''data'';');\n"
            + "CREATE TABLE t2 (\"PASSWORD business\" text);\n"
            + "ALTER ROLE \"PASSWORD name\" SET application_name TO 'PASSWORD setting';\n";
        Assertions.assertEquals(dump, sanitize(dump));
    }

    @Test
    public void handlesMultilineRolePasswordsEscapesCommentsAndMultipleStatements() throws Exception {
        String dump = "CREATE ROLE \"PASSWORD name\" WITH LOGIN PASSWORD\n/* comment */ 'one''two'; "
            + "ALTER USER bob ENCRYPTED PASSWORD E'one\\\'two';\n"
            + "ALTER ROLE bob PASSWORD 'multi\nline';\n"
            + "CREATE ROLE password PASSWORD token; ALTER ROLE bob PASSWORD DISABLE;";
        String expected = "CREATE ROLE \"PASSWORD name\" WITH LOGIN PASSWORD\n/* comment */ DISABLE; "
            + "ALTER USER bob ENCRYPTED PASSWORD DISABLE;\n"
            + "ALTER ROLE bob PASSWORD DISABLE;\n"
            + "CREATE ROLE password PASSWORD DISABLE; ALTER ROLE bob PASSWORD DISABLE;";
        Assertions.assertEquals(expected, sanitize(dump));
    }

    @Test
    public void doesNotReplaceOriginalWhenDumpIsTruncated() throws Exception {
        Path dump = Files.createTempFile("gaussdb-truncated-dump-", ".sql");
        String input = "CREATE ROLE one PASSWORD 'unterminated";
        try {
            Files.writeString(dump, input);
            Assertions.assertThrows(java.io.IOException.class, () -> TestableBackupAllHandler.sanitize(dump));
            Assertions.assertEquals(input, Files.readString(dump));
        } finally {
            Files.deleteIfExists(dump);
        }
    }

    private static String sanitize(String input) throws Exception {
        Path dump = Files.createTempFile("gaussdb-dumpall-regression-", ".sql");
        try {
            Files.writeString(dump, input, StandardCharsets.ISO_8859_1);
            TestableBackupAllHandler.sanitize(dump);
            return Files.readString(dump, StandardCharsets.ISO_8859_1);
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
