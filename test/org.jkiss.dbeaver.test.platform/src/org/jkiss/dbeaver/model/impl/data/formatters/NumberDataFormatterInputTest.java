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
package org.jkiss.dbeaver.model.impl.data.formatters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.text.ParseException;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NumberDataFormatterInputTest {
    private NumberDataFormatter formatter(Locale locale) {
        var formatter = new NumberDataFormatter();
        formatter.init(null, locale, Map.of());
        return formatter;
    }

    @ParameterizedTest
    @ValueSource(strings = {"123abc", "123.45junk", "1;DELETE", "12 34", "", "abc"})
    void rejectsInputThatCannotBeCompletelyParsed(String text) {
        var formatter = formatter(Locale.US);
        assertThrows(ParseException.class, () -> formatter.parseValue(text, BigDecimal.class));
        assertThrows(ParseException.class, () -> formatter.parseValue(text, null));
    }

    @Test
    void highPrecisionDecimalIsNotRoutedThroughDouble() throws Exception {
        var expected = new BigDecimal("12345678901234567890.123456789012345678");
        assertEquals(expected, formatter(Locale.US).parseValue(expected.toPlainString(), BigDecimal.class));
    }

    @Test
    void localeGroupingDecimalAndExponentStillParse() throws Exception {
        assertEquals(new BigDecimal("1234.50"), formatter(Locale.US).parseValue("1,234.50", BigDecimal.class));
        assertEquals(new BigDecimal("1234.50"), formatter(Locale.GERMANY).parseValue("1.234,50", BigDecimal.class));
        assertEquals(0, new BigDecimal("123000").compareTo(
            (BigDecimal) formatter(Locale.US).parseValue("1.23E5", BigDecimal.class)));
    }

    @Test
    void integerAndNullFormattingRemainSupported() throws Exception {
        var formatter = formatter(Locale.US);
        assertEquals(Long.MIN_VALUE, formatter.parseValue(Long.toString(Long.MIN_VALUE), Long.class));
        assertEquals(Integer.MAX_VALUE, formatter.parseValue(Integer.toString(Integer.MAX_VALUE), Integer.class));
        assertNull(formatter.formatValue(null));
    }
}
