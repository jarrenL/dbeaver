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

import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.sql.parser.SQLParserContext;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.model.sql.parser.SQLScriptParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GaussDBDialectTest {
    private final GaussDBDialect dialect = new GaussDBDialect();
    private final DBPPreferenceStore preferences = mock(DBPPreferenceStore.class);

    private SQLParserContext context(String sql) {
        DBPDataSource dataSource = mock(DBPDataSource.class);
        DBPDataSourceContainer container = mock(DBPDataSourceContainer.class);
        when(dataSource.getContainer()).thenReturn(container);
        when(container.getPreferenceStore()).thenReturn(preferences);
        when(container.getActualConnectionConfiguration()).thenReturn(new DBPConnectionConfiguration());
        when(dataSource.getSQLDialect()).thenReturn(dialect);
        when(preferences.getBoolean(ModelPreferences.QUERY_REMOVE_TRAILING_DELIMITER)).thenReturn(true);
        return SQLScriptParser.prepareSqlParserContext(dataSource, dialect, preferences, sql);
    }

    private List<String> parse(String sql) {
        var context = context(sql);
        return SQLScriptParser.extractScriptQueries(context, 0, sql.length(), false, false, false)
            .stream().map(SQLScriptElement::getText).map(String::trim).toList();
    }

    @Test
    void packageSpecificationIsOneStatementAndKeepsNamedEndTerminator() {
        String sql = "CREATE PACKAGE s.p AS FUNCTION f RETURN INTEGER; END p;";
        assertEquals(List.of(sql, "SELECT 1"), parse(sql + "\nSELECT 1;"));
    }

    @Test
    void packageBodyKeepsNestedFunctionAndFollowingStatementSeparate() {
        String sql = "CREATE OR REPLACE PACKAGE BODY s.p AS\n"
            + "FUNCTION f RETURN INTEGER AS BEGIN RETURN 1; END f;\nEND p;";
        assertEquals(List.of(sql, "SELECT 1"), parse(sql + "\nSELECT 1;"));
    }

    @Test
    void ordinarySqlAndDollarQuotedFunctionsStillSplit() {
        assertEquals(List.of("SELECT 1", "SELECT 2"), parse("SELECT 1; SELECT 2;"));
        String sql = "CREATE FUNCTION f() RETURNS integer AS $$ BEGIN RETURN 1; END; $$ LANGUAGE plpgsql";
        assertEquals(List.of(sql + ";", "SELECT f()"), parse(sql + "; SELECT f();"));
    }

    @Test
    void nonOracleModesKeepTransactionStatementsSeparate() {
        GaussDBDataSource dataSource = mock(GaussDBDataSource.class);
        GaussDBDatabase database = mock(GaussDBDatabase.class);
        when(dataSource.getDefaultInstance()).thenReturn(database);
        when(dataSource.getAvailableInstances()).thenReturn(List.of(database));
        dialect.setDataSource(dataSource);
        for (DBCompatibilityEnum mode : new DBCompatibilityEnum[]{DBCompatibilityEnum.M, DBCompatibilityEnum.POSTGRES}) {
            when(database.getCompatibility()).thenReturn(mode);
            assertEquals(List.of("BEGIN", "SELECT 1", "COMMIT"), parse("BEGIN; SELECT 1; COMMIT;"));
        }
    }

    @Test
    void packageCommandsDoNotStartDeclarationBlocks() {
        assertEquals(List.of("DROP PACKAGE s.p", "ALTER PACKAGE s.q COMPILE", "SELECT 1"),
            parse("DROP PACKAGE s.p; ALTER PACKAGE s.q COMPILE; SELECT 1;"));
    }

    @Test
    void packageInitializationAndControlFlowRemainOneStatement() {
        String sql = "CREATE PACKAGE BODY s.p AS\n"
            + "FUNCTION f RETURN INTEGER AS v INTEGER := 0; BEGIN\n"
            + "IF v = 0 THEN FOR i IN 1..3 LOOP v := v + i; END LOOP; ELSE v := -1; END IF;\n"
            + "RETURN v; END f;\nBEGIN NULL; END p;";
        assertEquals(List.of(sql, "SELECT 1"), parse(sql + "\nSELECT 1;"));
    }

    @Test
    void slashSeparatorDoesNotBecomeSqlOrBreakDivision() {
        String sql = "CREATE PACKAGE s.p AS FUNCTION f RETURN INTEGER; END p;";
        assertEquals(List.of(sql, "SELECT 6 / 2"), parse(sql + "\n/\nSELECT 6 / 2;"));
    }

    @Test
    void commentsStringsAndQuotedIdentifiersDoNotSplitPackages() {
        String sql = "CREATE PACKAGE BODY s.\"Odd;Name\" AS\n"
            + "-- END; / is not code\n"
            + "FUNCTION f RETURN VARCHAR2 AS BEGIN\n"
            + "/* END; BEGIN */ RETURN 'END; it''s / text';\n"
            + "END f; END \"Odd;Name\";";
        assertEquals(List.of(sql, "SELECT 1"), parse(sql + "\nSELECT 1;"));
    }

    @Test
    void nestedLocalRoutinesAndExceptionHandlerStayInsidePackage() {
        String sql = "CREATE PACKAGE BODY s.p AS\n"
            + "FUNCTION f RETURN INTEGER AS\n"
            + "FUNCTION inner_f RETURN INTEGER AS BEGIN RETURN 7; END;\n"
            + "BEGIN RETURN inner_f(); EXCEPTION WHEN OTHERS THEN RETURN -1; END;\nEND;";
        assertEquals(List.of(sql, "SELECT 1"), parse(sql + "\nSELECT 1;"));
    }

    @Test
    void leadingCommentsAndCrLfSlashAreHandled() {
        String sql = "/* package fixture */\r\nCREATE PACKAGE s.p AS FUNCTION f RETURN INTEGER; END p;";
        assertEquals(List.of(sql, "SELECT 6 / 2"),
            parse(sql + "\r\n  /  \r\nSELECT 6 / 2;"));
    }

    @Test
    void ordinaryIfNotExistsAndCaseExpressionsAreNotPlsqlBlocks() {
        assertEquals(List.of("CREATE TABLE IF NOT EXISTS t(id integer)", "SELECT 1"),
            parse("CREATE TABLE IF NOT EXISTS t(id integer); SELECT 1;"));
        assertEquals(List.of("SELECT CASE WHEN 1=1 THEN 2 ELSE 3 END;", "SELECT 4"),
            parse("SELECT CASE WHEN 1=1 THEN 2 ELSE 3 END; SELECT 4;"));
    }

    @Test
    void explicitSelectionKeepsPackageSemicolon() {
        String sql = "CREATE PACKAGE s.p AS FUNCTION f RETURN INTEGER; END p;";
        var context = context(sql);
        assertEquals(sql, SQLScriptParser.extractActiveQuery(context, 0, sql.length()).getText());
    }
}
