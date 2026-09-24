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

    @Test
    void standaloneCustomFilterCannotBeBypassedByAbsentTypeAndTextCriteria() throws Exception {
        var visible = event("visible", DBCExecutionPurpose.USER);
        var hidden = event("hidden", DBCExecutionPurpose.USER);
        var criteria = new QMEventCriteria();
        criteria.setObjectTypes(null);
        criteria.setQueryTypes(null);
        try (var cursor = browser(List.of(visible, hidden)).getQueryHistoryCursor(
            new QMCursorFilter(null, criteria, candidate -> candidate.getObject() == visible.getObject()))) {
            assertEquals(1, cursor.getTotalSize());
            assertSame(visible.getObject(), cursor.nextEvent(monitor).getObject());
        }
    }

    @Test
    void combinedCriteriaEvaluateCustomFilterOncePerCandidate() throws Exception {
        var selected = event("SELECT 中文", DBCExecutionPurpose.USER);
        var criteria = new QMEventCriteria();
        criteria.setObjectTypes(new QMObjectType[] {QMObjectType.query});
        criteria.setQueryTypes(new DBCExecutionPurpose[] {DBCExecutionPurpose.USER});
        criteria.setSearchString("中文");
        var filter = mock(QMEventFilter.class);
        when(filter.accept(selected)).thenReturn(true);
        try (var cursor = browser(List.of(selected)).getQueryHistoryCursor(new QMCursorFilter(null, criteria, filter))) {
            assertEquals(1, cursor.getTotalSize());
        }
        verify(filter, times(1)).accept(selected);
    }

    @Test
    void textSearchDoesNotDependOnDefaultLocale() throws Exception {
        var previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            var criteria = new QMEventCriteria();
            criteria.setObjectTypes(new QMObjectType[] {QMObjectType.query});
            criteria.setQueryTypes(new DBCExecutionPurpose[] {DBCExecutionPurpose.USER});
            criteria.setSearchString("INSERT");
            try (var cursor = browser(List.of(event("insert into t values (1)", DBCExecutionPurpose.USER)))
                .getQueryHistoryCursor(new QMCursorFilter(null, criteria, null))) {
                assertEquals(1, cursor.getTotalSize());
            }
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    private QMMetaEvent event(String text, DBCExecutionPurpose purpose) {
        var statement = mock(QMMStatementInfo.class);
        when(statement.getPurpose()).thenReturn(purpose);
        var execution = mock(QMMStatementExecuteInfo.class);
        when(execution.getStatement()).thenReturn(statement);
        when(execution.getText()).thenReturn(text);
        return new QMMetaEvent(execution, QMEventAction.END, 0, "fixture");
    }

    @Test
    void skipEmptyQueriesExcludesNullEmptyAndWhitespaceWithoutChangingRealSql() throws Exception {
        var sql = event(" SELECT '  ' -- 保留字符串空白", DBCExecutionPurpose.USER);
        var criteria = new QMEventCriteria();
        criteria.setObjectTypes(null);
        criteria.setQueryTypes(null);
        criteria.setSkipEmptyQueries(true);
        var events = List.of(event(null, DBCExecutionPurpose.USER), event("", DBCExecutionPurpose.USER),
            event(" \t\r\n", DBCExecutionPurpose.USER), sql);
        try (var cursor = browser(events).getQueryHistoryCursor(new QMCursorFilter(null, criteria, null))) {
            assertEquals(1, cursor.getTotalSize());
            assertSame(sql.getObject(), cursor.nextEvent(monitor).getObject());
            assertEquals(" SELECT '  ' -- 保留字符串空白", sql.getObject().getText());
        }
    }

    @Test
    void emptyQueryFilterIsOptInAndDoesNotRemoveConnectionEvents() throws Exception {
        var empty = event("", DBCExecutionPurpose.USER);
        var connection = new QMMetaEvent(mock(QMMConnectionInfo.class), QMEventAction.END, 0, "fixture");
        var criteria = new QMEventCriteria();
        criteria.setObjectTypes(null);
        criteria.setQueryTypes(null);
        var browser = browser(List.of(empty, connection));
        try (var cursor = browser.getQueryHistoryCursor(new QMCursorFilter(null, criteria, null))) {
            assertEquals(2, cursor.getTotalSize());
        }
        criteria.setSkipEmptyQueries(true);
        try (var cursor = browser.getQueryHistoryCursor(new QMCursorFilter(null, criteria, null))) {
            assertEquals(1, cursor.getTotalSize());
            assertSame(connection.getObject(), cursor.nextEvent(monitor).getObject());
        }
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
        var collector = mock(QMMCollectorImpl.class);
        when(collector.getPastEvents()).thenAnswer(invocation -> new ArrayList<>(events));
        return browser(collector);
    }

    @Test
    void browserRoutesOnlySelectedHistoryObjectsToCollector() throws Exception {
        var collector = mock(QMMCollectorImpl.class);
        var first = event("selected", DBCExecutionPurpose.USER);
        var second = event("also selected", DBCExecutionPurpose.USER);
        browser(collector).deleteHistoryEvents(List.of(first, second));
        verify(collector).deleteHistoryObjects(List.of(first.getObject(), second.getObject()));
        verifyNoMoreInteractions(collector);
    }

    @Test
    void unsupportedProviderRejectsDeletionInsteadOfPretendingSuccess() {
        var browser = mock(QMEventBrowser.class, CALLS_REAL_METHODS);
        assertThrows(DBException.class, () -> browser.deleteHistoryEvents(List.of()));
    }

    private QMEventBrowser browser(QMMCollectorImpl collector) throws Exception {
        var registry = mock(QMRegistryImpl.class);
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
