/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.dbeaver.ext.postgresql.model.PostgreExecutionContext;

/** Connection defaults shared by GaussDB compatibility modes. */
public class GaussDBExecutionContext extends PostgreExecutionContext {
    public GaussDBExecutionContext(GaussDBDatabase database, String purpose) {
        super(database, purpose);
    }

    @Override
    protected String getContextDefaultsQuery() {
        // M mode treats the bare SESSION_USER token as a column. The catalog function
        // also works in ORA/PG/B modes and retains session identity after SET ROLE.
        return "SELECT current_schema(),pg_catalog.session_user()";
    }
}
