/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.code.Nullable;

/** Normalizes the one-based source line reported by GS_ERRORS for editor navigation. */
final class GaussDBPackageSourceLines {
    private GaussDBPackageSourceLines() {
    }

    static int toEditorLine(@Nullable String definition, int serverLine) {
        // On GaussDB 505 centralized, GS_ERRORS.line is already relative to the
        // complete GS_SOURCE.src value, including the CREATE PACKAGE header.
        // Adding a parsed header offset therefore moves a valid diagnostic onto
        // the following statement. Keep the definition parameter so callers on
        // catalogs without GS_SOURCE keep the same API, but never invent an
        // offset that the server did not report.
        return serverLine < 1 ? 1 : serverLine;
    }
}
