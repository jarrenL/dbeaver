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
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.eclipse.core.runtime.Platform;
import org.jkiss.dbeaver.model.text.parser.TPCharacterScanner;
import org.jkiss.dbeaver.model.text.parser.TPRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class GaussDBPredicateScannerTest {
    private TPRule escapeRule() throws Exception {
        return (TPRule) Platform.getBundle("org.jkiss.dbeaver.ext.postgresql")
            .loadClass("org.jkiss.dbeaver.ext.postgresql.sql.PostgreEscapeStringRule").getConstructor().newInstance();
    }

    private TPCharacterScanner scanner(String text) throws Exception {
        var type = Platform.getBundle("org.jkiss.dbeaver.model.sql").loadClass(
            "org.jkiss.dbeaver.model.sql.parser.tokens.predicates.SQLTokenPredicateFactory$StringScanner");
        var constructor = type.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return (TPCharacterScanner) constructor.newInstance(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\r\n"})
    void columnTracksLineDelimitersAndUnread(String newline) throws Exception {
        var scanner = scanner("ab" + newline + "cd");
        assertEquals(0, scanner.getColumn());
        scanner.read();
        scanner.read();
        assertEquals(2, scanner.getColumn());
        for (int i = 0; i < newline.length(); i++) {
            scanner.read();
        }
        assertEquals(0, scanner.getColumn());
        scanner.read();
        scanner.read();
        assertEquals(2, scanner.getColumn());
        scanner.unread();
        assertEquals(1, scanner.getColumn());
    }

    @Test
    void escapeRuleCanInspectColumnWithoutThrowing() throws Exception {
        var scanner = scanner("E'escaped\\ntext' ");
        assertFalse(escapeRule().evaluate(scanner).isUndefined());
    }

    @Test
    void escapeRuleDoesNotConsumeIdentifierSuffix() throws Exception {
        var scanner = scanner("nameE'not an escape' ");
        for (int i = 0; i < 4; i++) {
            scanner.read();
        }
        assertTrue(escapeRule().evaluate(scanner).isUndefined());
        assertEquals(4, scanner.getOffset());
    }

    @ParameterizedTest
    @ValueSource(strings = {"E''", "e'abc'", "E'a''b'", "E'line\\nnext'", "E'中文'"})
    void escapeLiteralAtEndOfInputIsRecognized(String text) throws Exception {
        var scanner = scanner(text);
        assertFalse(escapeRule().evaluate(scanner).isUndefined());
        assertEquals(text.length(), scanner.getOffset());
        var documentScanner = new org.jkiss.dbeaver.model.text.parser.TPRuleBasedScanner();
        documentScanner.setRange(new org.eclipse.jface.text.Document(text), 0, text.length());
        assertFalse(escapeRule().evaluate(documentScanner).isUndefined());
        assertEquals(text.length(), documentScanner.getOffset());
    }

    @ParameterizedTest
    @ValueSource(strings = {"E'abc';", "E'a''b',", "e'' "})
    void escapeLiteralLeavesFollowingDelimiterUnread(String text) throws Exception {
        var scanner = scanner(text);
        assertFalse(escapeRule().evaluate(scanner).isUndefined());
        assertEquals(text.charAt(text.length() - 1), scanner.read());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "E", "e", "E'", "E'abc", "E'abc\\", "E'a''", "select", "E123"})
    void failedEscapeMatchRestoresBothScannerOffsets(String text) throws Exception {
        var fragment = scanner(text);
        var document = new org.jkiss.dbeaver.model.text.parser.TPRuleBasedScanner();
        document.setRange(new org.eclipse.jface.text.Document(text), 0, text.length());
        for (var input : new TPCharacterScanner[] {fragment, document}) {
            assertTrue(escapeRule().evaluate(input).isUndefined());
            assertEquals(0, input.getOffset(), "Failed rule must leave input for the next rule");
            assertEquals(text.isEmpty() ? TPCharacterScanner.EOF : text.charAt(0), input.read());
        }
    }
}
