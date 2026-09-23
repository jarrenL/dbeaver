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
