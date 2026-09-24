/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.model.sql;

import net.sf.jsqlparser.statement.select.PlainSelect;
import org.jkiss.code.NotNull;
import org.jkiss.utils.CommonUtils;

/** Structural gate for automatic SQL editor replay, not proof of function purity. */
public final class SQLQueryRecoveryPolicy {
    private SQLQueryRecoveryPolicy() {
    }

    public static boolean mayReplay(@NotNull SQLQuery query) {
        // Parsing can fail for vendor DML. Unknown is not evidence that replay is safe.
        if (!(query.getStatement() instanceof PlainSelect select) || query.getParseError() != null) {
            return false;
        }
        // WITH may contain modifying statements, and SELECT INTO creates objects.
        return CommonUtils.isEmpty(select.getIntoTables()) && CommonUtils.isEmpty(select.getWithItemsList());
    }
}
