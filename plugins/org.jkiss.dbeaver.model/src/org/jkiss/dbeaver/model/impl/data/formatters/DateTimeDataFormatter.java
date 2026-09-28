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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.data.DBDDataFormatter;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.time.ExtendedDateFormat;

import java.text.DateFormat;
import java.text.FieldPosition;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

public class DateTimeDataFormatter implements DBDDataFormatter {

    public static final String PROP_PATTERN = "pattern";
    public static final String PROP_TIMEZONE = "timezone";

    private String pattern;
    private ZoneId zone;
    private DateFormat dateFormat;
    private StringBuffer buffer;
    private FieldPosition position;
    private DateTimeFormatter dateTimeFormatter;

    @Override
    public void init(DBSTypedObject type, @NotNull Locale locale, @NotNull Map<String, Object> properties)
    {
        pattern = CommonUtils.toString(properties.get(PROP_PATTERN));
        final String timezone = CommonUtils.toString(properties.get(PROP_TIMEZONE));
        zone = CommonUtils.isEmptyTrimmed(timezone) ? null : ZoneId.of(timezone);
        String sdfPattern = pattern.replace("n", "f");
        dateFormat = new ExtendedDateFormat(
            sdfPattern,
            locale);
        // We shouldn't use lenient formatter (#7244)
        dateFormat.setLenient(false);
        buffer = new StringBuffer();
        position = new FieldPosition(0);
        // Legacy f is a fraction of a second, not the unscaled nano-of-second n.
        // Keep its width and leave quoted literals (including escaped quotes) intact.
        StringBuilder temporalPattern = new StringBuilder(pattern.length());
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char symbol = pattern.charAt(i);
            if (symbol == '\'') {
                quoted = !quoted;
            }
            temporalPattern.append(!quoted && symbol == 'f' ? 'S' : symbol);
        }
        String java8DatePattern = temporalPattern.toString();
        dateTimeFormatter = new DateTimeFormatterBuilder()
            .appendPattern(java8DatePattern)
            // Existing profiles use yyyy (year of era) without an explicit era.
            .parseDefaulting(ChronoField.ERA, 1)
            .toFormatter(locale)
            .withResolverStyle(ResolverStyle.STRICT);
    }

    @Nullable
    public ZoneId getZone() {
        return zone;
    }

    @NotNull
    public DateFormat getDateFormat() {
        return dateFormat;
    }

    @Override
    public String getPattern()
    {
        return pattern;
    }

    @Override
    public String formatValue(Object value)
    {
        // JDBC DATE/TIME are local calendar values; their toInstant() is unsupported.
        if (zone != null && value instanceof java.sql.Date date) {
            return dateTimeFormatter.format(date.toLocalDate());
        }
        if (zone != null && value instanceof java.sql.Time time) {
            return dateTimeFormatter.format(time.toLocalTime());
        }
        if (value instanceof Date && zone != null) {
            return dateTimeFormatter.format(ZonedDateTime.ofInstant(((Date) value).toInstant(), zone));
        }
        if (value instanceof TemporalAccessor) {
            if (zone != null) {
                if (value instanceof LocalDateTime) {
                    return dateTimeFormatter.format(((LocalDateTime) value).atZone(zone));
                }
                if (value instanceof ZonedDateTime) {
                    return dateTimeFormatter.format(((ZonedDateTime) value).withZoneSameInstant(zone));
                }
                if (value instanceof OffsetDateTime) {
                    return dateTimeFormatter.format(((OffsetDateTime) value).atZoneSameInstant(zone));
                }
            }
            return dateTimeFormatter.format((TemporalAccessor) value);
        }
        synchronized (dateFormat) {
            buffer.setLength(0);
            return value == null ? null : dateFormat.format(value, buffer, position).toString();
        }
    }

    @Override
    public Object parseValue(String value, Class<?> typeHint) throws ParseException
    {
        if (typeHint != null) {
            if (LocalDateTime.class.isAssignableFrom(typeHint)) {
                try {
                    return LocalDateTime.parse(value, dateTimeFormatter);
                } catch (DateTimeParseException e) {
                    throw new ParseException(e.getParsedString(), e.getErrorIndex());
                }
            }
            if (OffsetDateTime.class.isAssignableFrom(typeHint)) {
                try {
                    return OffsetDateTime.parse(value, dateTimeFormatter);
                } catch (DateTimeParseException e) {
                    throw new ParseException(e.getParsedString(), e.getErrorIndex());
                }
            }
        }
        try {
            return dateTimeFormatter.parseBest(value, OffsetDateTime::from, LocalDateTime::from);
        } catch (Exception e) {
            // Date-only/time-only profiles need the legacy return type, but must
            // still reject invalid fields and unconsumed trailing input.
            try {
                dateTimeFormatter.parse(value);
            } catch (DateTimeParseException parseException) {
                throw new ParseException(parseException.getParsedString(), parseException.getErrorIndex());
            }
            synchronized (dateFormat) {
                return dateFormat.parse(value);
            }
        }
    }

}
