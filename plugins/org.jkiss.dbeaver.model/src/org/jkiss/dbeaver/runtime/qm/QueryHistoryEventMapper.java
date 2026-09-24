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
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.qm.QMEventAction;
import org.jkiss.dbeaver.model.qm.QMMetaEvent;
import org.jkiss.dbeaver.model.qm.meta.QMMConnectionInfo;
import org.jkiss.dbeaver.model.qm.meta.QMMProjectInfo;
import org.jkiss.dbeaver.model.qm.meta.QMMStatementExecuteInfo;
import org.jkiss.dbeaver.model.qm.meta.QMMStatementInfo;

import java.util.UUID;

/** Maps completed query metadata only; never reconstructs live JDBC resources. */
public final class QueryHistoryEventMapper {
    private QueryHistoryEventMapper() {
    }

    @Nullable
    public static QueryHistoryStore.Entry snapshot(@NotNull UUID id, @NotNull QMMetaEvent event) {
        if (!(event.getObject() instanceof QMMStatementExecuteInfo execution)
            || !execution.isClosed() || execution.isFetching() || execution.isHistoryDeleted()) {
            return null;
        }
        var connection = execution.getConnection();
        var statement = execution.getStatement();
        if (connection == null || !connection.isLoggingEnabled() || statement == null
            || statement.getPurpose() == null || execution.getQueryString() == null
            || execution.getQueryString().isBlank()) {
            return null;
        }
        return new QueryHistoryStore.Entry(id, connection.getProjectId(), connection.getContainerId(),
            connection.getContainerName() == null ? connection.getContainerId() : connection.getContainerName(),
            connection.getDriverId(), execution.getQueryString(), statement.getPurpose().name(),
            execution.getSchema(), execution.getCatalog(), execution.getOpenTime(), execution.getCloseTime(),
            execution.getFetchRowCount(), execution.getErrorCode(), execution.getErrorMessage(),
            execution.getUpdateRowCount(), execution.getFetchBeginTime(), execution.getFetchEndTime(), execution.isTransactional(),
            connection.getProjectInfo() == null ? null : connection.getProjectInfo().getName(), connection.getContextName());
    }

    @NotNull
    public static QMMetaEvent restore(@NotNull QueryHistoryStore.Entry entry) {
        var purpose = DBCExecutionPurpose.valueOf(entry.purpose());
        var project = QMMProjectInfo.builder().setId(entry.projectId()).setName(entry.projectName()).build();
        var connection = QMMConnectionInfo.builder()
            .setProjectInfo(project).setContainerId(entry.dataSourceId()).setContainerName(entry.dataSourceName())
            .setDriverId(entry.driverId()).setContextName(entry.contextName())
            .setOpenTime(entry.startTime()).setCloseTime(entry.endTime()).build();
        var statement = new QMMStatementInfo(entry.startTime(), entry.endTime(), connection, purpose);
        var execution = new RestoredExecution(entry, statement);
        // A distinct historical session must not match the currently active application session.
        return new QMMetaEvent(execution, QMEventAction.END, entry.endTime(), "history:" + entry.id());
    }

    private static final class RestoredExecution extends QMMStatementExecuteInfo {
        private final long updateCount;

        private RestoredExecution(QueryHistoryStore.Entry entry, QMMStatementInfo statement) {
            super(entry.startTime(), entry.endTime(), statement, entry.sql(), entry.rowCount(), entry.errorCode(),
                entry.errorMessage(), entry.fetchBeginTime(), entry.fetchEndTime(), entry.transactional(),
                entry.schema(), entry.catalog());
            updateCount = entry.updateRowCount();
        }

        @Override
        public long getUpdateRowCount() {
            return updateCount;
        }
    }
}
