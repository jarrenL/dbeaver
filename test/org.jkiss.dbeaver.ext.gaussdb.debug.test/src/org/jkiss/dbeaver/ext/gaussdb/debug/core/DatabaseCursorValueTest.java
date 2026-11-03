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

import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.debug.core.model.IWatchExpressionResult;
import org.jkiss.dbeaver.debug.DBGSession;
import org.jkiss.dbeaver.debug.DBGVariable;
import org.jkiss.dbeaver.debug.DBGVariableType;
import org.jkiss.dbeaver.debug.core.model.DatabaseStackFrame;
import org.jkiss.dbeaver.debug.core.model.DatabaseValue;
import org.jkiss.dbeaver.debug.core.model.DatabaseVariable;
import org.jkiss.dbeaver.debug.core.model.DatabaseWatchExpressionDelegate;
import org.jkiss.dbeaver.debug.core.model.IDatabaseDebugTarget;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBCursorVariables;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugVariable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseCursorValueTest {
    private static final String OPEN_CURSOR = "{name:\tportal_1\nis_open:\tt\nfound:\tf\nnot_found:\tf\nrow_count:\t0}";

    private DBGVariable<?> cursor(String name, String value) {
        return GaussDBCursorVariables.aggregate(List.of(
            new GaussDBDebugVariable(name, "refcursor", value, null, false, 0)
        )).getFirst();
    }

    private Map<String, IVariable> children(IValue value) throws Exception {
        Map<String, IVariable> variables = new java.util.LinkedHashMap<>();
        for (IVariable variable : value.getVariables()) {
            assertNull(variables.put(variable.getName(), variable), "Attribute names must be unique");
        }
        return variables;
    }

    private IWatchExpressionResult watch(String expression, DatabaseStackFrame frame) {
        List<IWatchExpressionResult> results = new ArrayList<>();
        new DatabaseWatchExpressionDelegate().evaluateExpression(expression, frame, results::add);
        assertEquals(1, results.size(), "Each watch evaluation must finish exactly once");
        return results.getFirst();
    }

    private DatabaseStackFrame frame(IVariable... variables) throws Exception {
        DatabaseStackFrame frame = mock(DatabaseStackFrame.class);
        when(frame.getVariables()).thenReturn(variables);
        return frame;
    }

    @Test
    void cursorValueExpandsToTypedReadOnlySnapshotWithoutQueryingTheSession() throws Exception {
        IDatabaseDebugTarget target = mock(IDatabaseDebugTarget.class);
        DBGSession session = mock(DBGSession.class);
        when(target.getSession()).thenReturn(session);
        DatabaseVariable variable = new DatabaseVariable(target, cursor("cur", OPEN_CURSOR));

        assertEquals("refcursor", variable.getReferenceTypeName());
        assertFalse(variable.supportsValueModification());
        assertFalse(variable.verifyValue("another_portal"));
        IValue value = variable.getValue();
        assertEquals("refcursor", value.getReferenceTypeName());
        assertTrue(value.hasVariables());
        Map<String, IVariable> attributes = children(value);
        assertEquals(java.util.Set.of("NAME", "%ISOPEN", "%FOUND", "%NOTFOUND", "%ROWCOUNT"), attributes.keySet());
        assertEquals("portal_1", attributes.get("NAME").getValue().getValueString());
        assertEquals("true", attributes.get("%ISOPEN").getValue().getValueString());
        assertEquals("false", attributes.get("%FOUND").getValue().getValueString());
        assertEquals("0", attributes.get("%ROWCOUNT").getValue().getValueString());
        assertEquals("boolean", attributes.get("%ISOPEN").getReferenceTypeName());
        assertEquals("integer", attributes.get("%ROWCOUNT").getReferenceTypeName());
        for (IVariable attribute : attributes.values()) {
            assertFalse(attribute.supportsValueModification(), attribute.getName());
            assertFalse(attribute.getValue().hasVariables(), attribute.getName());
            assertEquals(0, attribute.getValue().getVariables().length);
        }
        verifyNoInteractions(session);
    }

    @Test
    void childArrayMutationDoesNotChangeTheValueSnapshot() throws Exception {
        DatabaseValue value = new DatabaseValue(mock(IDatabaseDebugTarget.class), cursor("cur", OPEN_CURSOR));
        IVariable[] initial = value.getVariables();
        int count = initial.length;
        initial[0] = null;
        IVariable[] reread = value.getVariables();
        assertEquals(count, reread.length);
        assertTrue(java.util.Arrays.stream(reread).allMatch(java.util.Objects::nonNull));
    }

    @Test
    void plainProviderVariablesKeepTheirDefaultTypeAndDoNotBecomeExpandable() throws Exception {
        DBGVariable<String> scalar = new DBGVariable<>() {
            @Override
            public String getVal() { return "ordinary text"; }
            @Override
            public String getName() { return "message"; }
            @Override
            public DBGVariableType getType() { return DBGVariableType.TEXT; }
            @Override
            public int getLineNumber() { return 0; }
        };
        IDatabaseDebugTarget target = mock(IDatabaseDebugTarget.class);
        when(target.getSession()).thenReturn(mock(DBGSession.class));
        DatabaseVariable variable = new DatabaseVariable(target, scalar);

        assertTrue(variable.supportsValueModification(), "Cursor support must not disable ordinary scalar editing");
        assertEquals(DBGVariableType.TEXT.toString(), variable.getReferenceTypeName());
        assertEquals(DBGVariableType.TEXT.toString(), variable.getValue().getReferenceTypeName());
        assertEquals("ordinary text", variable.getValue().getValueString());
        assertFalse(variable.getValue().hasVariables());
        assertEquals(0, variable.getValue().getVariables().length);
    }

    @Test
    void actualScalarDataTypeIsUsedInsteadOfTextCategory() throws Exception {
        DatabaseVariable variable = new DatabaseVariable(mock(IDatabaseDebugTarget.class),
            new GaussDBDebugVariable("amount", "numeric(38,12)", "123.456", null, false, 0));
        assertEquals("numeric(38,12)", variable.getReferenceTypeName());
        assertEquals("numeric(38,12)", variable.getValue().getReferenceTypeName());
    }

    @Test
    void parentCursorWatchRetainsExpandableValue() throws Exception {
        DatabaseVariable variable = new DatabaseVariable(mock(IDatabaseDebugTarget.class), cursor("cur", OPEN_CURSOR));
        IWatchExpressionResult result = watch("  cur  ", frame(variable));
        assertFalse(result.hasErrors());
        assertTrue(result.getValue().hasVariables());
        assertEquals("  cur  ", result.getExpressionText());
        assertEquals(5, result.getValue().getVariables().length);
    }

    @ParameterizedTest
    @CsvSource({"cur%ISOPEN,true", "cur%isopen,true", "cur%FoUnD,false", "cur%NOTFOUND,false", "cur%ROWCOUNT,0"})
    void cursorAttributeWatchReadsExistingSnapshot(String expression, String expected) throws Exception {
        IDatabaseDebugTarget target = mock(IDatabaseDebugTarget.class);
        DBGSession session = mock(DBGSession.class);
        when(target.getSession()).thenReturn(session);
        DatabaseVariable variable = new DatabaseVariable(target, cursor("cur", OPEN_CURSOR));
        IWatchExpressionResult result = watch(expression, frame(variable));
        assertFalse(result.hasErrors(), expression);
        assertEquals(expected, result.getValue().getValueString());
        verifyNoInteractions(session);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUR%ISOPEN", "cur%UNKNOWN", "cur%ROWCOUNT + 1", "cur%ISOPEN; DROP TABLE t", "cur.NAME", "cur%ISOPEN%FOUND"})
    void unsupportedWatchSyntaxDoesNotEvaluateSqlOrInventAttributes(String expression) throws Exception {
        IDatabaseDebugTarget target = mock(IDatabaseDebugTarget.class);
        DBGSession session = mock(DBGSession.class);
        when(target.getSession()).thenReturn(session);
        IWatchExpressionResult result = watch(expression, frame(new DatabaseVariable(target, cursor("cur", OPEN_CURSOR))));
        assertTrue(result.hasErrors(), expression);
        assertNull(result.getValue());
        verifyNoInteractions(session);
    }

    @Test
    void exactTopLevelNameTakesPrecedenceOverAttributeNotation() throws Exception {
        DatabaseVariable cursorVariable = new DatabaseVariable(mock(IDatabaseDebugTarget.class), cursor("cur", OPEN_CURSOR));
        DatabaseVariable quotedVariable = new DatabaseVariable(mock(IDatabaseDebugTarget.class),
            new GaussDBDebugVariable("cur%ISOPEN", "text", "literal variable", null, false, 0));
        IWatchExpressionResult result = watch("cur%ISOPEN", frame(cursorVariable, quotedVariable));
        assertFalse(result.hasErrors());
        assertEquals("literal variable", result.getValue().getValueString());
    }

    @Test
    void selectingAnotherFrameChangesCursorAttributeWatchWithoutReusingTheOldValue() throws Exception {
        IDatabaseDebugTarget target = mock(IDatabaseDebugTarget.class);
        DatabaseStackFrame first = frame(new DatabaseVariable(target, cursor("cur", OPEN_CURSOR)));
        DatabaseStackFrame second = frame(new DatabaseVariable(target, cursor("cur",
            "{name:\tportal_2\nis_open:\tt\nfound:\tt\nnot_found:\tf\nrow_count:\t3}")));
        assertEquals("0", watch("cur%ROWCOUNT", first).getValue().getValueString());
        assertEquals("3", watch("cur%ROWCOUNT", second).getValue().getValueString());
        assertEquals("0", watch("cur%ROWCOUNT", first).getValue().getValueString());
    }

    @Test
    void absentAttributeRemainsUnavailableInsteadOfBecomingZeroOrFalse() throws Exception {
        DatabaseVariable variable = new DatabaseVariable(mock(IDatabaseDebugTarget.class), cursor("cur", "portal_1"));
        assertEquals("portal_1", watch("cur", frame(variable)).getValue().getValueString());
        assertTrue(watch("cur%ROWCOUNT", frame(variable)).hasErrors());
        assertTrue(watch("cur%FOUND", frame(variable)).hasErrors());
    }

    @Test
    void ambiguousCursorAttributeWatchDoesNotSilentlyReadTheFirstPackageCursor() throws Exception {
        IDatabaseDebugTarget target = mock(IDatabaseDebugTarget.class);
        List<DBGVariable<?>> cursors = GaussDBCursorVariables.aggregate(List.of(
            new GaussDBDebugVariable("cur", "refcursor", "{row_count:\t1}", "pkg_one", false, 0),
            new GaussDBDebugVariable("cur", "refcursor", "{row_count:\t9}", "pkg_two", false, 0)));

        IWatchExpressionResult result = watch("cur%ROWCOUNT", frame(
            new DatabaseVariable(target, cursors.get(0)), new DatabaseVariable(target, cursors.get(1))));

        assertTrue(result.hasErrors());
        assertNull(result.getValue());
        assertTrue(java.util.Arrays.stream(result.getErrorMessages())
            .anyMatch(message -> message.toLowerCase(java.util.Locale.ROOT).contains("ambiguous")));
        verifyNoInteractions(target);
    }

    @Test
    void exactAttributeLikeVariableNameStillTakesPrecedenceOverAmbiguousCursorNames() throws Exception {
        IDatabaseDebugTarget target = mock(IDatabaseDebugTarget.class);
        DatabaseVariable first = new DatabaseVariable(target, cursor("cur", "{row_count:\t1}"));
        DatabaseVariable second = new DatabaseVariable(target, cursor("cur", "{row_count:\t9}"));
        DatabaseVariable exact = new DatabaseVariable(target,
            new GaussDBDebugVariable("cur%ROWCOUNT", "text", "literal attribute-like name", null, false, 0));

        IWatchExpressionResult result = watch("cur%ROWCOUNT", frame(first, second, exact));

        assertFalse(result.hasErrors());
        assertEquals("literal attribute-like name", result.getValue().getValueString());
    }
}
