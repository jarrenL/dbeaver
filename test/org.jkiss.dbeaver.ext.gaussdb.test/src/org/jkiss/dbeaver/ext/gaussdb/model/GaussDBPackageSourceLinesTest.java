/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GaussDBPackageSourceLinesTest {
    @Test
    void mapsSpecAndBodyContentPastCreateHeader() {
        assertEquals(3, GaussDBPackageSourceLines.toEditorLine("CREATE OR REPLACE PACKAGE BODY s.p AS\n f\n bad;", 2));
        assertEquals(2, GaussDBPackageSourceLines.toEditorLine("CREATE PACKAGE s.p IS\n bad;", 1));
        assertEquals(1, GaussDBPackageSourceLines.toEditorLine("CREATE PACKAGE s.p AS bad;", 1));
    }

    @Test
    void ignoresQuotedNamesAndCommentsWhileCountingMultilineHeaders() {
        String source = "-- AS\nCREATE OR REPLACE\nPACKAGE BODY \"AS\".\"IS\" /* AS */ AS\r\n f\n bad;";
        assertEquals(5, GaussDBPackageSourceLines.toEditorLine(source, 2));
    }

    @Test
    void preservesUnavailableOrUnwrappedCoordinates() {
        assertEquals(2, GaussDBPackageSourceLines.toEditorLine(null, 2));
        assertEquals(2, GaussDBPackageSourceLines.toEditorLine("FUNCTION f RETURN INTEGER;", 2));
    }
}
