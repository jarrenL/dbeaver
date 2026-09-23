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
package org.jkiss.dbeaver.model;

import com.google.gson.stream.JsonWriter;
import org.jkiss.dbeaver.model.struct.DBSObjectFilter;
import org.jkiss.dbeaver.registry.DataSourceDescriptor;
import org.jkiss.dbeaver.registry.FilterSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectionFilterSerializationTest {
    private final FilterSerializer<DataSourceDescriptor> serializer = new FilterSerializer<>();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void roundTripPreservesSchemaFiltersAndCaseMatching(boolean caseSensitive) throws Exception {
        var filter = new DBSObjectFilter();
        filter.setName("客户\"过滤器");
        filter.setDescription("中文𠀀\n说明");
        filter.setEnabled(true);
        filter.setCaseSensitive(caseSensitive);
        filter.setInclude(List.of("Bank%", "中文%"));
        filter.setExclude(List.of("Bank_private%"));
        var output = new StringWriter();
        try (var writer = new JsonWriter(output)) {
            writer.beginArray();
            serializer.saveObjectFilter(writer, "schema", "database:中文", filter);
            writer.endArray();
        }
        var configs = serializer.deserializeObjectFilterConfig(output.toString());
        assertEquals(1, configs.size());
        var config = configs.getFirst();
        assertEquals("schema", config.typeName());
        assertEquals("database:中文", config.objectID());
        var restored = config.filter();
        assertEquals(filter.getName(), restored.getName());
        assertEquals(filter.getDescription(), restored.getDescription());
        assertEquals(filter.getInclude(), restored.getInclude());
        assertEquals(filter.getExclude(), restored.getExclude());
        assertTrue(restored.isEnabled());
        assertEquals(caseSensitive, restored.isCaseSensitive());
        assertTrue(restored.matches("Bank_public"));
        assertTrue(restored.matches("中文表"));
        assertFalse(restored.matches("Bank_private_data"));
        assertEquals(!caseSensitive, restored.matches("bank_public"));
    }

    @Test
    void noUserFiltersSerializeToAnEmptyArray() throws Exception {
        var source = mock(DataSourceDescriptor.class);
        when(source.getObjectFilters()).thenReturn(List.of());
        String json = serializer.serializeCustomUserFilters(source);
        assertEquals("[]", json);
        assertTrue(serializer.deserializeObjectFilterConfig(json).isEmpty());
    }

    @Test
    void legacyConfigWithoutCaseFlagRetainsInsensitiveMatchingAndDisabledState() {
        var config = serializer.deserializeObjectFilterConfig(
            "[{\"type\":\"schema\",\"enabled\":false,\"include\":[\"Bank%\"]}]").getFirst();
        assertFalse(config.filter().isEnabled());
        assertFalse(config.filter().isCaseSensitive());
        assertTrue(config.filter().isNotApplicable());
        assertTrue(config.filter().matches("bank_public"));
    }

    @Test
    void multipleFiltersKeepTheirIndependentScopes() {
        var configs = serializer.deserializeObjectFilterConfig(
            "[{\"type\":\"schema\",\"id\":\"db1\",\"include\":[\"A%\"]},"
                + "{\"type\":\"table\",\"id\":\"db2\",\"exclude\":[\"private%\"]}]");
        assertEquals(2, configs.size());
        assertEquals("db1", configs.get(0).objectID());
        assertEquals("db2", configs.get(1).objectID());
        configs.get(0).filter().addExclude("new%");
        assertEquals(List.of("private%"), configs.get(1).filter().getExclude());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[", "{}", "[1]"})
    void malformedOrWrongShapeDocumentsAreRejected(String json) {
        assertThrows(com.google.gson.JsonParseException.class,
            () -> serializer.deserializeObjectFilterConfig(json));
    }

    @Test
    void emptyNamedFilterFieldIsStillOmittedFromParentConfiguration() throws Exception {
        var source = mock(DataSourceDescriptor.class);
        when(source.getObjectFilters()).thenReturn(List.of());
        var output = new StringWriter();
        try (var writer = new JsonWriter(output)) {
            writer.beginObject();
            serializer.saveObjectFilters(writer, "filters", source, false);
            writer.name("name").value("连接");
            writer.endObject();
        }
        assertEquals("{\"name\":\"连接\"}", output.toString());
    }
}
