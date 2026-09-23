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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the production predicate used by the SQL editor's execution confirmation. */
class GaussDBSQLSafetyTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE public.t SET amount = 1",
        "DELETE FROM public.t",
        "/* WHERE id=1 */ UPDATE public.t SET amount=1",
        "-- WHERE id=1\nDELETE FROM public.t",
        "UPDATE public.t SET label='WHERE id=1'",
        "UPDATE public.t SET amount=(SELECT max(amount) FROM public.s WHERE id=1)",
        "DELETE FROM public.t USING public.s",
        "UPDATE ONLY public.t SET amount=1",
        "DELETE FROM ONLY public.t",
        "UPDATE \"模式\".\"表\" SET \"where\"=1",
        "WITH s AS (SELECT id FROM public.s WHERE id=1) UPDATE public.t SET amount=1",
        "WITH s AS (SELECT id FROM public.s WHERE id=1) DELETE FROM public.t"
    })
    void missingOuterWhereRequiresConfirmation(String sql) {
        assertTrue(new SQLQuery(null, sql).isDeleteUpdateDangerous(), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE public.t SET amount=1 WHERE id=1",
        "DELETE FROM public.t WHERE id=1",
        "UPDATE public.t SET amount=(SELECT max(amount) FROM public.s) WHERE id=1",
        "DELETE FROM public.t USING public.s WHERE t.id=s.id",
        "UPDATE ONLY public.t SET amount=1 WHERE id=1",
        "DELETE FROM ONLY public.t WHERE id=1",
        "UPDATE public.t SET label='WHERE' WHERE id IN (SELECT id FROM public.s)",
        "DELETE FROM public.t WHERE EXISTS (SELECT 1 FROM public.s WHERE s.id=t.id)",
        "SELECT 'DELETE FROM public.t'",
        "INSERT INTO public.t(id) VALUES(1)",
        "UPDATE public.t SET amount=1 WHERE 1=1",
        "DELETE FROM public.t WHERE 1=1"
    })
    void outerWhereOrNonDeleteUpdateDoesNotTriggerThisSpecificRule(String sql) {
        // This is a WHERE-presence rule, not a proof that the operation is safe.
        assertFalse(new SQLQuery(null, sql).isDeleteUpdateDangerous(), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DROP TABLE public.t", "DROP TABLE IF EXISTS public.t CASCADE",
        "DROP VIEW public.v", "DROP SCHEMA \"模式\" CASCADE",
        "DROP INDEX public.idx", "DROP SEQUENCE public.seq"
    })
    void dropStatementsRequireTheirSeparateConfirmation(String sql) {
        assertTrue(new SQLQuery(null, sql).isDropDangerous(), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT 'DROP TABLE public.t'", "/* DROP TABLE public.t */ SELECT 1",
        "UPDATE public.t SET label='DROP TABLE public.t' WHERE id=1"
    })
    void quotedOrCommentedDropDoesNotTriggerDropConfirmation(String sql) {
        assertFalse(new SQLQuery(null, sql).isDropDangerous(), sql);
    }
}
