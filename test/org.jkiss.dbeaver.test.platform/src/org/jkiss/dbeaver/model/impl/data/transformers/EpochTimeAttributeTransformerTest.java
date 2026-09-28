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
package org.jkiss.dbeaver.model.impl.data.transformers;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.data.DBDRowIdentifier;
import org.jkiss.dbeaver.model.data.DBDValueHandler;
import org.jkiss.dbeaver.model.exec.DBCAttributeMetaData;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSEntityReferrer;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;

public class EpochTimeAttributeTransformerTest extends DBeaverUnitTest {
    private static final String NANOS = "nanoseconds";
    private static final String MILLIS = "milliseconds";
    private static final String SECONDS = "seconds";

    private final EpochTimeAttributeTransformer transformer = new EpochTimeAttributeTransformer();

    private final DBCSession session = mock(DBCSession.class);
    private final DBSTypedObject column = mock(DBSTypedObject.class);
    private final DBDValueHandler handler = mock(DBDValueHandler.class);

    private DBDAttributeBinding attributeBinding;
    private DBDValueHandler proxyHandler;

    @BeforeEach
    public void init() {
        attributeBinding = new DBDAttributeBindingTestDouble(handler);
    }

    private void setOptions(@Nullable String unit, @Nullable String timezoneID) {
        Map<String, Object> map = new HashMap<>(2, 1);
        if (unit != null) {
            map.put(EpochTimeAttributeTransformer.PROP_UNIT, unit);
        }
        if (timezoneID != null) {
            map.put(EpochTimeAttributeTransformer.ZONE_ID, timezoneID);
        }
        try {
            transformer.transformAttribute(session, attributeBinding, Collections.emptyList(), Collections.unmodifiableMap(map));
        } catch (DBException e) {
            throw new RuntimeException(e);
        }
        proxyHandler = attributeBinding.getValueHandler();
    }

    private String getDisplayString(Object o) {
        return proxyHandler.getValueDisplayString(column, o, DBDDisplayFormat.UI);
    }

