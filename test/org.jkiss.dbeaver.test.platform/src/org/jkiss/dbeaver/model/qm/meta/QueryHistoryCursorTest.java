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
import org.jkiss.dbeaver.model.qm.filters.QMDateRange;
import org.jkiss.dbeaver.model.qm.filters.QMEventStatus;
import org.jkiss.dbeaver.model.qm.filters.QMSortField;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.runtime.qm.QMMCollectorImpl;
import org.jkiss.dbeaver.runtime.qm.QMRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryHistoryCursorTest {
    private final VoidProgressMonitor monitor = new VoidProgressMonitor();

    @Test
    void statusSelectionExcludesOtherOutcomesAndUnfinishedExecutions() throws Exception {
        var success = event("ok", DBCExecutionPurpose.USER);
        var failure = event("failed", DBCExecutionPurpose.USER);
        var running = event("running", DBCExecutionPurpose.USER);
        when(success.getObject().isClosed()).thenReturn(true);
        when(failure.getObject().isClosed()).thenReturn(true);
        when(((QMMStatementExecuteInfo) failure.getObject()).hasError()).thenReturn(true);
        var connection = new QMMetaEvent(mock(QMMConnectionInfo.class), QMEventAction.END, 0, "fixture");
        var criteria = unrestrictedCriteria();
        for (var status : QMEventStatus.values()) {
            criteria.setEventStatuses(java.util.Set.of(status));
            try (var cursor = browser(List.of(success, failure, running, connection)).getQueryHistoryCursor(
                new QMCursorFilter(null, criteria, null))) {
                assertEquals(1, cursor.getTotalSize());
                assertSame((status == QMEventStatus.SUCCESS ? success : failure).getObject(),
                    cursor.nextEvent(monitor).getObject());
            }
        }
    }

    @Test
    void dateRangeUsesExecutionStartUtcAndIncludesBothBounds() throws Exception {
        var events = new ArrayList<QMMetaEvent>();
        for (long time : new long[] {999, 1000, 2000, 3000, 3001}) {
            var event = event(Long.toString(time), DBCExecutionPurpose.USER);
            when(event.getObject().getOpenTime()).thenReturn(time);
            events.add(event);
        }
        var criteria = unrestrictedCriteria();
        var from = java.time.Instant.ofEpochMilli(1000).atZone(java.time.ZoneId.of("Asia/Shanghai"));
        var to = java.time.Instant.ofEpochMilli(3000).atZone(java.time.ZoneId.of("America/New_York"));
        criteria.setDateRange(new QMDateRange(from, to));
        try (var cursor = browser(events).getQueryHistoryCursor(new QMCursorFilter(null, criteria, null))) {
            assertEquals(3, cursor.getTotalSize());
            for (int index : new int[] {3, 2, 1}) {
                assertSame(events.get(index).getObject(), cursor.nextEvent(monitor).getObject());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(QMSortField.class)
    void requestedSortFieldHonorsBothDirections(QMSortField field) throws Exception {
        var first = event("a-query", DBCExecutionPurpose.USER);
        var second = event("z-query", DBCExecutionPurpose.USER);
        when(first.getObject().getOpenTime()).thenReturn(100L);
        when(second.getObject().getOpenTime()).thenReturn(200L);
        for (var event : List.of(first, second)) {
            var connection = mock(QMMConnectionInfo.class);
            when(event.getObject().getConnection()).thenReturn(connection);
            when(connection.getConnectionUserName()).thenReturn(event == first ? "a-user" : "z-user");
            when(connection.getDriverId()).thenReturn(event == first ? "a-driver" : "z-driver");
        }
        var criteria = unrestrictedCriteria();
        criteria.setSortField(field);
        for (boolean desc : new boolean[] {false, true}) {
            criteria.setDesc(desc);
            try (var cursor = browser(List.of(first, second)).getQueryHistoryCursor(
                new QMCursorFilter(null, criteria, null))) {
                assertSame((desc ? second : first).getObject(), cursor.nextEvent(monitor).getObject());
                assertSame((desc ? first : second).getObject(), cursor.nextEvent(monitor).getObject());
            }
        }
    }

    @Test
    void openEndedAndReversedDateRangesHavePredictableResults() throws Exception {
        var events = new ArrayList<QMMetaEvent>();
        for (long time : new long[] {1000, 2000, 3000}) {
            var event = event("sql", DBCExecutionPurpose.USER);
            when(event.getObject().getOpenTime()).thenReturn(time);
            events.add(event);
        }
        var lower = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(1000), java.time.ZoneOffset.UTC);
        var middle = lower.plusSeconds(1);
        var upper = lower.plusSeconds(2);
        var ranges = List.of(new QMDateRange(null, middle), new QMDateRange(middle, null),
            new QMDateRange(upper, lower), new QMDateRange((java.time.LocalDateTime) null, null));
        int[] expected = {2, 2, 0, 3};
        var criteria = unrestrictedCriteria();
        for (int i = 0; i < ranges.size(); i++) {
            criteria.setDateRange(ranges.get(i));
            try (var cursor = browser(events).getQueryHistoryCursor(new QMCursorFilter(null, criteria, null))) {
                assertEquals(expected[i], cursor.getTotalSize());
            }
        }
    }

    @Test
    void sortingMissingMetadataDoesNotMutateCollectorSnapshot() throws Exception {
        var known = event("query", DBCExecutionPurpose.USER);
        var missing = event(null, DBCExecutionPurpose.USER);
        var connection = mock(QMMConnectionInfo.class);
        when(known.getObject().getConnection()).thenReturn(connection);
        when(known.getObject().getOpenTime()).thenReturn(100L);
        when(connection.getConnectionUserName()).thenReturn("user");
        when(connection.getDriverId()).thenReturn("driver");
        var snapshot = List.of(known, missing);
        var collector = mock(QMMCollectorImpl.class);
        when(collector.getPastEvents()).thenReturn(snapshot);
        var criteria = unrestrictedCriteria();
        criteria.setDesc(false);
        for (var field : QMSortField.values()) {
            criteria.setSortField(field);
            try (var cursor = browser(collector).getQueryHistoryCursor(new QMCursorFilter(null, criteria, null))) {
                assertSame(missing.getObject(), cursor.nextEvent(monitor).getObject());
                assertSame(known.getObject(), cursor.nextEvent(monitor).getObject());
            }
            assertEquals(List.of(known, missing), snapshot);
        }
    }

    private QMEventCriteria unrestrictedCriteria() {
        var criteria = new QMEventCriteria();
        criteria.setObjectTypes(null);
        criteria.setQueryTypes(null);
        return criteria;
    }

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
        return browser(collector, null, false);
    }

    private QMEventBrowser browser(QMMCollectorImpl collector,
        org.jkiss.dbeaver.runtime.qm.QueryHistoryPersistenceService persistence, boolean includeHistory) throws Exception {
        var registry = mock(QMRegistryImpl.class);
        var field = QMRegistryImpl.class.getDeclaredField("metaHandler");
        field.setAccessible(true);
        field.set(registry, collector);
        var persistenceField = QMRegistryImpl.class.getDeclaredField("historyPersistence");
        persistenceField.setAccessible(true);
        persistenceField.set(registry, persistence);
        var type = Class.forName(QMRegistryImpl.class.getName() + "$DefaultEventBrowser", true,
            QMRegistryImpl.class.getClassLoader());
        var constructor = type.getDeclaredConstructor(QMRegistryImpl.class, boolean.class);
        constructor.setAccessible(true);
        return (QMEventBrowser) constructor.newInstance(registry, includeHistory);
    }

    @Test
    void persistentBrowserMergesWithoutDuplicatingCurrentObjectsAndCurrentOnlyStaysIsolated() throws Exception {
        var current = event("current", DBCExecutionPurpose.USER);
        var old = event("old", DBCExecutionPurpose.USER);
        when(current.getObject().getOpenTime()).thenReturn(200L);
        when(old.getObject().getOpenTime()).thenReturn(100L);
        var collector = mock(QMMCollectorImpl.class);
        when(collector.getPastEvents()).thenAnswer(invocation -> new ArrayList<>(List.of(current)));
        var persistence = mock(org.jkiss.dbeaver.runtime.qm.QueryHistoryPersistenceService.class);
        when(persistence.getEvents()).thenReturn(List.of(old, current));
        var criteria = new QMEventCriteria();
        criteria.setObjectTypes(null);
        criteria.setQueryTypes(null);
        var filter = new QMCursorFilter(null, criteria, null);
        try (var cursor = browser(collector, persistence, false).getQueryHistoryCursor(filter)) {
            assertEquals(1, cursor.getTotalSize());
        }
        verifyNoInteractions(persistence);
        try (var cursor = browser(collector, persistence, true).getQueryHistoryCursor(filter)) {
            assertEquals(2, cursor.getTotalSize());
            assertSame(current.getObject(), cursor.nextEvent(monitor).getObject());
            assertSame(old.getObject(), cursor.nextEvent(monitor).getObject());
        }
    }

    @Test
    void persistentReadFailureIsNotMisrepresentedAsEmptyHistory() throws Exception {
        var collector = mock(QMMCollectorImpl.class);
        when(collector.getPastEvents()).thenReturn(new ArrayList<>());
        var persistence = mock(org.jkiss.dbeaver.runtime.qm.QueryHistoryPersistenceService.class);
        when(persistence.getEvents()).thenThrow(new java.io.IOException("fixture"));
        assertThrows(DBException.class, () -> browser(collector, persistence, true).getQueryHistoryCursor(
            new QMCursorFilter(null, new QMEventCriteria(), null)));
    }

    @Test
    void failedPersistentDeletionDoesNotDeleteCurrentMemoryHistory() throws Exception {
        var collector = mock(QMMCollectorImpl.class);
        var persistence = mock(org.jkiss.dbeaver.runtime.qm.QueryHistoryPersistenceService.class);
        var events = List.of(event("retained", DBCExecutionPurpose.USER));
        doThrow(new java.io.IOException("fixture")).when(persistence).delete(events);
        assertThrows(DBException.class, () -> browser(collector, persistence, false).deleteHistoryEvents(events));
        verifyNoInteractions(collector);
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
