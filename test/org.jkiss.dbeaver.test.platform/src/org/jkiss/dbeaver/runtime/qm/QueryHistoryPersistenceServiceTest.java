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
package org.jkiss.dbeaver.runtime.qm;

import org.jkiss.dbeaver.model.preferences.DBPPreferenceListener;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.qm.QMConstants;
import org.jkiss.dbeaver.model.qm.QMMetaEvent;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryHistoryPersistenceServiceTest {
    @TempDir
    Path directory;

    private DBPPreferenceStore preferences(boolean enabled) {
        var preferences = mock(DBPPreferenceStore.class);
        when(preferences.getBoolean(QMConstants.PROP_STORE_HISTORY)).thenReturn(enabled);
        when(preferences.getInt(QMConstants.PROP_HISTORY_DAYS)).thenReturn(30);
        return preferences;
    }

    private QMMetaEvent event(long time, String sql) {
        return QueryHistoryEventMapper.restore(new QueryHistoryStore.Entry(UUID.randomUUID(), "project", "connection",
            "连接", "gaussdb", sql, "USER", null, null, time, time + 1, 1, 0, null,
            -1, 0, 0, false, "Project", "SQL"));
    }

    @Test
    void recordingRequiresExplicitPreferenceAndDoesNotCaptureEarlierQueries() throws Exception {
        var preferences = preferences(false);
        var file = directory.resolve("history.json");
        var service = new QueryHistoryPersistenceService(preferences, file, e -> true);
        var earlier = event(1, "not consented");
        service.metaInfoChanged(new VoidProgressMonitor(), List.of(earlier));
        assertFalse(Files.exists(file));
        assertTrue(service.getEvents().isEmpty());
        when(preferences.getBoolean(QMConstants.PROP_STORE_HISTORY)).thenReturn(true);
        service.preferenceChange(new DBPPreferenceListener.PreferenceChangeEvent(preferences,
            QMConstants.PROP_STORE_HISTORY, false, true));
        var consented = event(System.currentTimeMillis() + 1, "SELECT 1");
        service.metaInfoChanged(new VoidProgressMonitor(), List.of(earlier, consented));
        assertEquals(List.of(consented), service.getEvents());
        service.dispose();
        verify(preferences).removePropertyChangeListener(service);
    }

    @Test
    void restartLoadsSavedHistoryAndDeletionIsDurable() throws Exception {
        var preferences = preferences(true);
        var file = directory.resolve("history.json");
        var service = new QueryHistoryPersistenceService(preferences, file, e -> true);
        service.metaInfoChanged(new VoidProgressMonitor(), List.of(event(System.currentTimeMillis() + 1, "SELECT '中文'")));
        service.dispose();
        var restarted = new QueryHistoryPersistenceService(preferences, file, e -> true);
        assertEquals("SELECT '中文'", restarted.getEvents().getFirst().getObject().getText());
        restarted.delete(restarted.getEvents());
        restarted.dispose();
        var finalRestart = new QueryHistoryPersistenceService(preferences, file, e -> true);
        assertTrue(finalRestart.getEvents().isEmpty());
        finalRestart.dispose();
    }

    @Test
    void writeFailureIsVisibleToBrowserAndCorruptFileIsPreserved() throws Exception {
        var preferences = preferences(true);
        var file = directory.resolve("history.json");
        Files.writeString(file, "{broken");
        var service = new QueryHistoryPersistenceService(preferences, file, e -> true);
        service.metaInfoChanged(new VoidProgressMonitor(), List.of(event(System.currentTimeMillis() + 1, "SELECT 1")));
        assertThrows(IOException.class, service::getEvents);
        assertEquals("{broken", Files.readString(file));
        service.dispose();
    }

    @Test
    void disablingRecordingDoesNotMakeExplicitDeletionTemporary() throws Exception {
        var preferences = preferences(true);
        var file = directory.resolve("history.json");
        var service = new QueryHistoryPersistenceService(preferences, file, e -> true);
        var recorded = event(System.currentTimeMillis() + 1, "SELECT 1");
        service.metaInfoChanged(new VoidProgressMonitor(), List.of(recorded));
        assertEquals(1, service.getEvents().size());
        when(preferences.getBoolean(QMConstants.PROP_STORE_HISTORY)).thenReturn(false);
        service.preferenceChange(new DBPPreferenceListener.PreferenceChangeEvent(preferences,
            QMConstants.PROP_STORE_HISTORY, true, false));
        service.delete(List.of(recorded));
        when(preferences.getBoolean(QMConstants.PROP_STORE_HISTORY)).thenReturn(true);
        service.preferenceChange(new DBPPreferenceListener.PreferenceChangeEvent(preferences,
            QMConstants.PROP_STORE_HISTORY, false, true));
        assertTrue(service.getEvents().isEmpty());
        assertTrue(new QueryHistoryStore(file, 2000).getEntries().isEmpty());
        service.dispose();
    }

    private QMRegistryImpl registry(QueryHistoryPersistenceService service, List<QMMetaEvent> pending) throws Exception {
        var collector = mock(QMMCollectorImpl.class, CALLS_REAL_METHODS);
        setField(collector, QMMCollectorImpl.class, "eventPool", new java.util.ArrayList<>(pending));
        setField(collector, QMMCollectorImpl.class, "pastEvents", new java.util.ArrayList<>());
        setField(collector, QMMCollectorImpl.class, "dispatchingEvents", List.of());
        setField(collector, QMMCollectorImpl.class, "historySync", new Object());
        setField(collector, QMMCollectorImpl.class, "listeners", new java.util.ArrayList<>(List.of(service)));
        // Do not start or wait for Eclipse jobs; snapshot and listener removal remain production code.
        doNothing().when(collector).dispose();
        var registry = mock(QMRegistryImpl.class, CALLS_REAL_METHODS);
        setField(registry, QMRegistryImpl.class, "metaHandler", collector);
        setField(registry, QMRegistryImpl.class, "historyPersistence", service);
        setField(registry, QMRegistryImpl.class, "handlers", new java.util.ArrayList<>(List.of(collector)));
        return registry;
    }

    private void setField(Object target, Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void registryShutdownPersistsCompletedEventBeforeNormalDispatch() throws Exception {
        var preferences = preferences(true);
        var file = directory.resolve("history.json");
        var service = new QueryHistoryPersistenceService(preferences, file, e -> true);
        var completed = event(System.currentTimeMillis() + 1, "SELECT '退出前完成'");
        var registry = registry(service, List.of(completed));
        assertFalse(Files.exists(file));
        registry.dispose();
        var entries = new QueryHistoryStore(file, 2000).getEntries();
        assertEquals(1, entries.size());
        assertEquals("SELECT '退出前完成'", entries.getFirst().sql());
        verify(preferences).removePropertyChangeListener(service);
        registry.dispose(); // repeated disposal must not rewrite or duplicate saved events
        assertEquals(entries, new QueryHistoryStore(file, 2000).getEntries());
    }

    @Test
    void registryShutdownDoesNotPersistActiveOrDeletedEventsOrLateCallbacks() throws Exception {
        var preferences = preferences(true);
        var file = directory.resolve("history.json");
        var service = new QueryHistoryPersistenceService(preferences, file, e -> true);
        var running = event(System.currentTimeMillis() + 1, "still running");
        running.getObject().setCloseTime(0);
        var deleted = event(System.currentTimeMillis() + 1, "removed");
        deleted.getObject().deleteFromHistory();
        registry(service, List.of(running, deleted)).dispose();
        assertFalse(Files.exists(file));
        running.getObject().setCloseTime(System.currentTimeMillis() + 2);
        service.metaInfoChanged(new VoidProgressMonitor(), List.of(running));
        assertFalse(Files.exists(file));
    }

    @Test
    void registryShutdownRespectsDisabledPersistence() throws Exception {
        var preferences = preferences(false);
        var file = directory.resolve("history.json");
        var service = new QueryHistoryPersistenceService(preferences, file, e -> true);
        registry(service, List.of(event(System.currentTimeMillis() + 1, "not enabled"))).dispose();
        assertFalse(Files.exists(file));
        verify(preferences).removePropertyChangeListener(service);
    }

    @Test
    void disposedServiceCannotBeReenabledByAlreadyQueuedPreferenceNotification() throws Exception {
        var preferences = preferences(false);
        var file = directory.resolve("history.json");
        var service = new QueryHistoryPersistenceService(preferences, file, e -> true);
        service.dispose();
        when(preferences.getBoolean(QMConstants.PROP_STORE_HISTORY)).thenReturn(true);
        service.preferenceChange(new DBPPreferenceListener.PreferenceChangeEvent(preferences,
            QMConstants.PROP_STORE_HISTORY, false, true));
        service.metaInfoChanged(new VoidProgressMonitor(),
            List.of(event(System.currentTimeMillis() + 1, "SELECT 'late after close'")));
        assertTrue(service.getEvents().isEmpty());
        assertFalse(Files.exists(file));
        service.dispose();
        verify(preferences, times(1)).removePropertyChangeListener(service);
    }
}
