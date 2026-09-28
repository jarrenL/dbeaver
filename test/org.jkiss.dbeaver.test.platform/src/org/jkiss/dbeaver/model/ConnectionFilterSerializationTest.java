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

    @Test
    void validSettingsBatchKeepsDocumentOrderAndIgnoresUnrelatedValues() {
        var source = mock(DataSourceDescriptor.class);
        var settings = new java.util.LinkedHashMap<String, String>();
        settings.put("navigator-filters.first", "[{\"type\":\"schema\",\"id\":\"first\",\"include\":[\"A%\"]}]");
        settings.put("unrelated", "not JSON and not a filter");
        settings.put("navigator-filters.second", "[{\"type\":\"table\",\"id\":\"second\",\"exclude\":[\"B%\"]}]");
        org.jkiss.dbeaver.registry.UserDBSObjectFilterUtils.setUserObjectFilters(source, settings);
        var calls = List.copyOf(mockingDetails(source).getInvocations());
        assertEquals(2, calls.size());
        assertEquals("first", calls.get(0).getArgument(1));
        assertEquals("second", calls.get(1).getArgument(1));
        assertTrue(((DBSObjectFilter) calls.get(0).getArgument(2)).isUserFilter());
        assertTrue(((DBSObjectFilter) calls.get(1).getArgument(2)).isUserFilter());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedSettingsBatchDoesNotPartiallyApplyFilters(boolean invalidFirst) {
        var source = mock(DataSourceDescriptor.class);
        var settings = new java.util.LinkedHashMap<String, String>();
        String valid = "[{\"type\":\"schema\",\"include\":[\"A%\"]}]";
        settings.put("navigator-filters.first", invalidFirst ? "[null]" : valid);
        settings.put("navigator-filters.second", invalidFirst ? valid : "[null]");
        assertThrows(com.google.gson.JsonParseException.class, () ->
            org.jkiss.dbeaver.registry.UserDBSObjectFilterUtils.setUserObjectFilters(source, settings));
        verifyNoInteractions(source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"navigator-filters", "navigator-filters.schema", "filters", "", "unrelated"})
    void filterUpdateNotificationsUseSameKeyScopeAsImport(String key) {
        var project = mock(org.jkiss.dbeaver.model.app.DBPProject.class, RETURNS_DEEP_STUBS);
        var source = mock(DataSourceDescriptor.class, RETURNS_DEEP_STUBS);
        when(project.getDataSourceRegistry().getDataSource("fixture-connection")).thenReturn(source);
        when(source.getId()).thenReturn("fixture-connection");
        var registry = source.getRegistry();
        org.jkiss.dbeaver.registry.UserDBSObjectFilterUtils.objectSettingUpdated(project, "fixture-connection", List.of(key));
        if (key.startsWith("navigator-filters")) {
            verify(registry).refreshConfig(List.of("fixture-connection"));
        } else {
            verifyNoInteractions(registry);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[{\"type\":[]}]", "[{\"id\":{}}]", "[{\"name\":1}]",
        "[{\"description\":true}]", "[{\"enabled\":\"invalid\"}]", "[{\"case-sensitive\":[]}]"
    })
    void malformedScopeAndFlagsAreRejected(String json) {
        assertThrows(com.google.gson.JsonParseException.class, () -> serializer.deserializeObjectFilterConfig(json));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyBooleanStringsPreserveTheirMeaning(boolean flag) {
        var filter = serializer.deserializeObjectFilterConfig(
            "[{\"enabled\":\"" + flag + "\",\"case-sensitive\":\"" + flag + "\"}]").getFirst().filter();
        assertEquals(flag, filter.isEnabled());
        assertEquals(flag, filter.isCaseSensitive());
    }

    @Test
    void invalidLaterEntryDoesNotApplyEarlierUserFilter() {
        var source = mock(DataSourceDescriptor.class);
        assertThrows(com.google.gson.JsonParseException.class, () ->
            org.jkiss.dbeaver.registry.UserDBSObjectFilterUtils.setUserObjectFilters(source, java.util.Map.of(
                "navigator-filters", "[{\"type\":\"schema\",\"id\":\"db1\",\"include\":[\"A%\"]},"
                    + "{\"type\":\"table\",\"include\":[null]}]")));
        verifyNoInteractions(source);
    }

    @Test
    void userImportPreservesScopeAndMarksOnlyApplicableEntries() {
        var source = mock(DataSourceDescriptor.class);
        org.jkiss.dbeaver.registry.UserDBSObjectFilterUtils.setUserObjectFilters(source, java.util.Map.of(
            "navigator-filters", "[{\"type\":\"schema\",\"id\":\"中文库\",\"enabled\":true,\"include\":[\"A%\"]},"
                + "{\"type\":\"table\",\"id\":\"db2\",\"exclude\":[\"private%\"]},{\"include\":[\"ignored%\"]}]"));
        // The string-scoped setter is protected; inspect actual recorded calls without changing its visibility.
        var calls = List.copyOf(mockingDetails(source).getInvocations());
        assertEquals(2, calls.size());
        assertTrue(calls.stream().allMatch(call -> call.getMethod().getName().equals("setObjectFilter")));
        assertEquals("schema", calls.get(0).getArgument(0));
        assertEquals("中文库", calls.get(0).getArgument(1));
        assertEquals("table", calls.get(1).getArgument(0));
        assertEquals("db2", calls.get(1).getArgument(1));
        DBSObjectFilter first = calls.get(0).getArgument(2);
        DBSObjectFilter second = calls.get(1).getArgument(2);
        assertTrue(first.isUserFilter());
        assertTrue(second.isUserFilter());
        assertTrue(first.matches("Accounts"));
        assertFalse(first.matches("Other"));
        assertEquals(List.of("private%"), second.getExclude());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[{\"enabled\":true}]",
        "[{\"enabled\":true,\"include\":null,\"exclude\":null}]",
        "[{\"enabled\":true,\"include\":[],\"exclude\":[]}]"
    })
    void legacyEmptyPatternRepresentationsRemainValid(String json) {
        var filter = serializer.deserializeObjectFilterConfig(json).getFirst().filter();
        assertTrue(filter.isEnabled());
        assertTrue(filter.getInclude().isEmpty());
        assertTrue(filter.getExclude().isEmpty());
        assertTrue(filter.matches("任意对象"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[null]", "[{\"include\":[null]}]", "[{\"include\":[1]}]",
        "[{\"include\":[{}]}]", "[{\"exclude\":[true]}]", "[{\"include\":\"A%\"}]"
    })
    void invalidFilterMembersAreRejectedBeforeApplyingConfiguration(String json) {
        assertThrows(com.google.gson.JsonParseException.class,
            () -> serializer.deserializeObjectFilterConfig(json));
        var valid = serializer.deserializeObjectFilterConfig(
            "[{\"type\":\"schema\",\"enabled\":true,\"include\":[\"客户%\"],\"exclude\":[\"客户私有%\"]}]").getFirst();
        assertTrue(valid.filter().matches("客户业务"));
        assertFalse(valid.filter().matches("客户私有表"));
        assertFalse(valid.filter().matches("其他表"));
    }

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
