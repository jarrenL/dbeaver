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

import org.jkiss.dbeaver.model.qm.QMMetaEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PersistentQueryHistoryTest {
    @TempDir
    Path directory;

    private QMMetaEvent event(long time, String sql) {
        return QueryHistoryEventMapper.restore(new QueryHistoryStore.Entry(UUID.randomUUID(), "project", "connection",
            "连接", "gaussdb", sql, "USER", null, null, time, time + 1, 1, 0, null,
            -1, 0, 0, false, "Project", "SQL"));
    }

    private PersistentQueryHistory reopen() throws IOException {
        return new PersistentQueryHistory(new QueryHistoryStore(directory.resolve("history.json"), 2));
    }

    @Test
    void repeatedEventsUpdateOneRecordAndReopenPreservesText() throws Exception {
        var history = reopen();
        var event = event(100, "SELECT '中文'");
        history.record(true, candidate -> true, List.of(event, event));
        assertEquals(1, history.getEvents().size());
        assertSame(event, history.getEvents().getFirst());
        var restarted = reopen();
        assertEquals(1, restarted.getEvents().size());
        assertEquals(event.getObject().getText(), restarted.getEvents().getFirst().getObject().getText());
    }

    @Test
    void disabledRecordingAndRejectedEventsNeverCreateSnapshot() throws Exception {
        var history = reopen();
        var event = event(100, "not recorded");
        history.record(false, candidate -> { fail("Disabled recording must not inspect SQL"); return true; }, List.of(event));
        history.record(true, candidate -> false, List.of(event));
        assertFalse(Files.exists(directory.resolve("history.json")));
        assertTrue(history.getEvents().isEmpty());
    }

    @Test
    void deletingLiveEventPreventsDelayedCallbackResurrection() throws Exception {
        var history = reopen();
        var event = event(100, "SELECT 1");
        history.record(true, candidate -> true, List.of(event));
        history.delete(List.of(event));
        history.record(true, candidate -> true, List.of(event));
        assertTrue(history.getEvents().isEmpty());
        assertTrue(reopen().getEvents().isEmpty());
        assertTrue(event.getObject().isHistoryDeleted());
    }

    @Test
    void deletingRestoredEventPersistsAndPreservesOtherRecord() throws Exception {
        var history = reopen();
        history.record(true, candidate -> true, List.of(event(100, "remove"), event(200, "retain")));
        var restarted = reopen();
        restarted.delete(List.of(restarted.getEvents().getFirst()));
        assertEquals(1, reopen().getEvents().size());
        assertEquals("retain", reopen().getEvents().getFirst().getObject().getText());
    }

    @Test
    void failedDeletionDoesNotHideRecordsOrMarkThemDeleted() throws Exception {
        var history = reopen();
        var event = event(100, "retained");
        history.record(true, candidate -> true, List.of(event));
        var file = directory.resolve("history.json");
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(IOException.class, () -> history.delete(List.of(event)));
        assertEquals(List.of(event), history.getEvents());
        assertFalse(event.getObject().isHistoryDeleted());
    }

    @Test
    void capacityAndRetentionRemoveCorrespondingVisibleMetadata() throws Exception {
        var history = reopen();
        history.record(true, candidate -> true, List.of(event(100, "old"), event(200, "middle"), event(300, "new")));
        assertEquals(List.of("middle", "new"), history.getEvents().stream().map(e -> e.getObject().getText()).toList());
        history.purgeBefore(300);
        assertEquals(1, history.getEvents().size());
        assertEquals("new", reopen().getEvents().getFirst().getObject().getText());
    }

    @Test
    void concurrentDeleteWaitsForRecordingAndDoesNotResurrectSelectedEvent() throws Exception {
        var history = reopen();
        var removed = event(100, "remove");
        var retained = event(200, "retain");
        history.record(true, candidate -> true, List.of(retained));
        var recording = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var deleting = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> {
                history.record(true, candidate -> {
                    recording.countDown();
                    await(release);
                    return true;
                }, List.of(removed));
                return null;
            });
            await(recording);
            var deleter = executor.submit(() -> {
                deleting.countDown();
                history.delete(List.of(removed));
                return null;
            });
            await(deleting);
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            deleter.get(10, TimeUnit.SECONDS);
            history.record(true, candidate -> true, List.of(removed));
            assertEquals(List.of(retained), history.getEvents());
            assertTrue(removed.getObject().isHistoryDeleted());
            assertFalse(retained.getObject().isHistoryDeleted());
            assertEquals(List.of("retain"), reopen().getEvents().stream().map(e -> e.getObject().getText()).toList());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void independentViewsCanDeleteSameSelectionWhileLateEventsArrive() throws Exception {
        var history = reopen();
        var removed = event(100, "remove");
        var retained = event(200, "retain");
        history.record(true, candidate -> true, List.of(removed, retained));
        var firstView = history.getEvents();
        var secondView = history.getEvents();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(3);
        try {
            var first = executor.submit(() -> {
                await(start);
                history.delete(List.of(firstView.getFirst()));
                return null;
            });
            var second = executor.submit(() -> {
                await(start);
                history.delete(List.of(secondView.getFirst()));
                return null;
            });
            var writer = executor.submit(() -> {
                await(start);
                for (int i = 0; i < 20; i++) {
                    history.record(true, candidate -> true, List.of(removed, retained));
                }
                return null;
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            writer.get(10, TimeUnit.SECONDS);
            assertEquals(List.of(retained), history.getEvents());
            assertEquals(List.of("retain"), reopen().getEvents().stream().map(e -> e.getObject().getText()).toList());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "History operation did not reach its synchronization point");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("History operation was interrupted", e);
        }
    }
}
