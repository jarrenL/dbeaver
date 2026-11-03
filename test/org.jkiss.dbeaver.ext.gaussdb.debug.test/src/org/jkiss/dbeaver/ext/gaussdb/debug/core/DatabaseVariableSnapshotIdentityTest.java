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
import org.jkiss.dbeaver.debug.DBGVariable;
import org.jkiss.dbeaver.debug.DBGVariableType;
import org.jkiss.dbeaver.debug.core.model.DatabaseValue;
import org.jkiss.dbeaver.debug.core.model.DatabaseVariable;
import org.jkiss.dbeaver.debug.core.model.IDatabaseDebugTarget;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBCursorVariables;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugVariable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies model identity contracts; real Eclipse viewer rendering is covered by GUI acceptance. */
class DatabaseVariableSnapshotIdentityTest {
    private static DBGVariable<?> cursor() {
        return GaussDBCursorVariables.aggregate(List.of(new GaussDBDebugVariable(
            "cur", "refcursor", "{name:\tportal_1\nis_open:\tt\nfound:\tf\nnot_found:\tf\nrow_count:\t0}",
            null, false, 0))).getFirst();
    }

    @Test
    void repeatedIndexedReadsReturnTheSameChildrenAsTheFullSnapshot() throws Exception {
        IDatabaseDebugTarget target = mock(IDatabaseDebugTarget.class);
        DatabaseVariable cursor = new DatabaseVariable(target, cursor());

        IVariable[] whole = cursor.getValue().getVariables();
        assertEquals(5, whole.length);
        for (int index = 0; index < whole.length; index++) {
            IVariable child = cursor.getValue().getVariables()[index];
            assertSame(whole[index], child, "Separate reads of a snapshot must retain child identity");
            assertSame(whole[index].getValue(), child.getValue());
        }
        assertSame(cursor.getValue(), cursor.getValue(), "An IVariable owns one value snapshot");
        verifyNoInteractions(target);
    }

    @Test
    void callerArrayMutationCannotRemoveCachedChildren() throws Exception {
        DatabaseVariable cursor = new DatabaseVariable(mock(IDatabaseDebugTarget.class), cursor());
        IVariable[] first = cursor.getValue().getVariables();
        IVariable initial = first[0];
        first[0] = null;

        IVariable[] second = cursor.getValue().getVariables();
        assertNotSame(first, second);
        assertEquals(5, second.length);
        assertSame(initial, second[0]);
        assertNotNull(second[4]);
    }

    @Test
    void concurrentViewerRequestsShareChildWrappers() throws Exception {
        DatabaseValue value = new DatabaseValue(mock(IDatabaseDebugTarget.class), cursor());
        try (var workers = Executors.newFixedThreadPool(4)) {
            List<Callable<IVariable[]>> reads = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                reads.add(value::getVariables);
            }
            List<java.util.concurrent.Future<IVariable[]>> results = workers.invokeAll(reads);
            IVariable[] expected = results.getFirst().get();
            for (var result : results) {
                IVariable[] actual = result.get();
                assertEquals(5, actual.length);
                for (int index = 0; index < expected.length; index++) {
                    assertSame(expected[index], actual[index]);
                }
            }
        }
    }

    @Test
    void valueCapturesChildListOnceAndDoesNotAdoptLaterListMutation() throws Exception {
        List<DBGVariable<?>> children = new ArrayList<>();
        children.add(new GaussDBDebugVariable("first", "integer", "1", null, true, 0));
        DBGVariable<String> source = new DBGVariable<>() {
            @Override public String getVal() { return "snapshot"; }
            @Override public String getName() { return "parent"; }
            @Override public DBGVariableType getType() { return DBGVariableType.TEXT; }
            @Override public int getLineNumber() { return 0; }
            @Override public List<? extends DBGVariable<?>> getChildren() { return children; }
        };
        IValue value = new DatabaseVariable(mock(IDatabaseDebugTarget.class), source).getValue();
        children.clear();

        assertTrue(value.hasVariables());
        assertEquals(1, value.getVariables().length);
        assertEquals("first", value.getVariables()[0].getName());
    }

    @Test
    void stableScalarValueStillReflectsAnAcceptedVariableEdit() throws Exception {
        GaussDBDebugVariable scalar = new GaussDBDebugVariable("amount", "integer", "1", null, false, 0);
        DatabaseVariable variable = new DatabaseVariable(mock(IDatabaseDebugTarget.class), scalar);
        IValue value = variable.getValue();
        scalar.setValue("2");

        assertSame(value, variable.getValue());
        assertEquals("2", value.getValueString());
        assertFalse(value.hasVariables());
        assertEquals(0, value.getVariables().length);
    }

    @Test
    void newSuspendedFrameGetsIndependentWrappers() throws Exception {
        IDatabaseDebugTarget target = mock(IDatabaseDebugTarget.class);
        DBGVariable<?> cursor = cursor();
        DatabaseVariable firstFrame = new DatabaseVariable(target, cursor);
        DatabaseVariable nextFrame = new DatabaseVariable(target, cursor);

        assertNotSame(firstFrame.getValue(), nextFrame.getValue());
        assertNotSame(firstFrame.getValue().getVariables()[0], nextFrame.getValue().getVariables()[0]);
    }

}
