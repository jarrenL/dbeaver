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

import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataType;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreNumericPrecisionTest {
    private final DBCSession session = mock(DBCSession.class);

    private PostgreDataType numericType() {
        PostgreDataType type = mock(PostgreDataType.class);
        when(type.getDataKind()).thenReturn(DBPDataKind.NUMERIC);
        when(type.getTypeID()).thenReturn(Types.NUMERIC);
        return type;
    }

    @ParameterizedTest
    @ValueSource(strings = {"9007199254740993", "-9007199254740993",
        "12345678901234567890.123456789012345678", "0.000000000000000000000000000000000001",
        "123.4500", "0.0000", "1E+400"})
    void finiteNumericPreservesDigitsAndScaleInScalarAndArray(String input) throws Exception {
        PostgreDataType type = numericType();
        BigDecimal expected = new BigDecimal(input);
        Object scalar = PostgreValueParser.convertStringToValue(session, type, input);
        assertEquals(expected, scalar);
        PostgreDataType array = mock(PostgreDataType.class);
        when(array.getDataKind()).thenReturn(DBPDataKind.ARRAY);
        when(array.getArrayDelimiter()).thenReturn(",");
        when(array.getComponentType(null)).thenReturn(type);
        Object parsed = PostgreValueParser.convertStringToValue(session, array, "{" + input + "}");
        assertArrayEquals(new Object[] {expected}, assertInstanceOf(Object[].class, parsed));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "+Infinity", "-Infinity"})
    void nonFiniteValuesKeepTheirExistingRepresentation(String input) throws Exception {
        assertEquals(Double.valueOf(input), PostgreValueParser.convertStringToValue(session, numericType(), input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-number", "1,234", "1e"})
    void invalidNumericRetainsOriginalText(String input) throws Exception {
        assertEquals(input, PostgreValueParser.convertStringToValue(session, numericType(), input));
    }

    @Test
    void floatingPointTypesKeepTheirExistingRepresentation() throws Exception {
        PostgreDataType type = numericType();
        when(type.getTypeID()).thenReturn(Types.DOUBLE);
        assertEquals(1.25d, PostgreValueParser.convertStringToValue(session, type, "1.25"));
        when(type.getTypeID()).thenReturn(Types.FLOAT);
        assertEquals(1.25f, PostgreValueParser.convertStringToValue(session, type, "1.25"));
    }
}
