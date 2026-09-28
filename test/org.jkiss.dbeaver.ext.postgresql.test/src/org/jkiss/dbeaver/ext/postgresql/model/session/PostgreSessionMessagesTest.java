/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.postgresql.model.session;

import org.jkiss.dbeaver.ext.postgresql.internal.PostgreSQLMessages;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class PostgreSessionMessagesTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "_zh"})
    void sessionErrorsHavePackagedEnglishAndChineseResources(String locale) throws Exception {
        Properties messages = new Properties();
        try (var stream = PostgreSQLMessages.class.getResourceAsStream("PostgreSQLMessages" + locale + ".properties")) {
            assertNotNull(stream);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                messages.load(reader);
            }
        }
        for (String key : java.util.List.of("session_invalid_identifier", "session_identifier_null",
            "session_operation_not_confirmed")) {
            String value = messages.getProperty(key);
            assertNotNull(value, key);
            assertFalse(value.isBlank(), key);
            assertTrue(value.contains(locale.isEmpty() ? "session" : "会话"), key);
            assertEquals(String.class, PostgreSQLMessages.class.getField(key).getType());
            String loaded = (String) PostgreSQLMessages.class.getField(key).get(null);
            assertNotNull(loaded, key);
            assertFalse(loaded.isBlank() || loaded.equals("!" + key + "!"), key);
        }
    }
}
