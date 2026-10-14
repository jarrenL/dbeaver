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

import org.jkiss.dbeaver.DBException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

public class GaussDBErrorPositionTest {
    @Test
    public void dirtyPackageSaveErrorSelectsUniqueInvalidType() {
        String token = "public.no_such_table.id%TYPE";
        String editorSource = "CREATE OR REPLACE PACKAGE BODY public.test_pkg AS\n" +
            "  missing " + token + ";\nEND test_pkg;";
        var positions = GaussDBDataSource.inferInvalidTypePosition(
            editorSource,
            new DBException("Save failed", new SQLException(
                "invalid type name \"" + token + "\"", "42704", 11019))
        );

        Assertions.assertNotNull(positions);
        Assertions.assertEquals(1, positions.length);
        Assertions.assertEquals(-1, positions[0].line);
        Assertions.assertEquals(editorSource.indexOf(token), positions[0].position);
        Assertions.assertTrue(positions[0].info.contains("Approximate"));
    }

    @Test
    public void ambiguousOrUnrelatedSaveErrorDoesNotInventASelection() {
        String token = "public.no_such_table.id%TYPE";
        var error = new SQLException("invalid type name \"" + token + "\"", "42704", 11019);

        Assertions.assertNull(GaussDBDataSource.inferInvalidTypePosition(token + "\n" + token, error));
        Assertions.assertNull(GaussDBDataSource.inferInvalidTypePosition(
            "CREATE PACKAGE BODY public.test_pkg AS END test_pkg;",
            new SQLException("syntax error", "42601")));
    }
}
