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

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DateTimeDataFormatterLocaleTest {
    private DateTimeDataFormatter formatter(Locale locale, String zone) {
        var formatter = new DateTimeDataFormatter();
        formatter.init(null, locale, Map.of("pattern", "dd MMMM yyyy HH:mm:ss", "timezone", zone));
        return formatter;
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "offset", "zoned", "timestamp"})
    void temporalAndJdbcValuesRespectSelectedLocale(String kind) {
        var value = LocalDateTime.of(2024, 2, 29, 12, 34, 56);
        Object input = switch (kind) {
            case "offset" -> value.atOffset(ZoneOffset.UTC);
            case "zoned" -> value.atZone(ZoneOffset.UTC);
            case "timestamp" -> Timestamp.from(value.toInstant(ZoneOffset.UTC));
            default -> value;
        };
        assertEquals("29 février 2024 12:34:56", formatter(Locale.FRENCH, "UTC").formatValue(input));
        assertEquals("29 February 2024 12:34:56", formatter(Locale.ENGLISH, "UTC").formatValue(input));
    }

    @Test
    void localeMonthNamesRoundTripWithoutTimezone() throws Exception {
        var formatter = formatter(Locale.FRENCH, "");
        var expected = LocalDateTime.of(2024, 2, 29, 12, 34, 56);
        assertEquals(expected, formatter.parseValue("29 février 2024 12:34:56", LocalDateTime.class));
        assertEquals("29 février 2024 12:34:56", formatter.formatValue(expected));
    }

    @Test
    void nullRemainsNullWithExplicitLocaleAndTimezone() {
        assertNull(formatter(Locale.FRENCH, "UTC").formatValue(null));
    }
}
