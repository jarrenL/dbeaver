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
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreSchema;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreTableBase;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreTableRegular;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;

import java.util.List;
import java.util.Locale;

/**
 * GaussDB table metadata backed by pg_class.parttype and pg_partition.
 */
public class GaussDBTable extends PostgreTableRegular {
    private final boolean partitioned;
    private final String partitionKey;
    private volatile List<PostgreTableBase> partitions;

    public GaussDBTable(@NotNull PostgreSchema schema, @NotNull JDBCResultSet resultSet) {
        super(schema, resultSet);
        String partType = JDBCUtils.safeGetString(resultSet, "gauss_parttype");
        this.partitioned = "p".equalsIgnoreCase(partType) || "s".equalsIgnoreCase(partType);
        this.partitionKey = partitioned
            ? formatPartitionKey(
                JDBCUtils.safeGetString(resultSet, "gauss_partstrategy"),
                JDBCUtils.safeGetString(resultSet, "gauss_partkey")
            )
            : null;
        if (getDataSource().getServerType().supportsRowLevelSecurity()) {
            String[] options = getRelOptions();
            if (options != null) {
                for (String option : options) {
                    if ("enable_rowsecurity=true".equalsIgnoreCase(option)) {
                        setHasRowLevelSecurity(true);
                        break;
                    }
                }
            }
        }
    }

    @Override
    public boolean hasPartitions() {
        return partitioned;
    }

    @Nullable
    @Override
    public String getPartitionKey() {
        return partitionKey;
    }

    @Nullable
    @Override
    public synchronized List<PostgreTableBase> getPartitions(DBRProgressMonitor monitor) throws DBException {
        if (!partitioned) {
            return null;
        }
        if (partitions == null) {
            partitions = GaussDBTablePartition.loadPartitions(monitor, this, getObjectId());
        }
        return partitions.isEmpty() ? null : partitions;
    }

    @Override
    public boolean supportsObjectDefinitionOption(@NotNull String option) {
        return (partitioned && DBPScriptObject.OPTION_INCLUDE_PARTITIONS.equals(option))
            || super.supportsObjectDefinitionOption(option);
    }

    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        partitions = null;
        return super.refreshObject(monitor);
    }

    @NotNull
    private static String formatPartitionKey(@Nullable String strategy, @Nullable String key) {
        String strategyName = switch (strategy == null ? "" : strategy.toLowerCase(Locale.ENGLISH)) {
            case "r" -> "RANGE";
            case "l" -> "LIST";
            case "h" -> "HASH";
            case "i" -> "INTERVAL";
            default -> "PARTITION";
        };
        return key == null || key.isBlank() ? strategyName : strategyName + " (attribute " + key.trim() + ")";
    }
}
