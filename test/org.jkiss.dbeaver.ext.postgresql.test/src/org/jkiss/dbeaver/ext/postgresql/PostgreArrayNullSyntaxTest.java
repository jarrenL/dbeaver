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

package org.jkiss.dbeaver.ext.postgresql;

import org.jkiss.dbeaver.model.exec.DBCException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PostgreArrayNullSyntaxTest {
    @ParameterizedTest
    @ValueSource(strings = {"NULL", "null", "Null", "nUlL"})
    void unquotedNullIsCaseInsensitiveButQuotedTextIsNotNull(String token) throws Exception {
        assertEquals(Arrays.asList(null, token),
            PostgreValueParser.parseArrayString("{" + token + ",\"" + token + "\"}", ","));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\NULL", "N\\ULL", "NUL\\L"})
    void escapedNullIsLiteralText(String token) throws Exception {
        assertEquals(List.of("NULL"), PostgreValueParser.parseArrayString("{" + token + "}", ","));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[0:1]", "{a\\", "{\"a}"})
    void malformedInputReportsCheckedParseFailure(String input) {
        assertThrows(DBCException.class, () -> PostgreValueParser.parseArrayString(input, ","));
    }

    @Test
    void quotedEmptyNullAndUnicodeAreDistinct() throws Exception {
        assertEquals(Arrays.asList("", null, "NULL", "中文𠀀"),
            PostgreValueParser.parseArrayString("{\"\",NULL,\"NULL\",\"中文𠀀\"}", ","));
    }

    @Test
    void explicitBoundsKeepElementOrderInLowLevelParser() throws Exception {
        assertEquals(List.of("10", "20"), PostgreValueParser.parseArrayString("[0:1]={10,20}", ","));
    }

    @Test
    void nestedArraysKeepNullAndEmptyString() throws Exception {
        assertEquals(List.of(Arrays.asList(null, ""), List.of("NULL", "中文")),
            PostgreValueParser.parseArrayString("{{NULL,\"\"},{\"NULL\",中文}}", ","));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a b", "中文  文本", "a\tb", "a\nb"})
    void internalUnquotedWhitespaceIsPreservedButOuterWhitespaceIsIgnored(String value) throws Exception {
        assertEquals(List.of(value, "tail"),
            PostgreValueParser.parseArrayString("{ \t" + value + " \r, tail }", ","));
    }

    @ParameterizedTest
    @ValueSource(strings = {" a ", "\t中文\n", "", "  "})
    void quotedWhitespaceIsPreservedExactly(String value) throws Exception {
        assertEquals(List.of(value, "tail"),
            PostgreValueParser.parseArrayString("{ \"" + value + "\" \t,tail}", ","));
    }

    @Test
    void escapedBoundarySpacesRemainData() throws Exception {
        assertEquals(List.of(" a ", "tail"),
            PostgreValueParser.parseArrayString("{\\ a\\ ,tail}", ","));
    }
}
