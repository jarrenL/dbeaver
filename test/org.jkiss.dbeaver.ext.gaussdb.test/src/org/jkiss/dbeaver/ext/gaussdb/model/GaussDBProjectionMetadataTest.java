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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GaussDBProjectionMetadataTest {
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