    private Object getValue(Object o) {
        try {
            return proxyHandler.getValueFromObject(session, column, o, false, false);
        } catch (DBCException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testMillisAndUTC() {
        setOptions(MILLIS, "UTC");

        Assertions.assertEquals("1970-01-01 00:00:00.000", getDisplayString(0));
        Assertions.assertEquals(0L, getValue("1970-01-01 00:00:00.000"));

        Assertions.assertEquals("1970-01-01 00:00:00.042", getDisplayString(42));
        Assertions.assertEquals(42L, getValue("1970-01-01 00:00:00.042"));

        Assertions.assertEquals("1969-12-31 23:59:59.999", getDisplayString(-1));
        Assertions.assertEquals(-1L, getValue("1969-12-31 23:59:59.999"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "0|1970-01-01 00:00:00.000000",
        "1|1970-01-01 00:00:00.000001",
        "42|1970-01-01 00:00:00.000042",
        "999999|1970-01-01 00:00:00.999999",
        "1000001|1970-01-01 00:00:01.000001",
        "-1|1969-12-31 23:59:59.999999",
        "-1000001|1969-12-31 23:59:58.999999",
        "1709251199123456|2024-02-29 23:59:59.123456"
    })
    void microsecondsRoundTripAcrossSecondAndEpochBoundaries(long raw, String expected) {
        setOptions("microseconds", "UTC");
        Assertions.assertEquals(expected, getDisplayString(raw));
        Assertions.assertEquals(raw, getValue(expected));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"not-a-date", "1970-01-01 00:00:00.000000junk"})
    void malformedEpochTextThrowsInsteadOfReturningAnExceptionAsValue(String text) throws Exception {
        setOptions("microseconds", "UTC");
        DBCException failure = Assertions.assertThrows(DBCException.class,
            () -> proxyHandler.getValueFromObject(session, column, text, false, true));
        Assertions.assertInstanceOf(java.time.DateTimeException.class, failure.getCause());
        Assertions.assertEquals(42L,
            proxyHandler.getValueFromObject(session, column, "1970-01-01 00:00:00.000042", false, true));
    }

    @Test
    public void testSecondsAndParis() {
        setOptions(SECONDS, "Europe/Paris");

        Assertions.assertEquals("1970-01-01 01:00:00", getDisplayString(0));
        Assertions.assertEquals(0L, getValue("1970-01-01 01:00:00"));

        Assertions.assertEquals("1970-01-01 01:00:42", getDisplayString(42));
        Assertions.assertEquals(42L, getValue("1970-01-01 01:00:42"));

        Assertions.assertEquals("1970-01-01 00:59:59", getDisplayString(-1));
        Assertions.assertEquals(-1L, getValue("1970-01-01 00:59:59"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "seconds|2023-02-29 12:00:00",
        "milliseconds|2023-02-29 12:00:00.000",
        "microseconds|2023-02-29 12:00:00.000000",
        "nanoseconds|2023-02-29 12:00:00.000000000",
        "dotnet|2023-02-29 12:00:00.0000000",
        "w32filetime|2023-02-29 12:00:00.0000000",
        "oadate|2023-02-29 12:00:00.000000000",
        "sqliteJulian|2023-02-29 12:00:00.00000",
        "seconds|2024-02-30 12:00:00",
        "seconds|2024-04-31 12:00:00",
        "seconds|2024-01-01 24:00:00",
        "seconds|2024-01-01 12:00:60"
    })
    void impossibleDatesAndTimesAreRejectedWithoutNormalization(String unit, String text) {
        setOptions(unit, "UTC");
        DBCException failure = Assertions.assertThrows(DBCException.class,
            () -> proxyHandler.getValueFromObject(session, column, text, false, true));
        Assertions.assertInstanceOf(java.time.DateTimeException.class, failure.getCause());
        // Failure must not poison the handler or reject an ordinary valid date for this unit.
        Object raw = getValue(getDisplayString(0L));
        Assertions.assertInstanceOf(Number.class, raw);
        Assertions.assertEquals(0.0, ((Number) raw).doubleValue());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "Asia/Shanghai|2024-03-01 07:59:59.123456",
        "Asia/Kathmandu|2024-03-01 05:44:59.123456",
        "America/St_Johns|2024-02-29 20:29:59.123456"
    })
    void microsecondsRoundTripAcrossNonUtcDayBoundaries(String zone, String expected) {
        setOptions("microseconds", zone);
        Assertions.assertEquals(expected, getDisplayString(1709251199123456L));
        Assertions.assertEquals(1709251199123456L, getValue(expected));
    }

    @Test
    public void testNanosAndUTC() {
        setOptions(NANOS, "UTC");

        Assertions.assertEquals("1970-01-01 00:00:00.000000000", getDisplayString(0));
        Assertions.assertEquals(0L, getValue("1970-01-01 00:00:00.000000000"));

        Assertions.assertEquals("1970-01-01 00:00:00.000000420", getDisplayString(420));
        Assertions.assertEquals(420L, getValue("1970-01-01 00:00:00.000000420"));

        Assertions.assertEquals("1970-01-01 00:00:01.000000420", getDisplayString(1_000_000_420));
        Assertions.assertEquals(1_000_000_420L, getValue("1970-01-01 00:00:01.000000420"));

        Assertions.assertEquals("1969-12-31 23:59:59.999999580", getDisplayString(-420));
        Assertions.assertEquals(-420L, getValue("1969-12-31 23:59:59.999999580"));

        Assertions.assertEquals("1969-12-31 23:59:58.999999580", getDisplayString(-1_000_000_420));
        Assertions.assertEquals(-1_000_000_420L, getValue("1969-12-31 23:59:58.999999580"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "dotnet|0|0001-01-01 00:00:00.0000000",
        "dotnet|1|0001-01-01 00:00:00.0000001",
        "dotnet|621355968000000001|1970-01-01 00:00:00.0000001",
        "dotnet|621355967999999999|1969-12-31 23:59:59.9999999",
        "dotnet|621355968001234567|1970-01-01 00:00:00.1234567",
        "dotnet|621355968010000001|1970-01-01 00:00:01.0000001",
        "w32filetime|0|1601-01-01 00:00:00.0000000",
        "w32filetime|1|1601-01-01 00:00:00.0000001",
        "w32filetime|116444736000000001|1970-01-01 00:00:00.0000001",
        "w32filetime|116444735999999999|1969-12-31 23:59:59.9999999",
        "w32filetime|116444736001234567|1970-01-01 00:00:00.1234567",
        "w32filetime|116444736010000001|1970-01-01 00:00:01.0000001"
    })
    void ticksUseSevenFractionDigitsAndPreserveHundredNanoseconds(String unit, long raw, String expected) {
        setOptions(unit, "UTC");
        Assertions.assertEquals(expected, getDisplayString(raw));
        Assertions.assertEquals(raw, getValue(expected));
    }

    private static class DBDAttributeBindingTestDouble extends DBDAttributeBinding {
        protected DBDAttributeBindingTestDouble(@NotNull DBDValueHandler valueHandler) {
            super(valueHandler);
        }

        @Nullable
        @Override
        public DBDAttributeBinding getParentObject() {
            return null;
        }

        @NotNull
        @Override
        public DBPDataSource getDataSource() {
            return null;
        }

        @Override
        public int getOrdinalPosition() {
            return 0;
        }

        @Override
        public boolean isRequired() {
            return false;
        }

        @Override
        public boolean isAutoGenerated() {
            return false;
        }

        @NotNull
        @Override
        public String getLabel() {
            return null;
        }

        @NotNull
        @Override
        public String getName() {
            return null;
        }

        @Nullable
        @Override
        public DBCAttributeMetaData getMetaAttribute() {
            return null;
        }

        @Nullable
        @Override
        public DBSEntityAttribute getEntityAttribute() {
            return null;
        }

        @Nullable
        @Override
        public DBDRowIdentifier getRowIdentifier() {
            return null;
        }

        @Nullable
        @Override
        public String getRowIdentifierStatus() {
            return null;
        }

        @Nullable
        @Override
        public List<DBSEntityReferrer> getReferrers() {
            return null;
        }

        @Nullable
        @Override
        public Object extractNestedValue(@NotNull Object ownerValue, int itemIndex) throws DBCException {
            return null;
        }

        @NotNull
        @Override
        public String getTypeName() {
            return null;
        }

        @NotNull
        @Override
        public String getFullTypeName() {
            return null;
        }

        @Override
        public int getTypeID() {
            return 0;
        }

        @NotNull
        @Override
        public DBPDataKind getDataKind() {
            return null;
        }

        @Nullable
        @Override
        public Integer getScale() {
            return null;
        }

        @Nullable
        @Override
        public Integer getPrecision() {
            return null;
        }

        @Override
        public long getMaxLength() {
            return 0;
        }

        @Override
        public long getTypeModifiers() {
            return 0;
        }
    }
}
