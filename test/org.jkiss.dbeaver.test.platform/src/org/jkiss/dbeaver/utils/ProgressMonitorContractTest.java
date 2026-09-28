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
package org.jkiss.dbeaver.utils;

import org.eclipse.core.runtime.IProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRBlockingObject;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.model.runtime.SubTaskProgressMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProgressMonitorContractTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void completingNestedTaskRestoresParentNotCompletedTask(int depth) {
        var nested = mock(IProgressMonitor.class);
        var monitor = new DefaultProgressMonitor(nested);
        var order = inOrder(nested);
        for (int i = 0; i < depth; i++) {
            monitor.beginTask("任务" + i, 100 + i);
            monitor.subTask("子步骤" + i);
            monitor.worked(i + 1);
            order.verify(nested).beginTask("任务" + i, 100 + i);
            order.verify(nested).subTask("子步骤" + i);
            order.verify(nested).worked(i + 1);
        }
        for (int i = depth - 1; i >= 0; i--) {
            monitor.done();
            order.verify(nested).done();
            if (i > 0) {
                order.verify(nested).beginTask("任务" + (i - 1), 100 + i - 1);
                order.verify(nested).subTask("子步骤" + (i - 1));
                order.verify(nested).worked(i);
            }
        }
        order.verifyNoMoreInteractions();
    }

    @Test
    void blockingStackReturnsSnapshotsAndUnwindsLastFirst() {
        var monitor = new DefaultProgressMonitor(mock(IProgressMonitor.class));
        var outer = mock(DBRBlockingObject.class);
        var inner = mock(DBRBlockingObject.class);
        assertNull(monitor.getActiveBlocks());
        monitor.startBlock(outer, null);
        monitor.startBlock(inner, null);
        var snapshot = monitor.getActiveBlocks();
        assertEquals(java.util.List.of(outer, inner), snapshot);
        snapshot.clear();
        assertEquals(java.util.List.of(outer, inner), monitor.getActiveBlocks());
        monitor.endBlock();
        assertEquals(java.util.List.of(outer), monitor.getActiveBlocks());
        monitor.endBlock();
        assertNull(monitor.getActiveBlocks());
    }

    @Test
    void subtaskCompletionDoesNotFinishParentAndObservesCancellation() {
        var nested = mock(IProgressMonitor.class);
        var parent = new DefaultProgressMonitor(nested);
        var sub = new SubTaskProgressMonitor(parent);
        parent.beginTask("parent", 10);
        sub.beginTask("child", 3);
        sub.worked(1);
        sub.done();
        verify(nested, never()).done();
        verify(nested).subTask("child");
        verify(nested).worked(1);
        when(nested.isCanceled()).thenReturn(true);
        assertTrue(sub.isCanceled());
        assertSame(nested, sub.getNestedMonitor());
        parent.done();
        verify(nested).done();
    }
}
