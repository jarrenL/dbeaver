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
import org.jkiss.dbeaver.ext.gaussdb.GaussDBConstants;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreLanguage;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreProcedureKind;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

public class GaussDBProcedureTest {

    private GaussDBSchema schema;

    private GaussDBDatabase database;

    private GaussDBDataSource dataSource;

    private PostgreLanguage language;

    private TestProcedure procedure;

    @BeforeEach
    public void setUp() {
        schema = Mockito.mock(GaussDBSchema.class);
        database = Mockito.mock(GaussDBDatabase.class);
        dataSource = Mockito.mock(GaussDBDataSource.class);
        language = Mockito.mock(PostgreLanguage.class);
        Mockito.when(schema.getDatabase()).thenReturn(database);
        Mockito.when(schema.getDataSource()).thenReturn(dataSource);
        Mockito.when(schema.getName()).thenReturn("public");
        Mockito.when(dataSource.getSQLDialect()).thenReturn(new GaussDBDialect());
        Mockito.when(language.getName()).thenReturn("plpgsql");
        Mockito.when(language.toString()).thenReturn("plpgsql");

        procedure = new TestProcedure(schema);
        procedure.setName("refresh_totals");
    }

    @Test
    public void initializesKindsForNewProcedureAndFunction() {
        Assertions.assertEquals(PostgreProcedureKind.p, procedure.getKind());
        Assertions.assertEquals(PostgreProcedureKind.f, new GaussDBFunction(schema).getKind());
    }

    @Test
    public void recognizesBothOracleCompatibilityValuesWithoutDuplicatingExistingBlock() {
        String source = "BEGIN\n\tNULL;\nEND;";

        Mockito.when(database.getDatabaseCompatibleMode()).thenReturn(GaussDBConstants.GAUSSDB_ORACLE_COMPATIBLE_MODE_C);
        String centralized = procedure.generate(language, source);
        Assertions.assertEquals(centralized.indexOf("END;"), centralized.lastIndexOf("END;"));
        Assertions.assertTrue(centralized.contains(source));
        Assertions.assertFalse(centralized.contains("LANGUAGE"));

        Mockito.when(database.getDatabaseCompatibleMode()).thenReturn(GaussDBConstants.GAUSSDB_ORACLE_COMPATIBLE_MODE);
        String distributed = procedure.generate(language, source);
        Assertions.assertTrue(distributed.contains(source));
        Assertions.assertFalse(distributed.contains("LANGUAGE"));
    }

    @Test
    public void generatesACompleteOraclePlaceholderBlockForNewRoutine() {
        Mockito.when(database.getDatabaseCompatibleMode()).thenReturn(GaussDBConstants.GAUSSDB_ORACLE_COMPATIBLE_MODE_C);

        String ddl = procedure.generate(language, "\n\t-- Enter routine body here\n");

        Assertions.assertTrue(ddl.startsWith("CREATE OR REPLACE PROCEDURE"));
        Assertions.assertTrue(ddl.contains("BEGIN"));
        Assertions.assertTrue(ddl.contains("-- Enter routine body here"));
        Assertions.assertFalse(ddl.contains("function body"));
        Assertions.assertFalse(ddl.contains("LANGUAGE"));
        Assertions.assertTrue(ddl.contains("\tNULL;"));
        Assertions.assertTrue(ddl.stripTrailing().endsWith("END;"));
    }

    @Test
    public void usesGaussPlSqlSyntaxForProcedureOutsideOracleCompatibilityMode() {
        for (String compatibilityMode : List.of("PG", "MYSQL", "TD")) {
            Mockito.when(database.getDatabaseCompatibleMode()).thenReturn(compatibilityMode);

            String ddl = procedure.generate(language, "BEGIN\n\tNULL;\nEND;");

            Assertions.assertTrue(ddl.startsWith("CREATE OR REPLACE PROCEDURE"));
            Assertions.assertFalse(ddl.contains("LANGUAGE"));
            Assertions.assertFalse(ddl.contains("$$"));
            Assertions.assertTrue(ddl.contains("AS"));
            Assertions.assertTrue(ddl.stripTrailing().endsWith("END;"));
        }
    }

    @Test
    public void doesNotUseFunctionLanguageSyntaxForProcedure() {
        Mockito.when(database.getDatabaseCompatibleMode()).thenReturn(GaussDBConstants.GAUSSDB_ORACLE_COMPATIBLE_MODE_C);
        Mockito.when(language.getName()).thenReturn("sql");
        Mockito.when(language.toString()).thenReturn("sql");

        String ddl = procedure.generate(language, "BEGIN\n\tNULL;\nEND;");

        Assertions.assertFalse(ddl.contains("LANGUAGE"));
        Assertions.assertFalse(ddl.contains("$$"));
        Assertions.assertTrue(ddl.stripTrailing().endsWith("END;"));
    }

    @Test
    public void preservesEditedCompleteDdlForCreateAction() throws DBException {
        String editedDdl = "CREATE OR REPLACE PROCEDURE public.refresh_totals() AS\nBEGIN\n\tNULL;\nEND;";
        DBRProgressMonitor monitor = Mockito.mock(DBRProgressMonitor.class);
        procedure.setObjectDefinitionText(editedDdl);

        Assertions.assertEquals(editedDdl, procedure.getCreateStatement(monitor));
    }

    @Test
    public void keepsPostgreSqlSyntaxForFunctionOutsideOracleMode() {
        Mockito.when(database.getDatabaseCompatibleMode()).thenReturn("PG");
        TestFunction function = new TestFunction(schema);
        function.setName("calculate_total");

        String ddl = function.generate(language, "integer", "BEGIN\n\tRETURN 1;\nEND;");

        Assertions.assertTrue(ddl.startsWith("CREATE OR REPLACE FUNCTION"));
        Assertions.assertTrue(ddl.contains("RETURNS integer"));
        Assertions.assertTrue(ddl.contains("LANGUAGE plpgsql"));
        Assertions.assertTrue(ddl.contains("AS $$"));
    }

    private static class TestProcedure extends GaussDBProcedure {
        TestProcedure(GaussDBSchema schema) {
            super(schema);
        }

        String generate(PostgreLanguage language, String body) {
            return generateGaussDBFunctionDeclaration(language, null, body);
        }
    }

    private static class TestFunction extends GaussDBFunction {
        TestFunction(GaussDBSchema schema) {
            super(schema);
        }

        String generate(PostgreLanguage language, String returnTypeName, String body) {
            return generateGaussDBFunctionDeclaration(language, returnTypeName, body);
        }
    }
}
