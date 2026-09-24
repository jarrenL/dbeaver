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

package org.jkiss.dbeaver.model.sql;

import org.jkiss.dbeaver.model.impl.sql.BasicSQLDialect;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.sql.semantics.SQLQueryModelRecognizer;
import org.jkiss.dbeaver.model.sql.semantics.SQLQueryRecognitionContext;
import org.jkiss.dbeaver.model.sql.semantics.SQLQueryRecognitionProblemInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SQLQueryQualityDiagnosticTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT a FROM t WHERE a LIKE '%tail'",
        "SELECT a FROM t WHERE a LIKE '_tail'",
        "SELECT a FROM t WHERE a ILIKE '%中文'",
        "SELECT a FROM t WHERE a NOT LIKE '%tail'",
        "SELECT a FROM t WHERE a LIKE /* pattern */ '%tail'",
        "SELECT a FROM t WHERE a LIKE '%a''b'",
        "SELECT a FROM t WHERE a LIKE '%tail' ESCAPE '!'",
        "SELECT a FROM t WHERE a LIKE '%tail' ESCAPE ''",
        "SELECT a FROM t WHERE a LIKE '%tail' ESCAPE '_'",
        "SELECT a FROM t WHERE a LIKE '_tail' ESCAPE '%'",
        "SELECT 1 WHERE 'abc' LIKE '%c'",
        "SELECT a FROM t WHERE EXISTS (SELECT 1 FROM s WHERE s.a LIKE '%x')"
    })
    void leadingLikeWildcardsProduceAdvisory(String sql) { check(sql, "LIKE ", 1); }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT a FROM t WHERE a LIKE 'prefix%'",
        "SELECT a FROM t WHERE a LIKE 'prefix_'",
        "SELECT a FROM t WHERE a LIKE ''",
        "SELECT a FROM t WHERE a LIKE '!%tail' ESCAPE '!'",
        "SELECT a FROM t WHERE a LIKE '!_tail' ESCAPE '!'",
        "SELECT a FROM t WHERE a LIKE '%%tail' ESCAPE '%'",
        "SELECT a FROM t WHERE a LIKE '__tail' ESCAPE '_'",
        "SELECT a FROM t WHERE a LIKE pattern_column",
        "SELECT a FROM t WHERE a LIKE '%tail' ESCAPE escape_column",
        "SELECT a FROM t WHERE a LIKE '%tail' ESCAPE 'ab'",
        "SELECT a FROM t WHERE a LIKE concat('%', a)",
        "SELECT a FROM t WHERE a LIKE '%a' || 'b'",
        "SELECT 'LIKE ''%tail''' FROM t",
        "SELECT a FROM t -- LIKE '%tail'"
    })
    void escapedLiteralPrefixesAndUnknownPatternsAreNotReported(String sql) { check(sql, "LIKE ", 0); }

    private void check(String sql, String messagePrefix, int expected) {
        var syntax = new SQLSyntaxManager();
        syntax.init(BasicSQLDialect.INSTANCE, mock(DBPPreferenceStore.class));
        var context = new SQLQueryRecognitionContext(new VoidProgressMonitor(), null, false, false,
            syntax, BasicSQLDialect.INSTANCE);
        assertNotNull(SQLQueryModelRecognizer.recognizeQuery(context, sql));
        var warnings = context.getProblems().stream().filter(p -> p.getMessage().startsWith(messagePrefix)).toList();
        assertEquals(expected, warnings.size(), sql);
        for (var warning : warnings) {
            assertEquals(SQLQueryRecognitionProblemInfo.Severity.WARNING, warning.getSeverity());
            var range = warning.getInterval();
            assertTrue(range.a >= 0 && range.b < sql.length() && range.a <= range.b);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM t", "SELECT t.* FROM t", "SELECT /* a */ * FROM t",
        "SELECT a FROM (SELECT * FROM t) s", "SELECT * FROM t WHERE EXISTS (SELECT 1 FROM s)"
    })
    void starProducesAdvisory(String sql) { check(sql, "SELECT *", 1); }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT COUNT(*) FROM t", "SELECT a*b FROM t", "SELECT '*' FROM t",
        "SELECT a FROM t -- SELECT *", "SELECT 't.*' FROM t", "SELECT a,b FROM t"
    })
    void nonProjectionStarsAreNotReported(String sql) { check(sql, "SELECT *", 0); }

    @ParameterizedTest
    @ValueSource(strings = {
        "INSERT INTO t VALUES(1)", "INSERT INTO t SELECT a FROM s",
        "INSERT INTO t DEFAULT VALUES", "INSERT INTO t /* columns */ VALUES(1,2)",
        "INSERT INTO t VALUES(1),(2)"
    })
    void implicitInsertColumnsProduceAdvisory(String sql) { check(sql, "INSERT ", 1); }

    @ParameterizedTest
    @ValueSource(strings = {
        "INSERT INTO t(a) VALUES(1)", "INSERT INTO t(a,b) SELECT a,b FROM s",
        "SELECT 'INSERT INTO t VALUES(1)'", "INSERT INTO t(a) /* keep */ VALUES(1)",
        "SELECT 1 -- INSERT INTO t VALUES(1)"
    })
    void explicitInsertColumnsAreNotReported(String sql) { check(sql, "INSERT ", 0); }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT a FROM t ORDER BY 1", "SELECT a,b FROM t ORDER BY 2 DESC",
        "SELECT a FROM t ORDER BY /* position */ 1 ASC",
        "SELECT a FROM (SELECT a FROM t ORDER BY 1) s",
        "SELECT row_number() OVER (ORDER BY a) AS rn FROM t ORDER BY 1"
    })
    void ordinalOrderingProducesAdvisory(String sql) { check(sql, "ORDER BY ", 1); }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT a FROM t ORDER BY a", "SELECT a AS label FROM t ORDER BY label",
        "SELECT 'ORDER BY 1' FROM t", "SELECT a FROM t -- ORDER BY 1",
        "SELECT row_number() OVER (ORDER BY 1) FROM t",
        "SELECT row_number() OVER (ORDER BY a) AS rn FROM t ORDER BY rn"
    })
    void namedOrderingAndWindowConstantsAreNotOrdinalSort(String sql) { check(sql, "ORDER BY ", 0); }
}
