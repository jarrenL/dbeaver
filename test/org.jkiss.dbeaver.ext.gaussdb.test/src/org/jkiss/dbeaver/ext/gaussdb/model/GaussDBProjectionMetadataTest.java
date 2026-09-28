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

import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLQueryType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.junit.jupiter.api.Test;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBProjectionMetadataTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT id FROM public.accounts FOR UPDATE",
        "SELECT id INTO copied_accounts FROM public.accounts"
    })
    void lockingAndIntoSelectsRetainModifyingClassification(String sql) {
        var query = new SQLQuery(null, sql);
        assertEquals(SQLQueryType.SELECT, query.getType());
        assertTrue(query.isModifying());
        assertFalse(query.isPlainSelect());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "UPDATE public.accounts SET amount=1|true|false|true",
        "UPDATE public.accounts SET amount=1 WHERE id=1|false|false|true",
        "DELETE FROM public.accounts|true|false|true",
        "DELETE FROM public.accounts WHERE id=1|false|false|true",
        "DROP TABLE public.accounts|false|true|true",
        "SELECT id FROM public.accounts|false|false|false"
    })
    void safetyChecksTrackReplacementAndReset(String replacement, boolean unrestricted, boolean drop, boolean mutating) {
        var query = new SQLQuery(null, "SELECT id FROM public.accounts");
        assertFalse(query.isMutatingStatement());
        query.setText(replacement);
        assertEquals(unrestricted, query.isDeleteUpdateDangerous());
        assertEquals(drop, query.isDropDangerous());
        assertEquals(mutating, query.isMutatingStatement());
        assertEquals(mutating, query.isModifying());
        query.reset();
        assertFalse(query.isDeleteUpdateDangerous());
        assertFalse(query.isDropDangerous());
        assertFalse(query.isMutatingStatement());
    }

    @Test
    void parameterExpansionAndResetPreserveExecutionIdentity() {
        String original = "SELECT id FROM public.accounts WHERE id=:id";
        SQLQuery query = new SQLQuery(null, original, 17, original.length());
        var parameter = new org.jkiss.dbeaver.model.sql.SQLQueryParameter(
            mock(org.jkiss.dbeaver.model.sql.SQLSyntaxManager.class), 0, "id", ":id", original.indexOf(":id"), 3);
        var parameters = java.util.List.of(parameter);
        query.setParameters(parameters);
        Object identity = new Object();
        query.setData(identity);
        query.setResultSetLimit(5, 20);
        assertEquals(SQLQueryType.SELECT, query.getType());
        for (String value : java.util.List.of("111", "222")) {
            parameter.setValue(value);
            org.jkiss.dbeaver.model.sql.SQLUtils.fillQueryParameters(query, parameters);
            assertEquals(original.replace(":id", value), query.getText());
            assertTrue(query.getStatement().toString().contains(value));
            assertEquals("accounts", query.getEntityMetadata(false).getEntityName());
            assertSame(parameters, query.getParameters());
            assertSame(identity, query.getData());
            assertEquals(17, query.getOffset());
            assertEquals(original.length(), query.getLength());
            assertEquals(5, query.getResultsOffset());
            assertEquals(20, query.getResultsMaxRows());
            query.reset();
            assertEquals(original, query.getText());
            assertTrue(query.getStatement().toString().contains(":id"));
        }
    }

    @Test
    void replacingJoinQueryClearsDerivedExportNames() {
        SQLQuery query = new SQLQuery(null,
            "SELECT a.id,b.id FROM public.accounts a JOIN audit.other_table b ON a.id=b.id");
        assertEquals(SQLQueryType.SELECT, query.getType());
        assertFalse(query.getAllSelectEntitiesNames().isEmpty());
        query.setText("SELECT id FROM public.accounts");
        assertEquals(SQLQueryType.SELECT, query.getType());
        assertTrue(query.getAllSelectEntitiesNames().isEmpty());
        query.reset();
        assertEquals(SQLQueryType.SELECT, query.getType());
        assertEquals(2, query.getAllSelectEntitiesNames().size());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SELECT name FROM audit.other_table|SELECT|other_table|1|false",
        "DELETE FROM audit.other_table WHERE id=1|DELETE|other_table|0|false",
        "SELECT id FROM public.accounts UNION SELECT id FROM audit.other_table|SELECT|-|0|false",
        "''|UNKNOWN|-|0|true",
        "SELECT (|UNKNOWN|-|0|true"
    })
    void replacingAndResettingTextCannotRetainOldParseState(
        String replacement, SQLQueryType type, String table, int columns, boolean invalid
    ) {
        String original = "SELECT id,a.* FROM public.accounts a";
        SQLQuery query = new SQLQuery(null, original);
        assertEquals(SQLQueryType.SELECT, query.getType());
        assertEquals("accounts", query.getEntityMetadata(false).getEntityName());
        assertEquals(1, query.getSelectItemAsteriskIndex());
        query.setText(replacement);
        assertEquals(type, query.getType());
        assertEquals(columns, query.getSelectItemCount());
        assertEquals(-1, query.getSelectItemAsteriskIndex());
        if (table.equals("-")) {
            assertNull(query.getEntityMetadata(false));
            assertNull(query.getEntityMetadata(true));
        } else {
            assertEquals(table, query.getEntityMetadata(false).getEntityName());
            assertEquals("audit", query.getEntityMetadata(false).getSchemaName());
        }
        assertEquals(invalid, query.getParseError() != null);
        query.reset();
        assertEquals(original, query.getText());
        assertEquals(SQLQueryType.SELECT, query.getType());
        assertNull(query.getParseError());
        assertEquals("accounts", query.getEntityMetadata(false).getEntityName());
        assertEquals(2, query.getSelectItemCount());
        assertEquals(1, query.getSelectItemAsteriskIndex());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT id FROM public.accounts UNION SELECT id FROM audit.other_table",
        "SELECT id FROM public.accounts UNION ALL SELECT id FROM audit.other_table",
        "SELECT id FROM public.accounts INTERSECT SELECT id FROM audit.other_table",
        "SELECT id FROM public.accounts EXCEPT SELECT id FROM audit.other_table",
        "SELECT id FROM public.accounts UNION ALL SELECT id FROM public.accounts",
        "SELECT id AS \"编号\" FROM public.accounts UNION SELECT id FROM audit.other_table ORDER BY 1",
        "WITH q AS (SELECT id FROM public.accounts) SELECT id FROM q UNION ALL SELECT id FROM audit.other_table",
        "SELECT id FROM public.accounts UNION SELECT id FROM audit.other_table INTERSECT SELECT id FROM public.accounts"
    })
    void setQueriesAreSelectsWithoutInventingSingleTableEditTarget(String sql) {
        SQLQuery query = new SQLQuery(null, sql);
        assertEquals(SQLQueryType.SELECT, query.getType());
        assertNull(query.getEntityMetadata(false));
        assertNull(query.getEntityMetadata(true));
        assertFalse(query.isPlainSelect(), "Set operations are not simple single-source SELECTs");
        assertFalse(query.isModifying(), "Classifying a set query as SELECT must not mark it as modifying");
        assertFalse(query.isMutatingStatement());
        assertEquals(-1, query.getSelectItemAsteriskIndex());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "UPDATE public.accounts a SET amount=(SELECT max(b.amount) FROM audit.other_table b) WHERE a.id=1|UPDATE|public|accounts",
        "UPDATE public.accounts a SET amount=b.amount FROM audit.other_table b WHERE a.id=b.id|UPDATE|public|accounts",
        "DELETE FROM public.accounts a WHERE EXISTS (SELECT 1 FROM audit.other_table a WHERE a.id=1)|DELETE|public|accounts",
        "DELETE FROM public.accounts a USING audit.other_table b WHERE a.id=b.id|DELETE|public|accounts",
        "INSERT INTO public.accounts(id) SELECT id FROM audit.other_table|INSERT|public|accounts",
        "UPDATE \"中文模式\".\"中文表\" AS \"别名\" SET amount=1 WHERE \"别名\".id=1|UPDATE|中文模式|中文表"
    })
    void dmlTargetIsNotConfusedWithJoinedOrNestedSource(String sql, SQLQueryType type, String schema, String table) {
        SQLQuery query = new SQLQuery(null, sql);
        assertEquals(type, query.getType());
        var target = query.getEntityMetadata(false);
        assertNotNull(target);
        assertEquals(schema, target.getSchemaName());
        assertEquals(table, target.getEntityName());
    }

    static Stream<Arguments> mixedSources() {
        return Stream.of("JOIN", "LEFT JOIN", "RIGHT JOIN", "FULL JOIN", "INNER JOIN")
            .flatMap(join -> Stream.of("cte-right", "cte-left", "derived-left")
                .map(orientation -> Arguments.of(join, orientation)));
    }

    @ParameterizedTest
    @MethodSource("mixedSources")
    void joinsPreservePhysicalSourceWithoutInventingVirtualSource(String join, String orientation) {
        String from = switch (orientation) {
            case "cte-right" -> "public.accounts a " + join + " q v";
            case "cte-left" -> "q v " + join + " public.accounts a";
            default -> "(SELECT id FROM audit.other_table) v " + join + " public.accounts a";
        };
        String prefix = orientation.startsWith("cte") ? "WITH q AS (SELECT id FROM audit.other_table) " : "";
        SQLQuery query = new SQLQuery(null, prefix + "SELECT a.id,v.id FROM " + from + " ON a.id=v.id");
        assertEquals(SQLQueryType.SELECT, query.getType());
        assertEquals(2, query.getSelectItemCount());
        assertNull(query.getEntityMetadata(false), "A JOIN is not a single physical source");
        var physical = query.getSelectItem(0).getEntityMetaData();
        assertNotNull(physical);
        assertEquals("public", physical.getSchemaName());
        assertEquals("accounts", physical.getEntityName());
        assertNull(query.getSelectItem(1).getEntityMetaData(), "CTE/derived alias is not an update table");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "WITH RECURSIVE q(id) AS (SELECT 1 UNION ALL SELECT id+1 FROM q WHERE id<3) SELECT q.id FROM q",
        "WITH q AS (SELECT 1 AS id), r AS (SELECT id FROM q) SELECT r.id FROM r",
        "SELECT q.id FROM (WITH q AS (SELECT 1 AS id) SELECT id FROM q) q"
    })
    void recursiveChainedAndNestedVirtualSourcesRemainUnresolved(String sql) {
        SQLQuery query = new SQLQuery(null, sql);
        assertEquals(SQLQueryType.SELECT, query.getType());
        assertNull(query.getEntityMetadata(false));
        assertNull(query.getSelectItem(0).getEntityMetaData());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Q|q|true", "q|Q|true", "\"q\"|q|true", "q|\"q\"|true",
        "\"Q\"|q|false", "q|\"Q\"|false", "\"Q\"|\"Q\"|true",
        "q|public.q|false"
    })
    void cteIdentifierMatchingUsesGaussDBCaseAndQualification(String cte, String reference, boolean virtual) {
        DBPDataSource source = mock(DBPDataSource.class);
        when(source.getSQLDialect()).thenReturn(new GaussDBDialect());
        SQLQuery query = new SQLQuery(source, "WITH " + cte + " AS (SELECT 1 AS id) SELECT x.id FROM " + reference + " x");
        assertEquals(SQLQueryType.SELECT, query.getType());
        if (virtual) {
            assertNull(query.getEntityMetadata(false));
            assertNull(query.getSelectItem(0).getEntityMetaData());
        } else {
            assertNotNull(query.getEntityMetadata(false));
            assertNotNull(query.getSelectItem(0).getEntityMetaData());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "WITH q AS (SELECT id FROM public.accounts) SELECT q.id FROM q",
        "WITH q AS (SELECT id FROM public.accounts) SELECT x.id FROM q x",
        "WITH \"中文\" AS (SELECT id FROM public.accounts) SELECT x.id FROM \"中文\" x",
        "SELECT q.id FROM (SELECT id FROM public.accounts) q",
        "SELECT q.id FROM (SELECT id FROM public.accounts UNION ALL SELECT id FROM audit.other_table) q"
    })
    void virtualRelationsAreNotReportedAsPhysicalTables(String sql) {
        SQLQuery query = new SQLQuery(null, sql);
        assertEquals(SQLQueryType.SELECT, query.getType());
        assertNull(query.getEntityMetadata(false), "A virtual relation name is not a physical update target");
        assertNull(query.getSelectItem(0).getEntityMetaData(), "Do not invent a table from the derived alias");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "WITH q AS (SELECT 1) SELECT a.id FROM public.accounts a",
        "SELECT a.id FROM public.accounts a WHERE EXISTS (SELECT 1 FROM audit.other_table a WHERE a.id=1)",
        "SELECT a.id FROM public.accounts a ORDER BY a.id",
        "SELECT a.id FROM public.accounts a WHERE a.id IN (SELECT b.id FROM audit.other_table b)"
    })
    void nestedOrUnrelatedAliasesDoNotHideOuterPhysicalSource(String sql) {
        SQLQuery query = new SQLQuery(null, sql);
        assertEquals(SQLQueryType.SELECT, query.getType());
        var metadata = query.getSelectItem(0).getEntityMetaData();
        assertNotNull(metadata);
        assertEquals("accounts", metadata.getEntityName());
        assertEquals("public", metadata.getSchemaName());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "amount+1", "amount*2", "coalesce(amount,0)", "count(*)", "sum(amount)",
        "CASE WHEN amount>0 THEN 1 ELSE 0 END", "CAST(amount AS numeric(10,2))",
        "row_number() OVER (ORDER BY id)", "(SELECT max(amount) FROM public.other_table)",
        "'literal AS alias'", "substring(label,1,2)", "amount/NULLIF(id,0)"
    })
    void expressionAliasIsRetainedAsProjectionName(String expression) {
        SQLQuery query = new SQLQuery(null, "SELECT " + expression + " AS result_value FROM public.accounts");
        assertEquals(SQLQueryType.SELECT, query.getType());
        assertEquals(1, query.getSelectItemCount());
        assertEquals("result_value", query.getSelectItem(0).getName());
        assertFalse(query.getSelectItem(0).isPlainColumn());
        assertNull(query.getSelectItem(0).getEntityMetaData(), "Computed columns must not acquire writable table metadata");
    }

    @ParameterizedTest
    @ValueSource(strings = {"JOIN", "LEFT JOIN", "RIGHT JOIN", "FULL JOIN", "INNER JOIN"})
    void qualifiedColumnsResolveEachJoinedTableAlias(String join) {
        SQLQuery query = new SQLQuery(null, "SELECT a.id AS account_id,b.id AS other_id FROM public.accounts a "
            + join + " audit.other_table b ON a.id=b.id");
        assertEquals(SQLQueryType.SELECT, query.getType());
        assertEquals(2, query.getSelectItemCount());
        var first = query.getSelectItem(0);
        var second = query.getSelectItem(1);
        // Source column names are intentionally retained for result-set edit mapping.
        assertEquals("id", first.getName());
        assertEquals("id", second.getName());
        assertTrue(first.isPlainColumn());
        assertEquals("accounts", first.getEntityMetaData().getEntityName());
        assertEquals("public", first.getEntityMetaData().getSchemaName());
        assertEquals("other_table", second.getEntityMetaData().getEntityName());
        assertEquals("audit", second.getEntityMetaData().getSchemaName());
    }

    @Test
    void quotedChineseTableAliasResolvesOriginalIdentifier() {
        SQLQuery query = new SQLQuery(null, "SELECT \"别名\".\"列\" FROM \"模式\".\"表\" AS \"别名\"");
        assertEquals(SQLQueryType.SELECT, query.getType());
        var metadata = query.getSelectItem(0).getEntityMetaData();
        assertEquals("模式", metadata.getSchemaName());
        assertEquals("表", metadata.getEntityName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "a.*"})
    void actualWildcardProjectionIsIdentified(String projection) {
        SQLQuery query = new SQLQuery(null, "SELECT a.id," + projection + " FROM public.accounts a");
        assertEquals(SQLQueryType.SELECT, query.getType());
        assertEquals(1, query.getSelectItemAsteriskIndex());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.amount*2", "'*'", "count(*)", "a.amount AS \"*\"", "a.amount+1 AS \"a*b\""})
    void starInExpressionOrAliasIsNotWildcard(String projection) {
        SQLQuery query = new SQLQuery(null, "SELECT " + projection + " FROM public.accounts a");
        assertEquals(SQLQueryType.SELECT, query.getType());
        assertEquals(-1, query.getSelectItemAsteriskIndex());
    }
}
