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
    @ValueSource(strings = {"SELECT 42", "SELECT id FROM t WHERE id=7", "SELECT pg_sleep(1)"})
    void plainSelectRetainsExistingRecovery(String sql) {
        assertTrue(SQLQueryRecoveryPolicy.mayReplay(new SQLQuery(null, sql)), sql);
    }
}
