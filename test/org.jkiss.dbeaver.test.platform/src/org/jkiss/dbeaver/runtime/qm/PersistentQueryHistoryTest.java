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
}
