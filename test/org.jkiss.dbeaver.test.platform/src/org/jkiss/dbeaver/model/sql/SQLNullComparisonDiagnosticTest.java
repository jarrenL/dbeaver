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
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SQLNullComparisonDiagnosticTest {
    private List<SQLQueryRecognitionProblemInfo> warnings(String sql) {
        var syntax = new SQLSyntaxManager();
        syntax.init(BasicSQLDialect.INSTANCE, mock(DBPPreferenceStore.class));
        var context = new SQLQueryRecognitionContext(new VoidProgressMonitor(), null, false, false,
            syntax, BasicSQLDialect.INSTANCE);
        assertNotNull(SQLQueryModelRecognizer.recognizeQuery(context, sql));
        return context.getProblems().stream()
            .filter(p -> p.getMessage().contains("IS NOT NULL")).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT 1 FROM t WHERE 1 = NULL", "SELECT 1 FROM t WHERE NULL <> 1",
        "SELECT 1 FROM t WHERE 1 != NULL", "SELECT 1 FROM t WHERE NULL < 1",
        "SELECT 1 FROM t WHERE 1 > NULL", "SELECT 1 FROM t WHERE NULL <= 1",
        "SELECT 1 FROM t WHERE 1 >= NULL", "SELECT 1 FROM t WHERE 1 = (NULL)",
        "SELECT 1 FROM t WHERE 1 = /* comment */ null",
        "SELECT CASE WHEN 1 = NULL THEN 1 ELSE 2 END FROM t",
        "SELECT 1 FROM t WHERE EXISTS (SELECT 1 FROM s WHERE 1 = NULL)",
        "UPDATE t SET a=2 WHERE a=NULL", "DELETE FROM t WHERE NULL=a"
    })
    void directNullComparisonProducesOneWarningWithSourceRange(String sql) {
        var warnings = warnings(sql);
        assertEquals(1, warnings.size(), sql);
        var warning = warnings.getFirst();
        assertEquals(SQLQueryRecognitionProblemInfo.Severity.WARNING, warning.getSeverity());
        var range = warning.getInterval();
        assertTrue(range.a >= 0 && range.b < sql.length() && range.a <= range.b);
        assertTrue(sql.substring(range.a, range.b + 1).toUpperCase(java.util.Locale.ROOT).contains("NULL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT 1 FROM t WHERE 1 IS NULL", "SELECT 1 FROM t WHERE 1 IS NOT NULL",
        "SELECT 1 FROM t WHERE 'NULL' = 'NULL'", "SELECT 'a = NULL' FROM t",
        "SELECT 1 FROM t /* WHERE a=NULL */", "SELECT 1 FROM t WHERE a=b",
        "SELECT 1 FROM t WHERE a=coalesce(NULL, 1)", "SELECT 1 FROM t WHERE a=(SELECT NULL)",
        "SELECT 1 FROM t WHERE a=NULLIF(b, 1)", "SELECT 1 FROM t WHERE a IN (NULL, 1)",
        "SELECT 1 FROM t WHERE a=1 -- =NULL", "SELECT 1 FROM t WHERE \"NULL\"=1"
    })
    void nullTestsStringsCommentsAndNonliteralOperandsDoNotTriggerRule(String sql) {
        assertTrue(warnings(sql).isEmpty(), sql);
    }
}
