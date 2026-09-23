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
package org.jkiss.dbeaver.model.qm.meta;

import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.qm.QMMetaEvent;
import org.jkiss.dbeaver.model.qm.QMEventAction;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.runtime.qm.QMMCollectorImpl;
import org.jkiss.junit.DBeaverUnitTest;
import org.jkiss.utils.LongKeyMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryHistoryCollectorTest extends DBeaverUnitTest {
    private final List<QMMetaEvent> pending = new ArrayList<>();
    private final List<Long> closed = new ArrayList<>();
    private final LongKeyMap<QMMConnectionInfo> connections = new LongKeyMap<>();

    private QMMCollectorImpl collector() throws Exception {
        var collector = mock(QMMCollectorImpl.class, CALLS_REAL_METHODS);
        field(collector, "eventPool", pending);
        field(collector, "closedConnections", closed);
        field(collector, "connectionMap", connections);
        field(collector, "listeners", new ArrayList<>());
        field(collector, "historySync", new Object());
        field(collector, "pastEvents", new ArrayList<>());
        field(collector, "running", false); // Run one production dispatch cycle, never schedule a background job.
        return collector;
    }

    private void field(QMMCollectorImpl collector, String name, Object value) throws Exception {
        var field = QMMCollectorImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(collector, value);
    }

    private void dispatch(QMMCollectorImpl collector) throws Exception {
        var type = Class.forName(QMMCollectorImpl.class.getName() + "$EventDispatcher", true,
            QMMCollectorImpl.class.getClassLoader());
        var constructor = type.getDeclaredConstructor(QMMCollectorImpl.class);
        constructor.setAccessible(true);
        var dispatcher = constructor.newInstance(collector);
        var method = type.getDeclaredMethod("run", DBRProgressMonitor.class);
        method.setAccessible(true);
        method.invoke(dispatcher, new VoidProgressMonitor());
    }

    @Test
    void retainsNewestTenThousandEventsAndReturnsDefensiveSnapshot() throws Exception {
        var collector = collector();
        var object = mock(QMMStatementExecuteInfo.class);
        for (int i = 0; i < 10005; i++) {
            pending.add(new QMMetaEvent(object, QMEventAction.END, i, "fixture"));
        }
        dispatch(collector);
        assertTrue(pending.isEmpty());
        var snapshot = collector.getPastEvents();
        assertEquals(10000, snapshot.size());
        assertEquals(5, snapshot.getFirst().getTimestamp());
        assertEquals(10004, snapshot.getLast().getTimestamp());
        snapshot.clear();
        assertEquals(10000, collector.getPastEvents().size());
        pending.add(new QMMetaEvent(object, QMEventAction.END, 10005, "fixture"));
        dispatch(collector);
        assertEquals(6, collector.getPastEvents().getFirst().getTimestamp());
        assertEquals(10005, collector.getPastEvents().getLast().getTimestamp());
    }

    @Test
    void removesClosedConnectionButRetainsReopenedConnectionDuringCleanup() throws Exception {
        var collector = collector();
        var finished = mock(QMMConnectionInfo.class);
        when(finished.isClosed()).thenReturn(true);
        var reopened = mock(QMMConnectionInfo.class);
        when(reopened.isClosed()).thenReturn(false);
        connections.put(11L, finished);
        connections.put(12L, reopened);
        closed.addAll(List.of(11L, 12L));
        dispatch(collector);
        var context = mock(DBCExecutionContext.class);
        when(context.getContextId()).thenReturn(11L);
        assertNull(collector.getConnectionInfo(context));
        when(context.getContextId()).thenReturn(12L);
        assertSame(reopened, collector.getConnectionInfo(context));
        assertTrue(closed.isEmpty());
    }

    @Test
    void failingListenerDoesNotPreventOtherListenersOrHistoryRetention() throws Exception {
        var collector = collector();
        var failing = mock(org.jkiss.dbeaver.model.qm.QMMetaListener.class);
        var healthy = mock(org.jkiss.dbeaver.model.qm.QMMetaListener.class);
        doThrow(new IllegalStateException("fixture listener failure")).when(failing).metaInfoChanged(any(), any());
        collector.addListener(failing);
        collector.addListener(healthy);
        var event = new QMMetaEvent(mock(QMMStatementExecuteInfo.class), QMEventAction.END, 1234, "fixture");
        pending.add(event);
        dispatch(collector);
        verify(failing).metaInfoChanged(any(), eq(List.of(event)));
        verify(healthy).metaInfoChanged(any(), eq(List.of(event)));
        assertEquals(List.of(event), collector.getPastEvents());
        assertTrue(pending.isEmpty());
        dispatch(collector);
        verifyNoMoreInteractions(failing, healthy);
    }

    @Test
    void removingListenerStopsFurtherDeliveryWithoutDroppingHistory() throws Exception {
        var collector = collector();
        var listener = mock(org.jkiss.dbeaver.model.qm.QMMetaListener.class);
        collector.addListener(listener);
        var first = new QMMetaEvent(mock(QMMStatementExecuteInfo.class), QMEventAction.END, 1, "fixture");
        pending.add(first);
        dispatch(collector);
        verify(listener).metaInfoChanged(any(), eq(List.of(first)));
        collector.removeListener(listener);
        var second = new QMMetaEvent(mock(QMMStatementExecuteInfo.class), QMEventAction.END, 2, "fixture");
        pending.add(second);
        dispatch(collector);
        verifyNoMoreInteractions(listener);
        assertEquals(List.of(first, second), collector.getPastEvents());
    }
}
