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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Bounded, atomic query-history snapshots. Callers must apply recording consent
 * and sensitive-statement filtering before adding records. Never stores connection credentials.
 * One instance owns one workspace file; this is not a cross-process database.
 */
public final class QueryHistoryStore {
    private static final int FORMAT_VERSION = 2;
    private static final long MAX_BYTES = 16 * 1024 * 1024;
    private static final Gson GSON = new Gson();
    private final Path file;
    private final int capacity;
    private List<Entry> entries;

    public record Entry(
        @NotNull UUID id, @NotNull String projectId, @NotNull String dataSourceId,
        @NotNull String dataSourceName, @NotNull String driverId, @NotNull String sql,
        @NotNull String purpose, @Nullable String schema, @Nullable String catalog,
        long startTime, long endTime, long rowCount, int errorCode, @Nullable String errorMessage,
        long updateRowCount, long fetchBeginTime, long fetchEndTime, boolean transactional,
        @Nullable String projectName, @Nullable String contextName
    ) {
    }

    private record Snapshot(int version, List<Entry> entries) {
    }

    public QueryHistoryStore(@NotNull Path file, int capacity) throws IOException {
        if (capacity < 1) {
            throw new IllegalArgumentException("History capacity must be positive");
        }
        this.file = file.toAbsolutePath();
        this.capacity = capacity;
        this.entries = read();
    }

    @NotNull
    public synchronized List<Entry> getEntries() {
        return List.copyOf(entries);
    }

    public synchronized void put(@NotNull Entry entry) throws IOException {
        validate(entry);
        var updated = new ArrayList<>(entries);
        updated.removeIf(old -> old.id().equals(entry.id()));
        updated.add(entry);
        updated.sort(java.util.Comparator.comparingLong(Entry::startTime));
        if (updated.size() > capacity) {
            updated.subList(0, updated.size() - capacity).clear();
        }
        save(updated);
    }

    public synchronized void delete(@NotNull Collection<UUID> ids) throws IOException {
        var selected = new HashSet<>(ids);
        var updated = new ArrayList<>(entries);
        updated.removeIf(entry -> selected.contains(entry.id()));
        save(updated);
    }

    public synchronized void purgeBefore(long cutoff) throws IOException {
        var updated = new ArrayList<>(entries);
        if (updated.removeIf(entry -> entry.startTime() < cutoff)) {
            save(updated);
        }
    }

    @NotNull
    private List<Entry> read() throws IOException {
        checkTarget();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (Files.size(file) > MAX_BYTES) {
            throw new IOException("Query history exceeds the size limit");
        }
        try {
            var document = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            validateDocument(document);
            var snapshot = GSON.fromJson(document, Snapshot.class);
            if (snapshot == null || snapshot.version() != FORMAT_VERSION || snapshot.entries() == null) {
                throw new IOException("Unsupported or incomplete query history snapshot");
            }
            var seen = new HashSet<UUID>();
            for (var entry : snapshot.entries()) {
                validate(entry);
                if (!seen.add(entry.id())) {
                    throw new IOException("Duplicate query history identity");
                }
            }
            var result = new ArrayList<>(snapshot.entries());
            result.sort(java.util.Comparator.comparingLong(Entry::startTime));
            if (result.size() > capacity) {
                result.subList(0, result.size() - capacity).clear();
            }
            return result;
        } catch (JsonParseException | IllegalArgumentException e) {
            // Do not attach parser exceptions: their messages may expose recorded SQL.
            throw new IOException("Invalid query history snapshot");
        }
    }

    private static void validateDocument(@NotNull JsonElement document) throws IOException {
        if (!document.isJsonObject()) {
            throw new IOException("Invalid query history snapshot");
        }
        var root = document.getAsJsonObject();
        validateInteger(root.get("version"), true);
        var items = root.get("entries");
        if (items == null || !items.isJsonArray()) {
            throw new IOException("Invalid query history snapshot");
        }
        for (var item : items.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                throw new IOException("Invalid query history entry");
            }
            var entry = item.getAsJsonObject();
            for (String key : List.of("id", "projectId", "dataSourceId", "dataSourceName", "driverId", "sql", "purpose")) {
                validateString(entry, key, true);
            }
            for (String key : List.of("schema", "catalog", "errorMessage", "projectName", "contextName")) {
                validateString(entry, key, false);
            }
            validateInteger(entry.get("startTime"), false);
            validateInteger(entry.get("endTime"), false);
            for (String key : List.of("rowCount", "errorCode", "updateRowCount", "fetchBeginTime", "fetchEndTime")) {
                var value = entry.get(key);
                if (value != null && !value.isJsonNull()) {
                    validateInteger(value, key.equals("errorCode"));
                }
            }
            var transactional = entry.get("transactional");
            if (transactional != null && !transactional.isJsonNull()
                && (!transactional.isJsonPrimitive() || !transactional.getAsJsonPrimitive().isBoolean())) {
                throw new IOException("Invalid query history flag");
            }
        }
    }

    private static void validateString(@NotNull JsonObject entry, @NotNull String key, boolean required) throws IOException {
        var value = entry.get(key);
        if (value == null || value.isJsonNull()) {
            if (!required) {
                return;
            }
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return;
        }
        throw new IOException("Invalid query history text field");
    }

    private static void validateInteger(@Nullable JsonElement value, boolean intRange) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Invalid query history numeric field");
        }
        try {
            var number = value.getAsBigDecimal();
            if (intRange) {
                number.intValueExact();
            } else {
                number.longValueExact();
            }
        } catch (ArithmeticException | NumberFormatException e) {
            // Neither values nor parser exception causes may disclose recorded content.
            throw new IOException("Invalid query history numeric field");
        }
    }

    private static void validate(@Nullable Entry entry) throws IOException {
        if (entry == null || entry.id() == null || entry.projectId() == null || entry.dataSourceId() == null
            || entry.dataSourceName() == null || entry.driverId() == null || entry.sql() == null
            || entry.purpose() == null || entry.startTime() < 0 || entry.endTime() < entry.startTime()) {
            throw new IOException("Incomplete query history entry");
        }
    }

    private void checkTarget() throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
            && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Query history target must be a regular file");
        }
    }

    private void save(@NotNull List<Entry> updated) throws IOException {
        checkTarget();
        byte[] data = GSON.toJson(new Snapshot(FORMAT_VERSION, updated)).getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_BYTES) {
            throw new IOException("Query history exceeds the size limit");
        }
        Path directory = file.getParent();
        // The workspace owner creates the directory; do not silently create arbitrary paths.
        Path temporary = Files.getFileStore(directory).supportsFileAttributeView("posix")
            ? Files.createTempFile(directory, ".query-history-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            : Files.createTempFile(directory, ".query-history-", ".tmp");
        try {
            Files.write(temporary, data);
            try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // Do not fall back to truncating the live file if atomic moves are unsupported.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            entries = List.copyOf(updated);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
