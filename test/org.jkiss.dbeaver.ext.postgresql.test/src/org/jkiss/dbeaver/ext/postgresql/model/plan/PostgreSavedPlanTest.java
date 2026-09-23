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
        "{\"version\":\"1\",\"sql\":\"select 1\",\"signature\":\"fixture-driver\",\"root\":null}"})
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

    @Test
    void parallelNodeTypeDoesNotAccumulatePrefixesAcrossSaves() throws Exception {
        var planner = planner();
        var plan = planner.deserialize(new StringReader(DOCUMENT.replace("\"Plan-Rows\":\"1000\"",
            "\"Parallel-Aware\":\"true\"")));
        assertEquals("Parallel Sort", plan.getPlanNodes(Map.of()).iterator().next().getNodeType());
        for (int round = 0; round < 3; round++) {
            var writer = new StringWriter();
            planner.serialize(writer, plan);
            plan = planner.deserialize(new StringReader(writer.toString()));
            assertEquals("Parallel Sort", plan.getPlanNodes(Map.of()).iterator().next().getNodeType());
        }
    }

    @Test
    void multipleRootsAndUnknownNodeAttributesSurviveRoundTrip() throws Exception {
        var planner = planner();
        String input = """
            {"version":"1","signature":"fixture-driver","sql":"SELECT 1","root":[
             {"type":"Custom Stream","attributes":[{"Vendor-Detail":"中文扩展"}]},
             {"type":"Seq Scan","attributes":[{"Plan-Rows":"7"}]}]}
            """;
        var plan = planner.deserialize(new StringReader(input));
        var writer = new StringWriter();
        planner.serialize(writer, plan);
        var roots = new java.util.ArrayList<>(planner.deserialize(new StringReader(writer.toString())).getPlanNodes(Map.of()));
        assertEquals(2, roots.size());
        assertEquals("Custom Stream", roots.get(0).getNodeType());
        assertEquals("中文扩展", ((PostgrePlanNodeExternal) roots.get(0)).attributes.get("Vendor-Detail"));
        assertNull(roots.get(0).getParent());
        assertNull(roots.get(1).getParent());
        assertEquals(7, ((PostgrePlanNodeExternal) roots.get(1)).getNodeRowCount().longValue());
        assertNull(((PostgrePlanNodeExternal) roots.get(1)).getNodeDuration());
    }

    @Test
    void emptyPlanIsValidAndDoesNotInventNodes() throws Exception {
        var planner = planner();
        var plan = planner.deserialize(new StringReader(
            "{\"version\":\"1\",\"signature\":\"fixture-driver\",\"sql\":\"SELECT 1\",\"root\":[]}"));
        assertTrue(plan.getPlanNodes(Map.of()).isEmpty());
        var writer = new StringWriter();
        planner.serialize(writer, plan);
        assertTrue(planner.deserialize(new StringReader(writer.toString())).getPlanNodes(Map.of()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "null", "7", "{}", "{\"type\":\"Sort\"}",
        "{\"type\":\"Sort\",\"attributes\":null}",
        "{\"type\":\"Sort\",\"attributes\":[null]}",
        "{\"type\":\"Sort\",\"attributes\":[],\"child\":[null]}"
    })
    void malformedNodeUnderValidEnvelopeIsRejectedAndRetryWorks(String node) throws Exception {
        var planner = planner();
        String input = "{\"version\":\"1\",\"signature\":\"fixture-driver\",\"sql\":\"SELECT 1\",\"root\":["
            + node + "]}";
        assertThrows(InvocationTargetException.class, () -> planner.deserialize(new StringReader(input)));
        assertEquals("SELECT '中文'", planner.deserialize(new StringReader(DOCUMENT)).getQueryString());
    }

    @Test
    void interruptedReaderPreservesFailureAndDoesNotCloseCallerResource() throws Exception {
        var failure = new java.io.IOException("synthetic read interruption");
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var reader = new java.io.Reader() {
            private boolean started;

            @Override
            public int read(char[] buffer, int offset, int length) throws java.io.IOException {
                if (started) {
                    throw failure;
                }
                started = true;
                buffer[offset] = '{';
                return 1;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        var planner = planner();
        var error = assertThrows(InvocationTargetException.class, () -> planner.deserialize(reader));
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertSame(failure, cause);
        assertFalse(closed.get(), "Reader ownership remains with the caller");
        reader.close();
        assertTrue(closed.get());
        assertEquals("SELECT '中文'", planner.deserialize(new StringReader(DOCUMENT)).getQueryString());
    }
}
