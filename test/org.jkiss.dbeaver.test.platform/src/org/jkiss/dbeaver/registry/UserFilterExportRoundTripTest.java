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
package org.jkiss.dbeaver.registry;

import com.google.gson.stream.JsonWriter;
import org.jkiss.dbeaver.model.struct.DBSObjectFilter;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserFilterExportRoundTripTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void userAndConnectionFiltersStaySeparateAcrossUtf8FileRoundTrip(int variant) throws Exception {
        boolean global = (variant & 1) == 0;
        boolean enabled = (variant & 2) == 0;
        boolean empty = (variant & 4) != 0;
        var user = new DBSObjectFilter();
        user.setUserFilter(true);
        user.setEnabled(enabled);
        user.setCaseSensitive(true);
        user.setName("客户\"过滤𠀀");
        user.setDescription("说明\n第二行");
        if (!empty) {
            user.setInclude(List.of("Bank%", "中文%"));
            user.setExclude(List.of("Bank_private%"));
        }
        var userMapping = new FilterMapping("schema");
        String scope = global ? null : "数据库:中文𠀀";
        if (global) userMapping.globalFilter = user;
        else userMapping.customFilters.put(scope, user);

        var defaults = new DBSObjectFilter();
        defaults.setEnabled(true);
        defaults.setInclude(List.of("DefaultOnly%"));
        var defaultMapping = new FilterMapping("table");
        defaultMapping.globalFilter = defaults;
        var source = mock(DataSourceDescriptor.class);
        when(source.getObjectFilters()).thenReturn(List.of(userMapping, defaultMapping));
        var serializer = new FilterSerializer<DataSourceDescriptor>();

        Path file = directory.resolve("连接过滤器.json");
        String json = serializer.serializeCustomUserFilters(source);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        String restoredJson = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(json, restoredJson);
        var configs = serializer.deserializeObjectFilterConfig(restoredJson);
        assertEquals(1, configs.size(), "Default connection filter must not enter the user export");
        assertEquals("schema", configs.getFirst().typeName());
        assertEquals(scope, configs.getFirst().objectID());

        var destination = mock(DataSourceDescriptor.class);
        UserDBSObjectFilterUtils.setUserObjectFilters(destination, Map.of(UserDBSObjectFilterUtils.USER_FILTER_KEY, restoredJson));
        var captured = org.mockito.ArgumentCaptor.forClass(DBSObjectFilter.class);
        verify(destination).setObjectFilter(eq("schema"), eq(scope), captured.capture());
        verifyNoMoreInteractions(destination);
        var imported = captured.getValue();
        assertTrue(imported.isUserFilter());
        assertEquals(enabled, imported.isEnabled());
        assertTrue(imported.isCaseSensitive());
        assertEquals(user.getName(), imported.getName());
        assertEquals(user.getDescription(), imported.getDescription());
        if (empty) {
            // JSON import normalizes absent lists to empty lists; semantic emptiness is the contract.
            assertTrue(imported.isEmpty());
            assertTrue(imported.getInclude().isEmpty());
            assertTrue(imported.getExclude().isEmpty());
            assertNull(user.getInclude());
            assertNull(user.getExclude());
        } else {
            assertEquals(user.getInclude(), imported.getInclude());
            assertEquals(user.getExclude(), imported.getExclude());
        }

        var connectionJson = new StringWriter();
        try (var writer = new JsonWriter(connectionJson)) {
            serializer.saveObjectFilters(writer, null, source, false);
        }
        var connectionFilters = serializer.deserializeObjectFilterConfig(connectionJson.toString());
        assertEquals(1, connectionFilters.size(), "User overrides must not enter connection defaults");
        assertEquals("table", connectionFilters.getFirst().typeName());
        assertEquals(List.of("DefaultOnly%"), connectionFilters.getFirst().filter().getInclude());
        assertTrue(user.isUserFilter());
        assertFalse(defaults.isUserFilter());
    }
}
