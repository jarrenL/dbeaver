/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.ui.internal;

import org.jkiss.dbeaver.debug.ui.DBGEditorAdvisor;

public class GaussDBSourceEditorAdvisor implements DBGEditorAdvisor {
    @Override
    public String getSourceFolderId() {
        return "postgresql.source.view"; //$NON-NLS-1$
    }
}
