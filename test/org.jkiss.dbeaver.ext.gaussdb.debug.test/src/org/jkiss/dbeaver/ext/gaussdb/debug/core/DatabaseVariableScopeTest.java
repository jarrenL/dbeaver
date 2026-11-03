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
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.eclipse.debug.core.model.IWatchExpressionResult;
import org.jkiss.dbeaver.debug.DBGSession;
import org.jkiss.dbeaver.debug.DBGStackFrame;
import org.jkiss.dbeaver.debug.DBGVariable;
import org.jkiss.dbeaver.debug.DBGVariableType;
import org.jkiss.dbeaver.debug.core.model.DatabaseStackFrame;
import org.jkiss.dbeaver.debug.core.model.DatabaseThread;
import org.jkiss.dbeaver.debug.core.model.DatabaseWatchExpressionDelegate;
import org.jkiss.dbeaver.debug.core.model.IDatabaseDebugTarget;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBCursorVariables;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugVariable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseVariableScopeTest {
    private DatabaseStackFrame frame(List<? extends DBGVariable<?>> variables) throws Exception {
        IDatabaseDebugTarget target = mock(IDatabaseDebugTarget.class);
        DBGSession session = mock(DBGSession.class);
        DBGStackFrame serverFrame = mock(DBGStackFrame.class);
        when(target.getSession()).thenReturn(session);
        doReturn(variables).when(session).getVariables(serverFrame);
        return new DatabaseStackFrame(new DatabaseThread(target), serverFrame);
    }

    private List<DBGVariable<?>> packageCursors() {
        return GaussDBCursorVariables.aggregate(List.of(
            new GaussDBDebugVariable("cur", "refcursor", "{row_count:\t1}", "pkg_one", false, 0),
            new GaussDBDebugVariable("cur", "refcursor", "{row_count:\t9}", "pkg_two", false, 0)));
    }

    @Test
    void realStackFrameRetainsSameNamedCursorsFromDifferentPackages() throws Exception {
        var variables = frame(packageCursors()).getVariables();

        assertEquals(2, variables.length, "The model must not discard a cursor merely because another package uses its name");
        assertEquals("cur", variables[0].getName());
        assertEquals("cur", variables[1].getName());
        assertEquals("(ROWCOUNT=1)", variables[0].getValue().getValueString());
        assertEquals("(ROWCOUNT=9)", variables[1].getValue().getValueString());
        for (int index = 0; index < variables.length; index++) {
            var attributes = List.of(variables[index].getValue().getVariables());
            var packageAttribute = attributes.stream().filter(attribute -> {
                try {
                    return "PACKAGE".equals(attribute.getName());
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }).findFirst().orElseThrow();
            assertEquals(index == 0 ? "pkg_one" : "pkg_two", packageAttribute.getValue().getValueString());
            assertFalse(packageAttribute.supportsValueModification());
        }
    }

    @Test
    void attributeWatchOnRealStackFrameRejectsAmbiguousPackageCursorName() throws Exception {
        List<IWatchExpressionResult> results = new ArrayList<>();
        new DatabaseWatchExpressionDelegate().evaluateExpression("cur%ROWCOUNT", frame(packageCursors()), results::add);

        assertEquals(1, results.size());
        IWatchExpressionResult result = results.getFirst();
        assertTrue(result.hasErrors());
        assertNull(result.getValue());
        assertTrue(java.util.Arrays.stream(result.getErrorMessages())
            .anyMatch(message -> message.toLowerCase(java.util.Locale.ROOT).contains("ambiguous")));
    }

    @Test
    void providersWithoutScopeKeepTheirExistingDeclarationLineDeduplication() throws Exception {
        var outer = new PlainVariable("count", "1", 2);
        var inner = new PlainVariable("count", "7", 8);
        assertNull(outer.getScopeName(), "Providers that do not expose package scope retain the original null scope");

        var variables = frame(List.of(outer, inner)).getVariables();

        assertEquals(1, variables.length);
        assertEquals("7", variables[0].getValue().getValueString());
        assertTrue(variables[0].supportsValueModification());
    }

    @Test
    void lowerDeclarationLineDoesNotReplaceAnExistingVariableInTheSameScope() throws Exception {
        var inner = new PlainVariable("count", "7", 8);
        var outer = new PlainVariable("count", "1", 2);

        var variables = frame(List.of(inner, outer)).getVariables();

        assertEquals(1, variables.length);
        assertEquals("7", variables[0].getValue().getValueString());
    }

    @Test
    void duplicateRowsWithinOnePackageStillDeduplicateWithoutDiscardingAnotherPackage() throws Exception {
        var first = new GaussDBDebugVariable("count", "integer", "1", "pkg_one", false, 0);
        var replacement = new GaussDBDebugVariable("count", "integer", "2", "pkg_one", false, 0);
        var anotherPackage = new GaussDBDebugVariable("count", "integer", "9", "pkg_two", false, 0);

        var variables = frame(List.of(first, replacement, anotherPackage)).getVariables();

        assertEquals(2, variables.length);
        assertEquals("2", variables[0].getValue().getValueString());
        assertEquals("9", variables[1].getValue().getValueString());
    }

    private record PlainVariable(String name, String value, int line) implements DBGVariable<String> {
        @Override
        public String getVal() { return value; }
        @Override
        public String getName() { return name; }
        @Override
        public DBGVariableType getType() { return DBGVariableType.NUMBER; }
        @Override
        public int getLineNumber() { return line; }
    }
}
