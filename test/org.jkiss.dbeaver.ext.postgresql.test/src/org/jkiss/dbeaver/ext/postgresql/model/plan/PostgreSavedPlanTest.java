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
package org.jkiss.dbeaver.ext.postgresql.model.plan;

import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreSavedPlanTest {
    private static final String DOCUMENT = """
        {"version":"1","signature":"fixture-driver","sql":"SELECT '中文'",
         "root":[{"type":"Sort","attributes":[{"Plan-Rows":"1000"},
          {"Actual-Rows":"5"},{"Actual-Total-Time":"0.022"},{"Total-Cost":"74.83"}],
          "child":[{"type":"Function Scan","attributes":[{"Function-Name":"generate_series"}]}]}]}
        """;

    private PostgreQueryPlaner planner() {
        var source = mock(PostgreDataSource.class, RETURNS_DEEP_STUBS);
        when(source.getInfo().getDriverName()).thenReturn("fixture-driver");
        return new PostgreQueryPlaner(source);
    }

    @Test
    void roundTripPreservesUnicodeHierarchyAndActualStatistics() throws Exception {
        var planner = planner();
        var plan = planner.deserialize(new StringReader(DOCUMENT));
        var writer = new StringWriter();
        planner.serialize(writer, plan);
        var restored = planner.deserialize(new StringReader(writer.toString()));
        assertEquals("SELECT '中文'", restored.getQueryString());
        var root = (PostgrePlanNodeExternal) restored.getPlanNodes(Map.of()).iterator().next();
        assertEquals("Sort", root.getNodeType());
        assertEquals(5, root.getNodeRowCount().longValue());
        assertEquals(0.022, root.getNodeDuration().doubleValue());
        assertEquals(74.83, root.getNodeCost().doubleValue());
        assertNull(root.getParent());
        var child = root.getNested().iterator().next();
        assertSame(root, child.getParent());
        assertEquals("Function Scan", child.getNodeType());
        assertTrue(child.getNested().isEmpty());
        verify(planner.getDataSource(), atLeastOnce()).getInfo();
        verifyNoMoreInteractions(planner.getDataSource());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{", "{}",
        "{\"sql\":\"select 1\",\"signature\":\"fixture-driver\",\"root\":null}"})
    void malformedInputIsReportedAndDoesNotPoisonNextLoad(String text) throws Exception {
        var planner = planner();
        assertThrows(InvocationTargetException.class, () -> planner.deserialize(new StringReader(text)));
        assertEquals("SELECT '中文'", planner.deserialize(new StringReader(DOCUMENT)).getQueryString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2", "999"})
    void incompatibleVersionIsRejected(String version) {
        var planner = planner();
        String input = DOCUMENT.replace("\"version\":\"1\"", "\"version\":\"" + version + "\"");
        assertThrows(InvocationTargetException.class, () -> planner.deserialize(new StringReader(input)));
    }

    @Test
    void missingVersionIsRejected() {
        var planner = planner();
        assertThrows(InvocationTargetException.class,
            () -> planner.deserialize(new StringReader(DOCUMENT.replace("\"version\":\"1\",", ""))));
    }

    @Test
    void otherDriverSignatureIsRejected() {
        var planner = planner();
        assertThrows(InvocationTargetException.class,
            () -> planner.deserialize(new StringReader(DOCUMENT.replace("fixture-driver", "different-driver"))));
    }
}
