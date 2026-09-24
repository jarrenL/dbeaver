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

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.qm.QMEvent;
import org.jkiss.dbeaver.model.qm.QMEventFilter;
import org.jkiss.dbeaver.model.qm.QMMetaEvent;
import org.jkiss.dbeaver.model.qm.meta.QMMObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Serializes persistence and deletion; metadata events retain their in-process identity. */
public final class PersistentQueryHistory {
    private final QueryHistoryStore store;
    private final Map<QMMObject, UUID> identities = new WeakHashMap<>();
    private final Map<UUID, QMMetaEvent> persisted = new HashMap<>();

    public PersistentQueryHistory(@NotNull QueryHistoryStore store) throws IOException {
        this.store = store;
        try {
            for (var entry : store.getEntries()) {
                var event = QueryHistoryEventMapper.restore(entry);
                identities.put(event.getObject(), entry.id());
                persisted.put(entry.id(), event);
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Unsupported query history metadata");
        }
    }

    /** Called by the event dispatcher, not by the SWT UI thread. */
    public synchronized void record(
        boolean enabled, @NotNull QMEventFilter filter, @NotNull List<QMMetaEvent> events
    ) throws IOException {
        if (!enabled) {
            return;
        }
        for (var event : events) {
            if (event.getObject().isHistoryDeleted() || !filter.accept(event)) {
                continue;
            }
            var id = identities.computeIfAbsent(event.getObject(), ignored -> UUID.randomUUID());
            var entry = QueryHistoryEventMapper.snapshot(id, event);
            if (entry != null) {
                store.put(entry);
                persisted.put(id, event);
                discardEvicted();
            }
        }
    }

    /** Marks objects deleted only after durable deletion succeeds. Delayed events cannot resurrect them. */
    public synchronized void delete(@NotNull Collection<? extends QMEvent> events) throws IOException {
        var ids = new ArrayList<UUID>();
        for (var event : events) {
            var id = identities.get(event.getObject());
            if (id != null) {
                ids.add(id);
            }
        }
        store.delete(ids);
        events.forEach(event -> event.getObject().deleteFromHistory());
        discardEvicted();
    }

    public synchronized void purgeBefore(long cutoff) throws IOException {
        store.purgeBefore(cutoff);
        discardEvicted();
    }

    @NotNull
    public synchronized List<QMMetaEvent> getEvents() {
        var events = new ArrayList<QMMetaEvent>();
        for (var entry : store.getEntries()) {
            var event = persisted.get(entry.id());
            if (event != null && !event.getObject().isHistoryDeleted()) {
                events.add(event);
            }
        }
        return List.copyOf(events);
    }

    private void discardEvicted() {
        var retained = new java.util.HashSet<UUID>();
        store.getEntries().forEach(entry -> retained.add(entry.id()));
        persisted.keySet().retainAll(retained);
    }
}
