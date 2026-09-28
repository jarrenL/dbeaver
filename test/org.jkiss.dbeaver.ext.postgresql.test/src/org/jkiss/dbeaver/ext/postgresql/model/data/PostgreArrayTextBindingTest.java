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
package org.jkiss.dbeaver.ext.postgresql.model.data;

import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataType;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.data.DBDCollection;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.data.DBDValueHandler;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class PostgreArrayTextBindingTest {
    private final PostgreDataType textType = mock(PostgreDataType.class);
    private final DBDValueHandler elementHandler = mock(DBDValueHandler.class);
    private final PostgreArrayValueHandler handler = PostgreArrayValueHandler.INSTANCE;

    private DBDCollection collection(Object... items) {
        when(textType.getDataKind()).thenReturn(DBPDataKind.STRING);
        when(textType.getArrayDelimiter()).thenReturn(",");
        when(elementHandler.getValueDisplayString(eq(textType), any(), any()))
            .thenAnswer(invocation -> invocation.getArgument(1).toString());
        DBDCollection collection = mock(DBDCollection.class);
        when(collection.getComponentType()).thenReturn(textType);
        when(collection.getComponentValueHandler()).thenReturn(elementHandler);
        when(collection.getItemCount()).thenReturn(items.length);
        for (int i = 0; i < items.length; i++) {
            when(collection.getItem(i)).thenReturn(items[i]);
        }
        return collection;
    }

    @ParameterizedTest
    @ValueSource(strings = {"\t中文", "中文\t", "\n中文", "中文\r", "\f中文", "中文\u000b"})
    void boundaryWhitespaceIsQuotedInDisplayAndJdbcBinding(String value) throws Exception {
        DBDCollection values = collection(value);
        String expected = "{\"" + value + "\"}";
        assertEquals(expected, handler.getValueDisplayString(textType, values, DBDDisplayFormat.NATIVE));
        JDBCPreparedStatement statement = mock(JDBCPreparedStatement.class);
        handler.bindParameter(mock(JDBCSession.class), statement, textType, 3, values);
        verify(statement).setObject(3, expected, Types.OTHER);
        verifyNoMoreInteractions(statement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "NULL", "null", "a,b", "{a}", "a b", "a\"b", "a\\b"})
    void reservedTextAndEscapesRemainQuoted(String value) {
        DBDCollection values = collection(value);
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        assertEquals("{\"" + escaped + "\"}",
            handler.getValueDisplayString(textType, values, DBDDisplayFormat.NATIVE));
    }

    @Test
    void nullEmptyAndLiteralNullRemainDistinct() {
        DBDCollection values = collection(null, "", "NULL", "中文𠀀");
        assertEquals("{NULL,\"\",\"NULL\",中文𠀀}",
            handler.getValueDisplayString(textType, values, DBDDisplayFormat.NATIVE));
    }

    @Test
    void emptyArrayIsNotSqlNull() {
        assertEquals("{}", handler.getValueDisplayString(textType, collection(), DBDDisplayFormat.NATIVE));
    }

    @Test
    void nestedArraysKeepWhitespaceAndNullBoundaries() {
        DBDCollection first = collection("\t中文", null);
        DBDCollection second = collection("", "NULL");
        DBDCollection outer = collection(first, second);
        assertEquals("{{\"\t中文\",NULL},{\"\",\"NULL\"}}",
            handler.getValueDisplayString(textType, outer, DBDDisplayFormat.NATIVE));
    }

    @Test
    void customTypeDelimiterIsQuotedAndUsedBetweenMembers() {
        DBDCollection values = collection("a;b", "中文");
        when(textType.getArrayDelimiter()).thenReturn(";");
        assertEquals("{\"a;b\";中文}",
            handler.getValueDisplayString(textType, values, DBDDisplayFormat.NATIVE));
    }
}
