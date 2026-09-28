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

import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import static org.junit.jupiter.api.Assertions.*;

class PostgreXmlPlanHierarchyTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "Hash Join,JOIN", "Merge Join,JOIN", "Nested Loop,JOIN", "Hash,HASH",
        "HashAggregate,AGGREGATE", "Aggregate,AGGREGATE", "Seq Scan,TABLE_SCAN",
        "Parallel Seq Scan,TABLE_SCAN", "Foreign Scan,TABLE_SCAN", "Bitmap Heap Scan,TABLE_SCAN",
        "Index Scan,INDEX_SCAN", "Index Only Scan,INDEX_SCAN", "Insert,MODIFY",
        "ModifyTable,MODIFY", "Sort,SORT", "Function Scan,FUNCTION", "Unknown Operator,DEFAULT"
    })
    void operatorCategoryDistinguishesJoinsAggregatesAndScans(String type,
        org.jkiss.dbeaver.model.exec.plan.DBCPlanNodeKind expected) throws Exception {
        var node = parse("<Plan><Node-Type>" + type + "</Node-Type></Plan>");
        assertEquals(expected, node.getNodeKind());
        assertEquals(type, node.getNodeType(), "Classification must not rewrite the server label");
    }

    @Test
    void nodeClassificationDoesNotDependOnDesktopLocale() throws Exception {
        var previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertEquals(org.jkiss.dbeaver.model.exec.plan.DBCPlanNodeKind.INDEX_SCAN,
                parse("<Plan><Node-Type>Index Scan</Node-Type></Plan>").getNodeKind());
            assertEquals(org.jkiss.dbeaver.model.exec.plan.DBCPlanNodeKind.JOIN,
                parse("<Plan><Node-Type>Nested Loop</Node-Type></Plan>").getNodeKind());
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void bufferStatisticsRemainSeparateForParentAndChild() throws Exception {
        var node = parse("<Plan><Node-Type>Hash Join</Node-Type><Shared-Hit-Blocks>123</Shared-Hit-Blocks>"
            + "<Shared-Read-Blocks>0</Shared-Read-Blocks><Temp-Written-Blocks>4294967296</Temp-Written-Blocks>"
            + "<I-O-Read-Time>0.125</I-O-Read-Time><Plans><Plan><Node-Type>Seq Scan</Node-Type>"
            + "<Shared-Hit-Blocks>7</Shared-Hit-Blocks><Local-Read-Blocks>3</Local-Read-Blocks>"
            + "</Plan></Plans></Plan>");
        assertEquals("123", node.getPropertyValue(null, "Shared-Hit-Blocks"));
        assertEquals("0", node.getPropertyValue(null, "Shared-Read-Blocks"));
        assertEquals("4294967296", node.getPropertyValue(null, "Temp-Written-Blocks"));
        assertEquals("0.125", node.getPropertyValue(null, "I-O-Read-Time"));
        var child = node.getNested().getFirst();
        assertEquals("7", child.getPropertyValue(null, "Shared-Hit-Blocks"));
        assertEquals("3", child.getPropertyValue(null, "Local-Read-Blocks"));
        assertNull(child.getPropertyValue(null, "Shared-Read-Blocks"));
        assertSame(node, child.getParent());
    }

    @Test
    void zeroActualRowsOverrideEstimateAndMissingMetricsAreNotInvented() throws Exception {
        var node = parse("<Plan><Node-Type>Result</Node-Type><Plan-Rows>500</Plan-Rows>"
            + "<Actual-Rows>0</Actual-Rows><Actual-Loops>0</Actual-Loops>"
            + "<Actual-Total-Time>0.000</Actual-Total-Time></Plan>");
        assertEquals(0L, node.getNodeRowCount().longValue());
        assertEquals(0.0, node.getNodeDuration().doubleValue());
        assertEquals("0", node.getPropertyValue(null, "Actual-Loops"));
        assertNull(node.getNodeCost());
        assertEquals("", node.getCost());
        assertNull(node.getPropertyValue(null, "Shared-Hit-Blocks"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"<Plan/>", "<Plan><Node-Type/></Plan>"})
    void missingNodeTypeUsesDefaultKindWithoutCrashing(String xml) throws Exception {
        assertEquals(org.jkiss.dbeaver.model.exec.plan.DBCPlanNodeKind.DEFAULT, parse(xml).getNodeKind());
    }

    @Test
    void parallelXmlNodeStillGetsItsDisplayPrefix() throws Exception {
        var node = parse("<Plan><Node-Type>Seq Scan</Node-Type><Parallel-Aware>true</Parallel-Aware></Plan>");
        assertEquals("Parallel Seq Scan", node.getNodeType());
    }

    private PostgrePlanNodeXML parse(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return new PostgrePlanNodeXML(null, null,
            factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement());
    }

    @Test
    void siblingsShareTheirActualParentAndPreserveOrder() throws Exception {
        var root = parse("<Plan><Node-Type>Hash Join</Node-Type><Plans>"
            + "<Plan><Node-Type>Seq Scan</Node-Type><Relation-Name>a</Relation-Name></Plan>"
            + "<Plan><Node-Type>Hash</Node-Type></Plan></Plans></Plan>");
        assertNull(root.getParent());
        assertEquals(2, root.getNested().size());
        assertEquals("Seq Scan", root.getNested().get(0).getNodeType());
        assertEquals("Hash", root.getNested().get(1).getNodeType());
        assertEquals("a", root.getNested().get(0).getEntity());
        for (var child : root.getNested()) assertSame(root, child.getParent());
    }

    @Test
    void grandchildPointsToImmediateParentNotRoot() throws Exception {
        var root = parse("<Plan><Node-Type>Sort</Node-Type><Plans>"
            + "<Plan><Node-Type>Aggregate</Node-Type><Plans><Plan><Node-Type>Seq Scan</Node-Type>"
            + "</Plan></Plans></Plan></Plans></Plan>");
        var middle = root.getNested().getFirst();
        var leaf = middle.getNested().getFirst();
        assertSame(root, middle.getParent());
        assertSame(middle, leaf.getParent());
        assertTrue(leaf.getNested().isEmpty());
    }

    @Test
    void costsAndEstimatedRowsRemainAvailableWithoutAnalyze() throws Exception {
        var node = parse("<Plan><Node-Type>Seq Scan</Node-Type><Startup-Cost>0.00</Startup-Cost>"
            + "<Total-Cost>12.50</Total-Cost><Plan-Rows>37</Plan-Rows></Plan>");
        assertEquals(12.5, node.getNodeCost().doubleValue());
        assertEquals(37, node.getNodeRowCount().longValue());
        assertNull(node.getNodeDuration());
    }
}
