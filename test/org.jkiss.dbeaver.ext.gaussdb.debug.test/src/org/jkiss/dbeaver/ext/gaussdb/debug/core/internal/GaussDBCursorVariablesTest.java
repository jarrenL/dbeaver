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
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.dbeaver.debug.DBGVariable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GaussDBCursorVariablesTest {
    private GaussDBDebugVariable variable(String name, String type, String value, String packageName, int frame) {
        return new GaussDBDebugVariable(name, type, value, packageName, false, frame);
    }

    private GaussDBDebugVariable cursor(String name, String value) {
        return variable(name, "refcursor", value, null, 0);
    }

    private DBGVariable<?> aggregate(GaussDBDebugVariable cursor) {
        List<DBGVariable<?>> result = GaussDBCursorVariables.aggregate(List.of(cursor));
        assertEquals(1, result.size());
        return result.getFirst();
    }

    private Map<String, DBGVariable<?>> children(DBGVariable<?> variable) {
        Map<String, DBGVariable<?>> attributes = new LinkedHashMap<>();
        for (DBGVariable<?> child : variable.getChildren()) {
            assertNull(attributes.put(child.getName(), child), "No duplicated cursor attributes");
        }
        return attributes;
    }

    @Test
    void parses507OpenedCursorWithoutLosingThePortalName() {
        DBGVariable<?> result = aggregate(cursor("cur",
            "{name:\t<unnamed portal 1>\nis_open:\tt\nfound:\tf\nnot_found:\tf\nrow_count:\t0}"));
        assertEquals("(ROWCOUNT=0, ISOPEN=true, FOUND=false, NOTFOUND=false)", result.getVal());
        Map<String, DBGVariable<?>> children = children(result);
        assertEquals("<unnamed portal 1>", children.get("NAME").getVal());
        assertEquals("true", children.get("%ISOPEN").getVal());
        assertEquals("false", children.get("%FOUND").getVal());
        assertEquals("false", children.get("%NOTFOUND").getVal());
        assertEquals("0", children.get("%ROWCOUNT").getVal());
        assertTrue(result.isReadOnly());
        assertTrue(children.values().stream().allMatch(DBGVariable::isReadOnly));
    }

    @ParameterizedTest
    @CsvSource({"t,t,f,1,true,true,false", "t,f,t,2,true,false,true", "f,f,f,0,false,false,false"})
    void fetchHitMissAndClosedStatusAreSeparateSnapshots(
        String opened, String found, String notFound, String count,
        String expectedOpened, String expectedFound, String expectedNotFound
    ) {
        DBGVariable<?> result = aggregate(cursor("cur", "{name:\tportal\nis_open:\t" + opened
            + "\nfound:\t" + found + "\nnot_found:\t" + notFound + "\nrow_count:\t" + count + "}"));
        Map<String, DBGVariable<?>> attributes = children(result);
        assertEquals(expectedOpened, attributes.get("%ISOPEN").getVal());
        assertEquals(expectedFound, attributes.get("%FOUND").getVal());
        assertEquals(expectedNotFound, attributes.get("%NOTFOUND").getVal());
        assertEquals(count, attributes.get("%ROWCOUNT").getVal());
    }

    @Test
    void optionalMissingFieldsDoNotBecomeFabricatedFalseOrZero() {
        DBGVariable<?> result = aggregate(cursor("cur", "{is_open:\tt}"));
        assertEquals("(ISOPEN=true)", result.getVal());
        assertEquals(java.util.Set.of("%ISOPEN"), children(result).keySet());
    }

    @Test
    void explicitNullIsDifferentFromMissingAndFalse() {
        DBGVariable<?> result = aggregate(cursor("cur", "{is_open:\tt\nfound:\tNULL\nrow_count:\tNULL}"));
        Map<String, DBGVariable<?>> attributes = children(result);
        assertEquals("NULL", attributes.get("%FOUND").getVal());
        assertEquals("NULL", attributes.get("%ROWCOUNT").getVal());
        assertFalse(attributes.containsKey("%NOTFOUND"));
        assertEquals("(ROWCOUNT=NULL, ISOPEN=true, FOUND=NULL)", result.getVal());
    }

    @Test
    void rowCountPreserves64BitValueWithoutIntegerTruncation() {
        DBGVariable<?> result = aggregate(cursor("cur", "{is_open:\tt\nrow_count:\t9223372036854775807}"));
        assertEquals("9223372036854775807", children(result).get("%ROWCOUNT").getVal());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"<unnamed portal 1>", "named portal: with spaces", "NULL"})
    void portalOnlyAndUnknownValuesRemainVisibleWithoutInventedStatus(String raw) {
        DBGVariable<?> result = aggregate(cursor("cur", raw));
        assertEquals(raw, result.getVal());
        assertFalse(children(result).keySet().stream().anyMatch(name -> name.startsWith("%")));
        assertTrue(result.isReadOnly());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{is_open:\tmaybe\nrow_count:\t2}",
        "{is_open:\tt\nrow_count:\tnot-a-number}",
        "{is_open:\tt\nrow_count:\t-1}",
        "{is_open:\tt\nrow_count:\t1.5}",
        "{is_open:\tt\nrow_count:\t1; DROP TABLE t}",
        "{is_open:\tt\nunknown_flag:\tt}",
        "{is_open:\tt\nis_open:\tf}",
        "{is_open:\tt\nfound:\tf",
        "prefix {is_open:\tt}",
        "{is_open:\tt} suffix"
    })
    void malformedOrUnrecognizedInlineStatusKeepsTheEntireOriginalText(String raw) {
        DBGVariable<?> result = aggregate(cursor("cur", raw));
        assertEquals(raw, result.getVal());
        assertFalse(children(result).keySet().stream().anyMatch(name -> name.startsWith("%")));
    }

    @Test
    void ordinaryTextThatLooksLikeCursorStatusIsNotParsedOrMadeReadOnly() {
        GaussDBDebugVariable ordinary = variable("description", "text", "{is_open:\tt\nrow_count:\t1}", null, 0);
        DBGVariable<?> result = aggregate(ordinary);
        assertSame(ordinary, result);
        assertEquals("{is_open:\tt\nrow_count:\t1}", result.getVal());
        assertTrue(result.getChildren().isEmpty());
        assertFalse(result.isReadOnly());
    }

    @ParameterizedTest
    @ValueSource(strings = {"refcursor", "REFCURSOR", "sys_refcursor", "SYS_REFCURSOR",
        "pg_catalog.refcursor", "pg_catalog.sys_refcursor", " REFCURSOR "})
    void recognizesSupportedCursorTypeNames(String type) {
        DBGVariable<?> result = aggregate(variable("cur", type, "{is_open:\tt}", "finance_pkg", 2));
        assertEquals("(ISOPEN=true)", result.getVal());
        assertEquals(type, result.getDataTypeName());
        assertEquals("finance_pkg", ((GaussDBDebugVariable) result).getPackageName());
        assertEquals(2, result.getLineNumber());
        assertTrue(result.isReadOnly());
    }

    @ParameterizedTest
    @ValueSource(strings = {"my_refcursor", "refcursor_array", "text", "varchar"})
    void cursorLikeTypeNamesAreNotTreatedAsRefcursor(String type) {
        GaussDBDebugVariable ordinary = variable("cur", type, "{is_open:\tt}", null, 0);
        assertSame(ordinary, aggregate(ordinary));
        assertFalse(ordinary.isReadOnly());
    }

    @Test
    void separateFramesAndPackageMetadataCannotLeakThroughNormalization() {
        DBGVariable<?> first = aggregate(variable("cur", "refcursor", "{row_count:\t1}", "pkg_one", 0));
        DBGVariable<?> second = aggregate(variable("cur", "refcursor", "{row_count:\t9}", "pkg_two", 3));
        assertEquals("1", children(first).get("%ROWCOUNT").getVal());
        assertEquals("9", children(second).get("%ROWCOUNT").getVal());
        assertEquals(0, first.getLineNumber());
        assertEquals(3, second.getLineNumber());
        assertEquals("pkg_one", ((GaussDBDebugVariable) first).getPackageName());
        assertEquals("pkg_two", ((GaussDBDebugVariable) second).getPackageName());
    }

    @Test
    void normalizationDoesNotMutateInputRowsOrExposeMutableChildList() {
        GaussDBDebugVariable original = cursor("cur", "{is_open:\tt}");
        DBGVariable<?> result = aggregate(original);
        assertEquals("{is_open:\tt}", original.getVal());
        assertThrows(UnsupportedOperationException.class, () -> result.getChildren().clear());
    }

    @Test
    void summaryRetainsTheExactRawProtocolTextForInspection() {
        String raw = "{name:\tportal_1\nis_open:\tt\nrow_count:\t1}";
        GaussDBCursorVariable result = (GaussDBCursorVariable) aggregate(cursor("cur", raw));
        assertEquals("(ROWCOUNT=1, ISOPEN=true)", result.getVal());
        assertEquals(raw, result.getRawValue());
    }

    @Test
    void cursorAndAttributeCannotBeMutatedThroughTheConcreteVariableClass() {
        GaussDBCursorVariable result = (GaussDBCursorVariable) aggregate(cursor("cur", "{is_open:\tt}"));
        GaussDBDebugVariable opened = (GaussDBDebugVariable) children(result).get("%ISOPEN");
        assertThrows(UnsupportedOperationException.class, () -> result.setValue("another_portal"));
        assertThrows(UnsupportedOperationException.class, () -> opened.setValue("false"));
        assertEquals("(ISOPEN=true)", result.getVal());
        assertEquals("true", opened.getVal());
    }

    @Test
    void packageCursorExposesItsExactPackageAsAReadOnlyChild() {
        DBGVariable<?> cursor = aggregate(variable("cur", "refcursor", "{is_open:\tt}", "Finance Package", 0));
        GaussDBDebugVariable packageAttribute = (GaussDBDebugVariable) children(cursor).get("PACKAGE");

        assertEquals("Finance Package", cursor.getScopeName());
        assertEquals("Finance Package", packageAttribute.getVal());
        assertEquals("text", packageAttribute.getDataTypeName());
        assertTrue(packageAttribute.isReadOnly());
        assertThrows(UnsupportedOperationException.class, () -> packageAttribute.setValue("other_package"));
    }

    private GaussDBDebugVariable hidden(String cursorName, String attribute, String value, String packageName, int frame) {
        return variable("_gsdb_cursor_attri" + cursorName + "__" + attribute,
            "rowcount".equals(attribute) ? "bigint" : "boolean", value, packageName, frame);
    }

    @Test
    void nameBasedHiddenAttributesAreCollectedIndependentlyOfResultRowOrder() {
        var count = hidden("cur", "rowcount", "8", null, 0);
        var ordinary = variable("amount", "integer", "42", null, 0);
        var opened = hidden("cur", "isopen", "t", null, 0);
        var found = hidden("cur", "found", "t", null, 0);
        var notFound = hidden("cur", "notfound", "f", null, 0);

        List<DBGVariable<?>> result = GaussDBCursorVariables.aggregate(List.of(
            count, ordinary, cursor("cur", "portal_1"), opened, found, notFound));

        assertEquals(2, result.size());
        assertSame(ordinary, result.getFirst());
        assertEquals("cur", result.get(1).getName());
        assertEquals("(ROWCOUNT=8, ISOPEN=true, FOUND=true, NOTFOUND=false)", result.get(1).getVal());
        assertEquals("8", count.getVal(), "Input protocol rows must not be mutated");
        assertEquals("t", opened.getVal());
    }

    @Test
    void similarCursorNamesDoNotStealEachOthersAttributes() {
        List<DBGVariable<?>> result = GaussDBCursorVariables.aggregate(List.of(
            cursor("c", "portal_1"), hidden("c10", "rowcount", "10", null, 0),
            cursor("c10", "portal_2"), hidden("c", "rowcount", "1", null, 0)));
        assertEquals(2, result.size());
        assertEquals("1", children(result.get(0)).get("%ROWCOUNT").getVal());
        assertEquals("10", children(result.get(1)).get("%ROWCOUNT").getVal());
    }

    @Test
    void hiddenAttributesUseLiteralCursorNameMatchingEvenForQuotedIdentifiers() {
        String name = "c$[数据]";
        List<DBGVariable<?>> result = GaussDBCursorVariables.aggregate(List.of(
            cursor(name, "portal_1"), hidden(name, "isopen", "t", null, 0)));
        assertEquals(1, result.size());
        assertEquals("(ISOPEN=true)", result.getFirst().getVal());
    }

    @Test
    void orphanUnknownAndNumberBasedInternalNamesRemainVisible() {
        var orphan = hidden("absent", "isopen", "t", null, 0);
        var unknown = hidden("cur", "otherflag", "t", null, 0);
        var datumNumber = variable("__gsdb_cursor_attri_123_isopen__", "boolean", "t", null, 0);
        List<DBGVariable<?>> result = GaussDBCursorVariables.aggregate(List.of(
            cursor("cur", "portal_1"), orphan, unknown, datumNumber));
        assertEquals(4, result.size());
        assertSame(orphan, result.get(1));
        assertSame(unknown, result.get(2));
        assertSame(datumNumber, result.get(3));
        assertFalse(children(result.getFirst()).containsKey("%ISOPEN"));
    }

    @Test
    void hiddenAttributesCannotBeAssignedAcrossPackageOrFrameBoundaries() {
        var packageOne = variable("cur", "refcursor", "portal_1", "pkg_one", 0);
        var packageTwo = variable("cur", "refcursor", "portal_2", "pkg_two", 0);
        var unqualified = hidden("cur", "found", "t", null, 0);
        var wrongFrame = hidden("cur", "isopen", "t", "pkg_one", 1);
        List<DBGVariable<?>> result = GaussDBCursorVariables.aggregate(List.of(
            packageOne, packageTwo, hidden("cur", "rowcount", "1", "pkg_one", 0),
            hidden("cur", "rowcount", "2", "pkg_two", 0), unqualified, wrongFrame));
        assertEquals(4, result.size());
        assertEquals("1", children(result.get(0)).get("%ROWCOUNT").getVal());
        assertEquals("2", children(result.get(1)).get("%ROWCOUNT").getVal());
        assertSame(unqualified, result.get(2));
        assertSame(wrongFrame, result.get(3));
    }

    @Test
    void ambiguousDuplicateCursorNamesDoNotConsumeHiddenRows() {
        var attribute = hidden("cur", "rowcount", "3", null, 0);
        List<DBGVariable<?>> result = GaussDBCursorVariables.aggregate(List.of(
            cursor("cur", "portal_1"), cursor("cur", "portal_2"), attribute));
        assertEquals(3, result.size());
        assertSame(attribute, result.get(2));
        assertFalse(children(result.get(0)).containsKey("%ROWCOUNT"));
        assertFalse(children(result.get(1)).containsKey("%ROWCOUNT"));
    }

    @Test
    void duplicateHiddenAttributeRowsRemainVisibleRatherThanChoosingAnArbitraryValue() {
        var first = hidden("cur", "found", "t", null, 0);
        var second = hidden("cur", "found", "f", null, 0);
        List<DBGVariable<?>> result = GaussDBCursorVariables.aggregate(List.of(cursor("cur", "portal_1"), first, second));
        assertEquals(3, result.size());
        assertSame(first, result.get(1));
        assertSame(second, result.get(2));
        assertFalse(children(result.getFirst()).containsKey("%FOUND"));
    }

    @Test
    void invalidHiddenValueIsPreservedWhileValidAttributesStillFold() {
        var invalid = hidden("cur", "isopen", "not-a-boolean", null, 0);
        List<DBGVariable<?>> result = GaussDBCursorVariables.aggregate(List.of(
            cursor("cur", "portal_1"), hidden("cur", "rowcount", "3", null, 0), invalid));
        assertEquals(2, result.size());
        assertSame(invalid, result.get(1));
        assertEquals("(ROWCOUNT=3)", result.getFirst().getVal());
        assertFalse(children(result.getFirst()).containsKey("%ISOPEN"));
    }

    @Test
    void jdbcNullHiddenAttributeIsExplicitNullInsteadOfFalse() {
        List<DBGVariable<?>> result = GaussDBCursorVariables.aggregate(List.of(
            cursor("cur", "portal_1"), hidden("cur", "found", null, null, 0)));
        assertEquals(1, result.size());
        assertEquals("(FOUND=NULL)", result.getFirst().getVal());
        assertEquals("NULL", children(result.getFirst()).get("%FOUND").getVal());
        assertFalse(children(result.getFirst()).containsKey("%NOTFOUND"));
    }

    @Test
    void inlineStateIsAuthoritativeAndConflictingHiddenRowsRemainVisible() {
        var conflicting = hidden("cur", "found", "f", null, 0);
        List<DBGVariable<?>> result = GaussDBCursorVariables.aggregate(List.of(
            cursor("cur", "{is_open:\tt\nfound:\tt}"),
            hidden("cur", "isopen", "t", null, 0), conflicting));
        assertEquals(2, result.size());
        assertSame(conflicting, result.get(1));
        assertEquals("(ISOPEN=true, FOUND=true)", result.getFirst().getVal());
    }

    @Test
    void nonCursorVariableCannotAbsorbPrivateLookingRows() {
        var ordinary = variable("cur", "varchar", "portal_1", null, 0);
        var attribute = hidden("cur", "isopen", "t", null, 0);
        List<DBGVariable<?>> result = GaussDBCursorVariables.aggregate(List.of(ordinary, attribute));
        assertEquals(2, result.size());
        assertSame(ordinary, result.get(0));
        assertSame(attribute, result.get(1));
    }
}
