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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PostgreCompositeArrayRoundTripTest {
    private String arrayField(Object[] array) throws Exception {
        String composite = PostgreValueParser.generateObjectString(new Object[] {array});
        assertTrue(composite.startsWith("(") && composite.endsWith(")"));
        String[] fields = PostgreValueParser.parseSingleObject(composite.substring(1, composite.length() - 1));
        assertEquals(1, fields.length);
        return fields[0];
    }

    @ParameterizedTest
    @ValueSource(strings = {"中文 文本", "a,b", "{x}", "[x]", "\"", "\\", "", "NULL", "\t中文\n", "中文𠀀"})
    void textArrayInsideCompositePreservesItsValue(String value) throws Exception {
        String literal = arrayField(new Object[] {value});
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        assertEquals("{\"" + escaped + "\"}", literal);
        assertEquals(List.of(value), PostgreValueParser.parseArrayString(literal, ","));
    }

    @Test
    void nullEmptyAndLiteralNullRemainSeparateInCompositeArray() throws Exception {
        String literal = arrayField(new Object[] {null, "", "NULL"});
        assertEquals("{NULL,\"\",\"NULL\"}", literal);
        assertEquals(Arrays.asList(null, "", "NULL"), PostgreValueParser.parseArrayString(literal, ","));
    }

    @Test
    void nestedTextArraysKeepTheirShapeAndEscapes() throws Exception {
        String literal = arrayField(new Object[] {new String[] {"a b", "x,y"}, new String[] {"", "NULL"}});
        assertEquals("{{\"a b\",\"x,y\"},{\"\",\"NULL\"}}", literal);
        assertEquals(List.of(List.of("a b", "x,y"), List.of("", "NULL")),
            PostgreValueParser.parseArrayString(literal, ","));
    }

    @Test
    void numericArrayKeepsExactDigitsAndExistingUnquotedSyntax() throws Exception {
        assertEquals("{9007199254740993,1234567890.123456789012345678,0.0000}",
            arrayField(new Object[] {9007199254740993L,
                new BigDecimal("1234567890.123456789012345678"), new BigDecimal("0.0000")}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\", "a\"b", "\\\"", "中文\n"})
    void scalarCompositeEscapingPreservesTextNullAndEmpty(String value) throws Exception {
        String composite = PostgreValueParser.generateObjectString(new Object[] {value, null, ""});
        assertArrayEquals(new String[] {value, null, ""},
            PostgreValueParser.parseSingleObject(composite.substring(1, composite.length() - 1)));
    }
}
