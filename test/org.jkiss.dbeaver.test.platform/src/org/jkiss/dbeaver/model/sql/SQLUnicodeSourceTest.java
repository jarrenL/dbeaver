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
package org.jkiss.dbeaver.model.sql;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.misc.Interval;
import org.jkiss.dbeaver.model.stm.STMSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

class SQLUnicodeSourceTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "SELECT 1", "中文", "😀🧪", "-- 😀\r\nSELECT 'é'", "SELECT \"名称😀\""})
    void stringInputPreservesDocumentCoordinates(String text) {
        assertCoordinates(STMSource.fromString(text).getStream(), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "SELECT 1", "中文", "😀🧪", "-- 😀\r\nSELECT 'é'", "SELECT \"名称😀\""})
    void readerInputPreservesDocumentCoordinates(String text) throws Exception {
        try (var reader = new StringReader(text)) {
            assertCoordinates(STMSource.fromReader(reader).getStream(), text);
        }
    }

    private void assertCoordinates(CharStream stream, String text) {
        assertEquals(text.length(), stream.size());
        for (int i = 0; i < text.length(); i++) {
            assertEquals(i, stream.index());
            assertEquals(text.charAt(i), stream.LA(1));
            assertEquals(text.substring(i, i + 1), stream.getText(Interval.of(i, i)));
            stream.consume();
            assertEquals(text.charAt(i), stream.LA(-1));
        }
        assertEquals(IntStream.EOF, stream.LA(1));
        assertEquals(text.length(), stream.index());
        stream.seek(0);
        assertEquals(0, stream.index());
        assertEquals(text, stream.getText(Interval.of(0, text.length() - 1)));
    }
}
