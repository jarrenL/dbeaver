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
    @ParameterizedTest
    @ValueSource(strings = {"noon", "n'n", "中文n"})
    void quotedNanoLettersHaveSameLiteralMeaningForJdbcAndTemporal(String literal) {
        var formatter = new DateTimeDataFormatter();
        formatter.init(null, Locale.ENGLISH, Map.of("pattern", "yyyy-MM-dd '" + literal.replace("'", "''") + "' HH:mm:ss"));
        var local = LocalDateTime.of(2024, 2, 29, 12, 34, 56);
        String expected = "2024-02-29 " + literal + " 12:34:56";
        assertEquals(expected, formatter.formatValue(local));
        assertEquals(expected, formatter.formatValue(Timestamp.valueOf(local)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Shanghai", "America/New_York"})
    void reinitializationReplacesZoneLocaleAndPattern(String zone) throws Exception {
        var formatter = formatter(Locale.FRENCH, "UTC");
        var instant = java.time.Instant.parse("2024-02-29T20:34:56Z");
        formatter.init(null, Locale.ENGLISH, Map.of("pattern", "yyyy-MM-dd HH:mm:ss XXX", "timezone", zone));
        var expected = instant.atZone(java.time.ZoneId.of(zone));
        String text = formatter.formatValue(Timestamp.from(instant));
        assertEquals(expected.toOffsetDateTime(), formatter.parseValue(text, java.time.OffsetDateTime.class));
        formatter.init(null, Locale.ENGLISH, Map.of("pattern", "dd MMMM yyyy HH:mm:ss"));
        assertNull(formatter.getZone());
        assertEquals("29 February 2024 12:34:56", formatter.formatValue(LocalDateTime.of(2024, 2, 29, 12, 34, 56)));
    }

    @Test
    void invalidZoneCanBeFollowedBySuccessfulReinitialization() {
        var formatter = formatter(Locale.ENGLISH, "UTC");
        assertThrows(java.time.DateTimeException.class,
            () -> formatter.init(null, Locale.ENGLISH, Map.of("pattern", "yyyy-MM-dd", "timezone", "Invalid/Zone")));
        formatter.init(null, Locale.ENGLISH, Map.of("pattern", "yyyy-MM-dd", "timezone", "UTC"));
        assertEquals("2024-02-29", formatter.formatValue(java.sql.Date.valueOf("2024-02-29")));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "UTC,date", "Asia/Shanghai,date", "America/New_York,date",
        "UTC,time", "Asia/Shanghai,time", "America/New_York,time"
    })
    void jdbcDateAndTimeWithoutInstantRemainWallClockValues(String zone, String kind) {
        boolean date = kind.equals("date");
        var formatter = new DateTimeDataFormatter();
        formatter.init(null, Locale.ENGLISH, Map.of("pattern", date ? "yyyy-MM-dd" : "HH:mm:ss", "timezone", zone));
        Object value = date ? java.sql.Date.valueOf("2024-02-29") : java.sql.Time.valueOf("23:59:58");
        assertEquals(date ? "2024-02-29" : "23:59:58", formatter.formatValue(value));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "2024-03-10T06:59:59.123456Z,2024-03-10 01:59:59.123456 -05:00",
        "2024-03-10T07:00:00.123456Z,2024-03-10 03:00:00.123456 -04:00",
        "2024-11-03T05:30:00.123456Z,2024-11-03 01:30:00.123456 -04:00",
        "2024-11-03T06:30:00.123456Z,2024-11-03 01:30:00.123456 -05:00"
    })
    void instantFormattingAcrossDstRetainsOffsetAndMicroseconds(String instantText, String expected) throws Exception {
        var formatter = new DateTimeDataFormatter();
        formatter.init(null, Locale.ENGLISH,
            Map.of("pattern", "yyyy-MM-dd HH:mm:ss.ffffff XXX", "timezone", "America/New_York"));
        var instant = java.time.Instant.parse(instantText);
        assertEquals(expected, formatter.formatValue(Timestamp.from(instant)));
        assertEquals(expected, formatter.formatValue(instant.atOffset(ZoneOffset.UTC)));
        assertEquals(expected, formatter.formatValue(instant.atZone(java.time.ZoneId.of("Asia/Shanghai"))));
        var parsed = assertInstanceOf(java.time.OffsetDateTime.class, formatter.parseValue(expected, null));
        assertEquals(instant, parsed.toInstant());
    }

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

    @ParameterizedTest
    @ValueSource(strings = {
        "2023-02-29 12:00:00", "2024-02-30 12:00:00", "2024-04-31 12:00:00",
        "2024-01-01 24:00:00", "2024-01-01 12:00:00junk", "2024-13-01 12:00:00"
    })
    void invalidDateIsRejectedRatherThanNormalizedOrPartiallyParsed(String input) {
        var plain = new DateTimeDataFormatter();
        plain.init(null, Locale.ENGLISH, Map.of("pattern", "yyyy-MM-dd HH:mm:ss"));
        assertThrows(java.text.ParseException.class, () -> plain.parseValue(input, LocalDateTime.class));
        assertThrows(java.text.ParseException.class, () -> plain.parseValue(input, null));
        var offset = new DateTimeDataFormatter();
        offset.init(null, Locale.ENGLISH, Map.of("pattern", "yyyy-MM-dd HH:mm:ss Z"));
        assertThrows(java.text.ParseException.class,
            () -> offset.parseValue(input + " +0800", java.time.OffsetDateTime.class));
        assertThrows(java.text.ParseException.class, () -> offset.parseValue(input + " +0800", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"yyyy", "uuuu"})
    void validLeapDayAndOffsetRemainParsable(String yearPattern) throws Exception {
        var formatter = new DateTimeDataFormatter();
        formatter.init(null, Locale.ENGLISH, Map.of("pattern", yearPattern + "-MM-dd HH:mm:ss Z"));
        var expected = java.time.OffsetDateTime.parse("2024-02-29T23:59:59+08:00");
        assertEquals(expected, formatter.parseValue("2024-02-29 23:59:59 +0800", java.time.OffsetDateTime.class));
        assertEquals(expected, formatter.parseValue("2024-02-29 23:59:59 +0800", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"yyyy-MM-dd", "HH:mm:ss"})
    void dateAndTimeOnlyLegacyProfilesStillRoundTrip(String pattern) throws Exception {
        var formatter = new DateTimeDataFormatter();
        formatter.init(null, Locale.ENGLISH, Map.of("pattern", pattern));
        String input = pattern.equals("yyyy-MM-dd") ? "2024-02-29" : "23:59:59";
        Object parsed = formatter.parseValue(input, null);
        assertInstanceOf(java.util.Date.class, parsed);
        assertEquals(input, formatter.formatValue(parsed));
        assertThrows(java.text.ParseException.class, () -> formatter.parseValue(input + "junk", null));
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 6, 9})
    void fractionalSecondWidthAndLeadingZerosSurviveFormattingAndParsing(int width) throws Exception {
        var formatter = new DateTimeDataFormatter();
        formatter.init(null, Locale.ENGLISH,
            Map.of("pattern", "yyyy-MM-dd HH:mm:ss." + "f".repeat(width), "timezone", "UTC"));
        for (int nanos : new int[] {0, 1_000_000, 123_456_789}) {
            var value = LocalDateTime.of(2024, 2, 29, 12, 34, 56, nanos);
            String fraction = String.format(Locale.ROOT, "%09d", nanos).substring(0, width);
            String expected = "2024-02-29 12:34:56." + fraction;
            assertEquals(expected, formatter.formatValue(value));
            assertEquals(expected, formatter.formatValue(Timestamp.from(value.toInstant(ZoneOffset.UTC))));
            int unit = (int) Math.pow(10, 9 - width);
            var truncated = value.withNano(nanos / unit * unit);
            assertEquals(truncated, formatter.parseValue(expected, LocalDateTime.class));
            assertEquals(truncated, formatter.parseValue(expected, null));
        }
    }

    @Test
    void quotedFractionLettersAreLiteralText() throws Exception {
        var formatter = new DateTimeDataFormatter();
        formatter.init(null, Locale.ENGLISH, Map.of("pattern", "yyyy-MM-dd 'fff' HH:mm:ss.fff"));
        var value = LocalDateTime.of(2024, 2, 29, 12, 34, 56, 1_000_000);
        String expected = "2024-02-29 fff 12:34:56.001";
        assertEquals(expected, formatter.formatValue(value));
        assertEquals(value, formatter.parseValue(expected, LocalDateTime.class));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"XXX,+08:00", "XX,+0800", "X,Z", "Z,+0800"})
    void automaticTypeRetainsParsedOffsetAndMicroseconds(String zonePattern, String offsetText) throws Exception {
        var formatter = new DateTimeDataFormatter();
        formatter.init(null, Locale.ENGLISH, Map.of("pattern", "yyyy-MM-dd HH:mm:ss.ffffff " + zonePattern));
        String text = "2024-02-29 12:34:56.001234 " + offsetText;
        var expected = java.time.OffsetDateTime.of(2024, 2, 29, 12, 34, 56, 1_234_000,
            offsetText.equals("Z") ? ZoneOffset.UTC : ZoneOffset.ofHours(8));
        assertEquals(expected, formatter.parseValue(text, java.time.OffsetDateTime.class));
        assertEquals(expected, formatter.parseValue(text, null));
        assertEquals(text, formatter.formatValue(expected));
    }

    @Test
    void quotedZoneLetterDoesNotMakeLocalTimestampOffsetAware() throws Exception {
        var formatter = new DateTimeDataFormatter();
        formatter.init(null, Locale.ENGLISH, Map.of("pattern", "yyyy-MM-dd HH:mm:ss 'Z'"));
        assertEquals(LocalDateTime.of(2024, 2, 29, 12, 34, 56),
            formatter.parseValue("2024-02-29 12:34:56 Z", null));
    }

    @Test
    void lowercaseOffsetPatternIsAnExplicitLegacyFormatterLimitation() {
        var formatter = new DateTimeDataFormatter();
        assertThrows(IllegalArgumentException.class,
            () -> formatter.init(null, Locale.ENGLISH, Map.of("pattern", "yyyy-MM-dd HH:mm:ss xxx")));
    }
}
