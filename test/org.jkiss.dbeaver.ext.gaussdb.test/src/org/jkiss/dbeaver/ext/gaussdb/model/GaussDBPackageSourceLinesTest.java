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
    void serverLineCannotOverflowThroughClientSideHeaderArithmetic() {
        assertEquals(Integer.MAX_VALUE,
            GaussDBPackageSourceLines.toEditorLine("CREATE PACKAGE s.p AS\n bad;", Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE - 1,
            GaussDBPackageSourceLines.toEditorLine("CREATE\nPACKAGE s.p AS\n bad;", Integer.MAX_VALUE - 1));
    }

    @Test
    void centralized505CoordinatesAlreadyMatchCompleteServerSource() {
        String body = "CREATE OR REPLACE PACKAGE BODY review_live.review_pkg AS\n"
            + " FUNCTION value_of RETURN INTEGER AS\n"
            + " v review_live.missing_dependency.id%TYPE;\n"
            + " BEGIN\n RETURN 7;\n END;\nEND review_pkg;";
        String specification = "CREATE OR REPLACE PACKAGE review_live.review_pkg AS\n"
            + " v review_live.missing_dependency.id%TYPE;\n"
            + " FUNCTION value_of RETURN INTEGER;\nEND review_pkg;";

        assertEquals(3, GaussDBPackageSourceLines.toEditorLine(body, 3));
        assertTrue(body.split("\\R", -1)[GaussDBPackageSourceLines.toEditorLine(body, 3) - 1]
            .contains("missing_dependency"));
        assertEquals(2, GaussDBPackageSourceLines.toEditorLine(specification, 2));
        assertTrue(specification.split("\\R", -1)[GaussDBPackageSourceLines.toEditorLine(specification, 2) - 1]
            .contains("missing_dependency"));
    }

    @Test
    void preservesServerCoordinatesRegardlessOfHeaderFormatting() {
        assertEquals(3, GaussDBPackageSourceLines.toEditorLine("CREATE PACKAGE s.p AS\n\n\n bad;", 3));
        assertEquals(2, GaussDBPackageSourceLines.toEditorLine(
            "/* AS\n /* IS */ */\nCREATE PACKAGE \"a\"\"AS\".\"IS\" AS\n bad;", 2));
        assertEquals(2, GaussDBPackageSourceLines.toEditorLine(
            "-- AS\nCREATE OR REPLACE\nPACKAGE BODY \"AS\".\"IS\" /* AS */ AS\r\n f\n bad;", 2));
        assertEquals(1, GaussDBPackageSourceLines.toEditorLine("CREATE PACKAGE s.p AS bad;", 1));
    }

    @Test
    void preservesUnavailableOrUnwrappedCoordinates() {
        assertEquals(2, GaussDBPackageSourceLines.toEditorLine(null, 2));
        assertEquals(2, GaussDBPackageSourceLines.toEditorLine("FUNCTION f RETURN INTEGER;", 2));
    }
}
