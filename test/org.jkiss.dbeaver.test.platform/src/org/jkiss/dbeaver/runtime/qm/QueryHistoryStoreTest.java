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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class QueryHistoryStoreTest {
    @TempDir
    Path directory;

    private QueryHistoryStore.Entry entry(long time, String sql) {
        return new QueryHistoryStore.Entry(UUID.randomUUID(), "project", "connection", "验收连接", "gaussdb",
            sql, "USER", "模式", "database", time, time + 10, 42, 0, null, -1, 0, 0, false, "Project", "SQL");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "sql,123", "sql,true", "projectId,42", "errorMessage,false",
        "startTime,99.5", "endTime,9223372036854775908", "startTime,null",
        "startTime,\"100\"", "transactional,\"true\"", "errorCode,4294967296"
    })
    void malformedFieldCannotBeCoercedIntoValidHistory(String field, String rawValue) throws Exception {
        var file = directory.resolve("history.json");
        var expected = entry(100, "SELECT 'private-history-marker'");
        new QueryHistoryStore(file, 10).put(expected);
        var valid = Files.readAllBytes(file);
        var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.getAsJsonArray("entries").get(0).getAsJsonObject()
            .add(field, com.google.gson.JsonParser.parseString(rawValue));
        Files.writeString(file, json.toString());
        var damaged = Files.readAllBytes(file);
        var error = assertThrows(IOException.class, () -> new QueryHistoryStore(file, 10));
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("private-history-marker"));
        assertArrayEquals(damaged, Files.readAllBytes(file));
        Files.write(file, valid);
        assertEquals(List.of(expected), new QueryHistoryStore(file, 10).getEntries());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"\"2\"", "2.5", "4294967298", "null"})
    void invalidVersionRepresentationIsRejected(String rawVersion) throws Exception {
        var file = directory.resolve("history.json");
        var content = "{\"version\":" + rawVersion + ",\"entries\":[]}";
        Files.writeString(file, content);
        assertThrows(IOException.class, () -> new QueryHistoryStore(file, 10));
        assertEquals(content, Files.readString(file));
    }

    @Test
    void missingOptionalFieldsAndUnknownFieldsRemainCompatible() throws Exception {
        var file = directory.resolve("history.json");
        var expected = entry(100, "SELECT '兼容🧪'");
        new QueryHistoryStore(file, 10).put(expected);
        var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        var item = json.getAsJsonArray("entries").get(0).getAsJsonObject();
        for (String field : List.of("schema", "catalog", "errorMessage", "projectName", "contextName",
            "rowCount", "errorCode", "updateRowCount", "fetchBeginTime", "fetchEndTime", "transactional")) {
            item.remove(field);
        }
        item.addProperty("futureField", "ignored");
        Files.writeString(file, json.toString());
        var actual = new QueryHistoryStore(file, 10).getEntries().getFirst();
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.sql(), actual.sql());
        assertEquals(100, actual.startTime());
        assertEquals(110, actual.endTime());
        assertNull(actual.schema());
        assertNull(actual.errorMessage());
        assertEquals(0, actual.rowCount());
        assertEquals(0, actual.updateRowCount());
        assertFalse(actual.transactional());
    }

    @Test
    void maximumTimestampAndNegativeUnknownCountsRoundTripExactly() throws Exception {
        var file = directory.resolve("history.json");
        var expected = new QueryHistoryStore.Entry(UUID.randomUUID(), "project", "connection", "连接", "gaussdb",
            "SELECT 1", "USER", null, null, Long.MAX_VALUE - 1, Long.MAX_VALUE, -1,
            Integer.MIN_VALUE, null, -1, 0, 0, true, null, null);
        new QueryHistoryStore(file, 10).put(expected);
        assertEquals(List.of(expected), new QueryHistoryStore(file, 10).getEntries());
    }

    @Test
    void restartPreservesMultilineUnicodeAndIdentity() throws Exception {
        var file = directory.resolve("history.json");
        var expected = entry(100, "SELECT '中文\\\"';\n-- 多行\nSELECT '\t'");
        new QueryHistoryStore(file, 10).put(expected);
        assertEquals(List.of(expected), new QueryHistoryStore(file, 10).getEntries());
        assertThrows(UnsupportedOperationException.class,
            () -> new QueryHistoryStore(file, 10).getEntries().clear());
    }

    @Test
    void deletionSurvivesRestartAndPreservesUnselectedEntries() throws Exception {
        var file = directory.resolve("history.json");
        var first = entry(100, "SELECT 1");
        var second = entry(200, "SELECT 2");
        var store = new QueryHistoryStore(file, 10);
        store.put(first);
        store.put(second);
        store.delete(List.of(first.id(), UUID.randomUUID()));
        assertEquals(List.of(second), new QueryHistoryStore(file, 10).getEntries());
        store.delete(List.of(second.id()));
        assertTrue(new QueryHistoryStore(file, 10).getEntries().isEmpty());
    }

    @Test
    void updatesReplaceIdentityAndCapacityKeepsNewestStartTimes() throws Exception {
        var file = directory.resolve("history.json");
        var store = new QueryHistoryStore(file, 2);
        var first = entry(100, "SELECT 1");
        var second = entry(200, "SELECT 2");
        var third = entry(300, "SELECT 3");
        store.put(second);
        store.put(first);
        store.put(third);
        store.put(third);
        assertEquals(List.of(second, third), new QueryHistoryStore(file, 2).getEntries());
        assertEquals(List.of(third), new QueryHistoryStore(file, 1).getEntries());
    }

    @Test
    void retentionBoundaryIsInclusiveAndDurable() throws Exception {
        var file = directory.resolve("history.json");
        var store = new QueryHistoryStore(file, 10);
        store.put(entry(99, "old"));
        var retained = entry(100, "boundary");
        store.put(retained);
        store.purgeBefore(100);
        assertEquals(List.of(retained), new QueryHistoryStore(file, 10).getEntries());
    }

    @Test
    void malformedAndUnknownFormatsAreNotSilentlyOverwritten() throws Exception {
        var file = directory.resolve("history.json");
        for (String damaged : List.of("{broken", "null", "{}", "{\"version\":999,\"entries\":[]}",
            "{\"version\":2,\"entries\":[null]}")) {
            Files.writeString(file, damaged);
            assertThrows(IOException.class, () -> new QueryHistoryStore(file, 10));
            assertEquals(damaged, Files.readString(file));
        }
    }

    @Test
    void failedSaveDoesNotChangeMemoryOrClobberUnexpectedTarget() throws Exception {
        var file = directory.resolve("history.json");
        var store = new QueryHistoryStore(file, 10);
        var saved = entry(100, "retained");
        store.put(saved);
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(IOException.class, () -> store.put(entry(200, "not saved")));
        assertEquals(List.of(saved), store.getEntries());
        assertTrue(Files.isDirectory(file));
    }

    @Test
    void rejectsSymlinkWithoutTouchingItsTarget() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        var target = directory.resolve("unrelated");
        Files.writeString(target, "untouched");
        var file = directory.resolve("history.json");
        Files.createSymbolicLink(file, target);
        assertThrows(IOException.class, () -> new QueryHistoryStore(file, 10));
        assertEquals("untouched", Files.readString(target));
    }

    @Test
    void failedDeleteCanRetryWithoutLosingUnselectedHistory() throws Exception {
        assertFailedRemovalCanRetry(false);
    }

    @Test
    void failedRetentionCanRetryWithoutLosingBoundaryHistory() throws Exception {
        assertFailedRemovalCanRetry(true);
    }

    private void assertFailedRemovalCanRetry(boolean retention) throws Exception {
        var file = directory.resolve("历史 快照.json");
        var store = new QueryHistoryStore(file, 10);
        var old = entry(99, "SELECT '旧记录'");
        var boundary = entry(100, "SELECT '保留🧪'");
        store.put(old);
        store.put(boundary);
        var original = Files.readAllBytes(file);
        var backup = directory.resolve("saved.json");
        Files.move(file, backup);
        Files.createDirectory(file);
        var unrelated = file.resolve("unrelated.txt");
        Files.writeString(unrelated, "do not modify");
        assertThrows(IOException.class, () -> {
            if (retention) {
                store.purgeBefore(100);
            } else {
                store.delete(List.of(old.id()));
            }
        });
        assertEquals(List.of(old, boundary), store.getEntries());
        assertArrayEquals(original, Files.readAllBytes(backup));
        assertEquals("do not modify", Files.readString(unrelated));
        try (var files = Files.list(directory)) {
            assertEquals(2, files.count(), "Failure must not leave temporary snapshots");
        }
        Files.delete(unrelated);
        Files.delete(file);
        Files.move(backup, file);
        if (retention) {
            store.purgeBefore(100);
        } else {
            store.delete(List.of(old.id()));
        }
        assertEquals(List.of(boundary), store.getEntries());
        assertEquals(List.of(boundary), new QueryHistoryStore(file, 10).getEntries());
    }

    @Test
    void duplicateIdentityOnDiskIsRejectedWithoutExposingSqlOrChangingFile() throws Exception {
        var file = directory.resolve("history.json");
        new QueryHistoryStore(file, 10).put(entry(100, "SELECT 'private-marker-🧪'"));
        var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        var items = json.getAsJsonArray("entries");
        items.add(items.get(0).deepCopy());
        Files.writeString(file, json.toString());
        var original = Files.readAllBytes(file);
        var error = assertThrows(IOException.class, () -> new QueryHistoryStore(file, 10));
        assertFalse(error.getMessage().contains("private-marker"));
        assertNull(error.getCause());
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void snapshotsUsePrivatePermissionsAndLeaveNoTemporaryFiles() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        var file = directory.resolve("history.json");
        new QueryHistoryStore(file, 10).put(entry(100, "SELECT 1"));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(file), files.toList());
        }
    }

    @Test
    void errorDetailsAndDifferentConnectionIdentitySurviveRestart() throws Exception {
        var file = directory.resolve("history.json");
        var store = new QueryHistoryStore(file, 10);
        var success = entry(100, "SELECT 1");
        var failed = new QueryHistoryStore.Entry(UUID.randomUUID(), "other-project", "other-connection",
            "其他连接", "postgresql", "SELECT missing", "USER", null, null,
            200, 220, -1, 42, "找不到对象\n详细信息", -1, 0, 0, true, "Other project", "SQL");
        store.put(success);
        store.put(failed);
        var restarted = new QueryHistoryStore(file, 10);
        assertEquals(List.of(success, failed), restarted.getEntries());
        restarted.delete(List.of(success.id()));
        assertEquals(List.of(failed), new QueryHistoryStore(file, 10).getEntries());
    }

    @Test
    void oversizedUpdateCannotReplacePreviousSnapshot() throws Exception {
        var file = directory.resolve("history.json");
        var store = new QueryHistoryStore(file, 10);
        var original = entry(100, "SELECT 1");
        store.put(original);
        assertThrows(IOException.class, () -> store.put(entry(200, "x".repeat(16 * 1024 * 1024))));
        assertEquals(List.of(original), store.getEntries());
        assertEquals(List.of(original), new QueryHistoryStore(file, 10).getEntries());
    }
}
