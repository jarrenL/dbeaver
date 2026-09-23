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
    @Test
    void configuredDialectClassifiesGaussKeywordsCaseInsensitively() {
        new PostgreServerGaussDB(mock(GaussDBDataSource.class)).configureDialect(dialect);
        for (String word : List.of("PACKAGE", "BODY", "DBCOMPATIBILITY", "DISTRIBUTE", "REPLICATION")) {
            assertEquals(org.jkiss.dbeaver.model.DBPKeywordType.KEYWORD, dialect.getKeywordType(word));
            assertEquals(org.jkiss.dbeaver.model.DBPKeywordType.KEYWORD,
                dialect.getKeywordType(word.toLowerCase(java.util.Locale.ROOT)));
        }
    }

    @Test
    void configuredDialectClassifiesGaussTypesAndFunctions() {
        new PostgreServerGaussDB(mock(GaussDBDataSource.class)).configureDialect(dialect);
        for (String type : List.of("VARCHAR2", "NUMBER", "INT1", "CLOB", "DATETIME")) {
            assertEquals(org.jkiss.dbeaver.model.DBPKeywordType.TYPE, dialect.getKeywordType(type), type);
        }
        // SQL keyword precedence must remain intact for identifier quoting.
        assertEquals(org.jkiss.dbeaver.model.DBPKeywordType.KEYWORD, dialect.getKeywordType("YEAR"));
        org.junit.jupiter.api.Assertions.assertTrue(dialect.getDataTypes(null).contains("YEAR"));
        for (String function : List.of("gs_encrypt_aes128", "hll_cardinality", "vector_norm")) {
            assertEquals(org.jkiss.dbeaver.model.DBPKeywordType.FUNCTION, dialect.getKeywordType(function), function);
        }
        org.junit.jupiter.api.Assertions.assertNull(dialect.getKeywordType("not_a_known_gauss_keyword_947"));
    }

    @Test
    void dialectTypeEnumerationDoesNotExposeMutableInternalCollection() {
        var first = dialect.getDataTypes(null);
        org.junit.jupiter.api.Assertions.assertTrue(first.containsAll(List.of("VARCHAR2", "NUMBER", "INT1")));
        first.clear();
        org.junit.jupiter.api.Assertions.assertTrue(dialect.getDataTypes(null).contains("VARCHAR2"));
    }

    @Test
    void quotedIdentifiersRoundTripEmbeddedQuotesAndTerminators() {
        for (String identifier : List.of("select", "MixedCase", "带空格 名", "a\"b", "semi;colon", "a.b")) {
            String quoted = dialect.getQuotedIdentifier(identifier, true, true);
            assertEquals('"' + identifier.replace("\"", "\"\"") + '"', quoted);
            assertEquals(identifier, dialect.getUnquotedIdentifier(quoted, true));
        }
    }

    @Test
    void standardStringLiteralEscapingPreservesUnicodeAndSeparators() {
        for (String value : List.of("", "O'Brien", "中文;--", "line1\nline2", "a\\b", "''")) {
            String quoted = dialect.getQuotedString(value);
            assertEquals("'" + value.replace("'", "''") + "'", quoted);
            assertEquals(value, dialect.getUnquotedString(quoted));
        }
    }

    @Test
    void historicalQuotedLiteralsDoNotIntroduceStatements() {
        for (String sql : List.of("SELECT '中文;值'", "SELECT 'it''s;quoted'", "SELECT $$a;b$$",
            "SELECT ';--not comment'", "SELECT '/*;*/'")) {
            // Dollar-quoted blocks retain their delimiter under the production parser contract.
            assertEquals(List.of(sql + (sql.contains("$$") ? ";" : ""), "SELECT 2"), parse(sql + "; SELECT 2;"));
        }
    }

    @Test
    void historicalAliasesAndNestedExpressionsKeepStatementBoundaries() {
        for (String sql : List.of(
            "SELECT CASE WHEN a.id IS NULL THEN 'missing;' ELSE 'ok' END AS result FROM t a",
            "SELECT a.id FROM t a JOIN (SELECT id FROM u WHERE id > 0) b ON a.id=b.id",
            "SELECT row_number() OVER (PARTITION BY a.kind ORDER BY a.id) AS n FROM t a",
            "WITH q AS (SELECT 1 AS id) SELECT id FROM q UNION ALL SELECT 2")) {
            // CASE is tracked as a block; preserving its final semicolon is intentional.
            assertEquals(List.of(sql + (sql.startsWith("SELECT CASE") ? ";" : ""), "SELECT 3"), parse(sql + "; SELECT 3;"));
        }
    }

    @Test
    void historicalDmlAndDistributedDdlRemainIntact() {
        for (String sql : List.of(
            "CREATE TABLE t(id integer, name varchar(20)) DISTRIBUTE BY HASH(id)",
            "MERGE INTO t USING u ON (t.id=u.id) WHEN MATCHED THEN UPDATE SET name=u.name",
            "INSERT INTO t VALUES(1,'a') ON DUPLICATE KEY UPDATE name='b'",
            "EXECUTE DIRECT ON (dn_1) 'SELECT 1; SELECT 2'")) {
            assertEquals(List.of(sql, "SELECT 4"), parse(sql + "; SELECT 4;"));
        }
    }

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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "E''", "e'a;b'", "E'a'';b'", "E'it\\'s;still text'", "E'-- ; not comment'",
        "E'/* ; END; */'", "E'line1\\nline2;中文'", "E'line1\nline2;中文'"
    })
    void escapeStringsSurviveFullScriptExtraction(String literal) {
        String query = "SELECT " + literal;
        assertEquals(List.of(query), parse(query));
        assertEquals(List.of(query, "SELECT 2"), parse(query + ";\nSELECT 2;"));
        assertEquals(List.of("SELECT 0", query), parse("SELECT 0;\r\n" + query));
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

    @Test
    void quotedAliasesContainingTerminatorsRemainInOneQuery() {
        String sql = "SELECT t.id AS \"END;别名\" FROM \"Mixed;Table\" t ORDER BY \"END;别名\"";
        assertEquals(List.of(sql, "SELECT 2"), parse(sql + "; SELECT 2;"));
    }

    @Test
    void updateSubqueryAndDeleteUsingRemainSeparateCommands() {
        String update = "UPDATE t SET v=(SELECT max(v) FROM q WHERE q.id=t.id) WHERE t.id=1";
        String delete = "DELETE FROM t USING q WHERE t.id=q.id";
        assertEquals(List.of(update, delete, "SELECT 1"), parse(update + ";" + delete + ";SELECT 1;"));
    }

    @Test
    void unionAndQuotedTextDoNotCreatePhantomCommands() {
        String sql = "SELECT 'UNION; END;' AS v UNION ALL SELECT 'BEGIN; /* not comment */'";
        assertEquals(List.of(sql, "SELECT 1"), parse(sql + ";SELECT 1;"));
    }

    @Test
    void multipleSlashTerminatedProceduresKeepTheirBodies() {
        String first = "CREATE PROCEDURE p AS BEGIN NULL; END;";
        String second = "CREATE PROCEDURE q AS BEGIN NULL; EXCEPTION WHEN OTHERS THEN NULL; END;";
        assertEquals(List.of(first, second, "SELECT 1"),
            parse(first + "\r\n/\r\n" + second + "\r\n/\r\nSELECT 1;"));
    }
}
