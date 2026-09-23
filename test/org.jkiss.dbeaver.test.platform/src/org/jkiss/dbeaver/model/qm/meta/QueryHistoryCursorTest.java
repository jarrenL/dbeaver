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

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.qm.*;
import org.jkiss.dbeaver.model.qm.filters.QMCursorFilter;
import org.jkiss.dbeaver.model.qm.filters.QMEventCriteria;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.runtime.qm.QMMCollectorImpl;
import org.jkiss.dbeaver.runtime.qm.QMRegistryImpl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryHistoryCursorTest {
    private final VoidProgressMonitor monitor = new VoidProgressMonitor();

    private QMMetaEvent event(String text, DBCExecutionPurpose purpose) {
        var statement = mock(QMMStatementInfo.class);
        when(statement.getPurpose()).thenReturn(purpose);
        var execution = mock(QMMStatementExecuteInfo.class);
        when(execution.getStatement()).thenReturn(statement);
        when(execution.getText()).thenReturn(text);
        return new QMMetaEvent(execution, QMEventAction.END, 0, "fixture");
    }

    @Test
    void scrollingForwardAndBackwardChangesNextEvent() throws Exception {
        var first = event("first", DBCExecutionPurpose.USER);
        var second = event("second", DBCExecutionPurpose.USER);
        var third = event("third", DBCExecutionPurpose.USER);
        try (var cursor = new QMUtils.ListCursorImpl(List.of(first, second, third))) {
            assertEquals(3, cursor.getTotalSize());
            cursor.scroll(2, monitor);
            assertSame(third.getObject(), cursor.nextEvent(monitor).getObject());
            assertFalse(cursor.hasNextEvent(monitor));
            cursor.scroll(0, monitor);
            assertSame(first.getObject(), cursor.nextEvent(monitor).getObject());
            assertSame(second.getObject(), cursor.nextEvent(monitor).getObject());
        }
    }

    @Test
    void invalidScrollDoesNotChangeCurrentPosition() throws Exception {
        var first = event("first", DBCExecutionPurpose.USER);
        var second = event("second", DBCExecutionPurpose.USER);
        try (var cursor = new QMUtils.ListCursorImpl(List.of(first, second))) {
            cursor.nextEvent(monitor);
            assertThrows(DBException.class, () -> cursor.scroll(-1, monitor));
            assertThrows(DBException.class, () -> cursor.scroll(2, monitor));
            assertSame(second.getObject(), cursor.nextEvent(monitor).getObject());
        }
    }

    @Test
    void emptyCursorHasNoEventsAndRejectsPositionZero() throws Exception {
        try (var cursor = new QMUtils.ListCursorImpl(List.of())) {
            assertEquals(0, cursor.getTotalSize());
            assertFalse(cursor.hasNextEvent(monitor));
            assertThrows(DBException.class, () -> cursor.scroll(0, monitor));
        }
    }

    private QMEventBrowser browser(List<QMMetaEvent> events) throws Exception {
        var registry = mock(QMRegistryImpl.class);
        var collector = mock(QMMCollectorImpl.class);
        when(collector.getPastEvents()).thenAnswer(invocation -> new ArrayList<>(events));
        var field = QMRegistryImpl.class.getDeclaredField("metaHandler");
        field.setAccessible(true);
        field.set(registry, collector);
        var type = Class.forName(QMRegistryImpl.class.getName() + "$DefaultEventBrowser", true,
            QMRegistryImpl.class.getClassLoader());
        var constructor = type.getDeclaredConstructor(QMRegistryImpl.class);
        constructor.setAccessible(true);
        return (QMEventBrowser) constructor.newInstance(registry);
    }

    @Test
    void browserCombinesPurposeTextAndCustomFilterInNewestFirstOrder() throws Exception {
        var first = event("SELECT 中文 first", DBCExecutionPurpose.USER);
        var metadata = event("SELECT 中文 metadata", DBCExecutionPurpose.META);
        var second = event("select 中文 second", DBCExecutionPurpose.USER);
        var excluded = event("SELECT 中文 excluded", DBCExecutionPurpose.USER);
        var events = List.of(first, metadata, second, excluded);
        var criteria = new QMEventCriteria();
        criteria.setObjectTypes(new QMObjectType[] {QMObjectType.query});
        criteria.setQueryTypes(new DBCExecutionPurpose[] {DBCExecutionPurpose.USER});
        criteria.setSearchString("SeLeCt 中文");
        try (var cursor = browser(events).getQueryHistoryCursor(new QMCursorFilter(null, criteria,
            event -> event.getObject() != excluded.getObject()))) {
            assertEquals(2, cursor.getTotalSize());
            assertSame(second.getObject(), cursor.nextEvent(monitor).getObject());
            assertSame(first.getObject(), cursor.nextEvent(monitor).getObject());
            assertFalse(cursor.hasNextEvent(monitor));
        }
        assertSame(first, events.get(0));
    }

    @Test
    void unmatchedTextReturnsEmptyHistory() throws Exception {
        var criteria = new QMEventCriteria();
        criteria.setObjectTypes(new QMObjectType[] {QMObjectType.query});
        criteria.setQueryTypes(new DBCExecutionPurpose[] {DBCExecutionPurpose.USER});
        criteria.setSearchString("absent text");
        try (var cursor = browser(List.of(event("SELECT 1", DBCExecutionPurpose.USER)))
            .getQueryHistoryCursor(new QMCursorFilter(null, criteria, null))) {
            assertEquals(0, cursor.getTotalSize());
            assertFalse(cursor.hasNextEvent(monitor));
        }
    }
}
