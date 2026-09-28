/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.model.sql;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SQLQueryRecoveryPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE t SET id=2 WHERE id=1",
        "SELECT change_data(1)",
        "SELECT id FROM t FOR UPDATE",
        "SELECT id FROM t UNION ALL SELECT id FROM s",
        "WITH changed AS (DELETE FROM t RETURNING id) SELECT * FROM changed",
        "SELECT ("
    })
    void replacementMustNotReuseEarlierReplayApproval(String replacement) {
        var query = new SQLQuery(null, "SELECT id FROM t WHERE id=7");
        assertTrue(SQLQueryRecoveryPolicy.mayReplay(query));
        query.setText(replacement);
        assertFalse(SQLQueryRecoveryPolicy.mayReplay(query), replacement);
        query.reset();
        assertTrue(SQLQueryRecoveryPolicy.mayReplay(query));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "INSERT INTO t(id) VALUES (7)",
        "INSERT INTO t(id) SELECT 7 FROM pg_sleep(120)",
        "INSERT INTO t VALUES(1) ON DUPLICATE KEY UPDATE id=2",
        "UPDATE t SET id=2 WHERE id=1",
        "DELETE FROM t WHERE id=1",
        "MERGE INTO t USING s ON (t.id=s.id) WHEN MATCHED THEN UPDATE SET id=2",
        "CALL change_data(1)",
        "BEGIN change_data(1); END;",
        "CREATE TABLE t(id integer)",
        "COMMIT", "ROLLBACK",
        "SELECT id INTO backup_t FROM t",
        "WITH changed AS (DELETE FROM t RETURNING id) SELECT * FROM changed",
        "WITH read_only AS (SELECT 1) SELECT * FROM read_only",
        "SELECT * FROM", ""
    })
    void writesAndUncertainStatementsAreNotAutomaticallyReplayed(String sql) {
        assertFalse(SQLQueryRecoveryPolicy.mayReplay(new SQLQuery(null, sql)), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT 42", "SELECT id FROM t WHERE id=7", "SELECT 'function()' AS label /* ignored() */",
        "SELECT id FROM t ORDER BY id LIMIT 10 OFFSET 2", "SELECT DISTINCT id FROM t"})
    void plainSelectRetainsExistingRecovery(String sql) {
        assertTrue(SQLQueryRecoveryPolicy.mayReplay(new SQLQuery(null, sql)), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT pg_sleep(1)", "SELECT change_data(1)", "SELECT public.change_data(1)",
        "SELECT nextval('seq')", "SELECT NEXT VALUE FOR seq", "SELECT * FROM change_data(1)",
        "SELECT id FROM t WHERE change_data(id)=1", "SELECT id FROM t ORDER BY change_data(id)",
        "SELECT change_data(id) FROM t GROUP BY change_data(id)",
        "SELECT id FROM t GROUP BY id HAVING change_data(id)=1",
        "SELECT (SELECT change_data(1))", "SELECT * FROM (SELECT change_data(1)) nested",
        "SELECT a.id FROM a JOIN b ON change_data(a.id)=b.id",
        "SELECT sum(id) OVER () FROM t", "SELECT * FROM t FOR UPDATE",
        "SELECT * FROM t FOR SHARE", "SELECT * FROM t FOR UPDATE SKIP LOCKED",
        "SELECT * FROM (SELECT * FROM t FOR UPDATE) nested",
        "SELECT id FROM t LIMIT change_data(1)", "SELECT id FROM t OFFSET change_data(1)",
        "SELECT id FROM t QUALIFY change_data(id)=1",
        "SELECT DISTINCT ON (change_data(id)) id FROM t",
        "SELECT id FROM t FETCH FIRST change_data(1) ROWS ONLY",
        "SELECT id FROM t WINDOW w AS (PARTITION BY change_data(id))"
    })
    void functionSequenceAndLockingQueriesAreNotAutomaticallyReplayed(String sql) {
        assertFalse(SQLQueryRecoveryPolicy.mayReplay(new SQLQuery(null, sql)), sql);
    }
}
