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
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceListener;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.qm.*;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** Workspace-scoped, explicitly enabled persistence. SQL text may contain sensitive data. */
public class QueryHistoryPersistenceService implements QMMetaListener, DBPPreferenceListener {
    private static final Log log = Log.getLog(QueryHistoryPersistenceService.class);
    private final DBPPreferenceStore preferences;
    private final Path file;
    private final QMEventFilter filter;
    private PersistentQueryHistory history;
    private boolean enabled;
    private long enabledSince;
    private IOException failure;

    public QueryHistoryPersistenceService(
        @NotNull DBPPreferenceStore preferences, @NotNull Path file, @NotNull QMEventFilter filter
    ) {
        this.preferences = preferences;
        this.file = file;
        this.filter = filter;
        enabled = preferences.getBoolean(QMConstants.PROP_STORE_HISTORY);
        enabledSince = System.currentTimeMillis();
        preferences.addPropertyChangeListener(this);
    }

    @Override
    public synchronized void preferenceChange(PreferenceChangeEvent event) {
        if (filter instanceof DefaultEventFilter defaultFilter) {
            defaultFilter.reloadPreferences();
        }
        if (QMConstants.PROP_STORE_HISTORY.equals(event.getProperty())) {
            boolean newEnabled = preferences.getBoolean(QMConstants.PROP_STORE_HISTORY);
            if (newEnabled && !enabled) {
                enabledSince = System.currentTimeMillis();
                failure = null;
            }
            enabled = newEnabled;
        }
    }

    private PersistentQueryHistory history() throws IOException {
        if (failure != null) {
            throw failure;
        }
        if (history == null) {
            history = new PersistentQueryHistory(new QueryHistoryStore(file, 2000));
        }
        long days = Math.max(1, preferences.getInt(QMConstants.PROP_HISTORY_DAYS));
        history.purgeBefore(System.currentTimeMillis() - days * 86_400_000L);
        return history;
    }

    @Override
    public synchronized void metaInfoChanged(@NotNull DBRProgressMonitor monitor, @NotNull List<QMMetaEvent> events) {
        if (!enabled) {
            return;
        }
        try {
            history().record(true, event -> event.getObject().getOpenTime() >= enabledSince && filter.accept(event), events);
        } catch (IOException e) {
            // Do not log SQL, connection properties or serialized contents.
            failure = e;
            log.error("Unable to persist query history; reopen query history to view the error");
        }
    }

    @NotNull
    public synchronized List<QMMetaEvent> getEvents() throws IOException {
        return enabled ? history().getEvents() : List.of();
    }

    public synchronized void delete(@NotNull Collection<? extends QMEvent> events) throws IOException {
        // Turning recording off must not let an explicit deletion reappear when it is re-enabled.
        if (enabled || history != null) {
            history().delete(events);
        }
    }

    public synchronized void dispose() {
        preferences.removePropertyChangeListener(this);
        enabled = false;
    }
}
