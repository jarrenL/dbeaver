/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GaussDBPackageSourceLinesTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {Integer.MIN_VALUE, -1, 0})
    void unavailableContentLineFallsBackToFirstEditorLine(int contentLine) {
        assertEquals(1, GaussDBPackageSourceLines.toEditorLine(null, contentLine));
        assertEquals(1, GaussDBPackageSourceLines.toEditorLine("CREATE PACKAGE s.p AS\n bad;", contentLine));
    }

    @Test
    void headerOffsetCannotOverflowToNegativeEditorLine() {
        assertEquals(1, GaussDBPackageSourceLines.toEditorLine("CREATE PACKAGE s.p AS\n bad;", Integer.MAX_VALUE));
        assertEquals(1, GaussDBPackageSourceLines.toEditorLine("CREATE\nPACKAGE s.p AS\n bad;", Integer.MAX_VALUE - 1));
        assertEquals(Integer.MAX_VALUE,
            GaussDBPackageSourceLines.toEditorLine("CREATE PACKAGE s.p AS\n bad;", Integer.MAX_VALUE - 1));
    }

    @Test
    void quotedNamesAndNestedCommentsDoNotBecomeHeaderDelimiters() {
        String source = "/* AS\n /* IS */ */\nCREATE PACKAGE \"a\"\"AS\".\"IS\" AS\n bad;";
        assertEquals(4, GaussDBPackageSourceLines.toEditorLine(source, 1));
    }

    @Test
    void blankBodyLinesRemainInContentCoordinates() {
        assertEquals(4, GaussDBPackageSourceLines.toEditorLine("CREATE PACKAGE s.p AS\n\n\n bad;", 3));
    }
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
