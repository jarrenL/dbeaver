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
import org.jkiss.dbeaver.ext.postgresql.model.PostgreTable;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreTableBase;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreTableColumn;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreTablePartition;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.dbeaver.model.struct.rdb.DBSTablePartition;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Physical GaussDB partition or subpartition from pg_partition.
 */
public class GaussDBTablePartition extends PostgreTablePartition {
    static final String LOAD_PARTITIONS_SQL =
        "SELECT p.oid,p.relname,t.relowner,NULL::text AS description,t.relacl,t.reloptions,t.relpersistence," +
            "p.reltablespace,false AS relhasoids," +
            "EXISTS (SELECT 1 FROM pg_catalog.pg_partition cp WHERE cp.parentid=p.oid AND cp.parttype='s') " +
            "AS relhassubclass,p.parttype::text AS gauss_parttype,p.partstrategy::text AS gauss_partstrategy," +
            "p.boundaries::text AS partition_expr " +
            "FROM pg_catalog.pg_partition p CROSS JOIN pg_catalog.pg_class t " +
            "WHERE t.oid=? AND p.parentid=? AND p.parttype IN ('p','s') " +
            "ORDER BY p.partitionno,p.oid";

    private final PostgreTable partitionParent;
    private final long rootTableId;
    private final boolean hasChildren;
    private volatile List<PostgreTableBase> partitions;

    private GaussDBTablePartition(
        @NotNull PostgreTable partitionParent,
        long rootTableId,
        @NotNull JDBCResultSet resultSet
    ) {
        super(partitionParent.getSchema(), resultSet);
        this.partitionParent = partitionParent;
        this.rootTableId = rootTableId;
        this.hasChildren = JDBCUtils.safeGetBoolean(resultSet, "relhassubclass");
        setPartitionExpression(formatPartitionExpression(
            JDBCUtils.safeGetString(resultSet, "gauss_partstrategy"),
            JDBCUtils.safeGetString(resultSet, "partition_expr")
        ));
    }

    @NotNull
    @Override
    public DBSTable getParentTable() {
        return partitionParent;
    }

    @NotNull
    @Override
    public PostgreTable getPartitionOf() {
        return partitionParent;
    }

    @Override
    public boolean isSubPartition() {
        return partitionParent instanceof DBSTablePartition;
    }

    @Nullable
    @Override
    public DBSTablePartition getPartitionParent() {
        return partitionParent instanceof DBSTablePartition partition ? partition : null;
    }

    @Override
    public boolean hasPartitions() {
        return hasChildren;
    }

    @Nullable
    @Override
    public synchronized List<PostgreTableBase> getPartitions(DBRProgressMonitor monitor) throws DBException {
        if (!hasChildren) {
            return null;
        }
        if (partitions == null) {
            partitions = loadPartitions(monitor, this, rootTableId);
        }
        return partitions.isEmpty() ? null : partitions;
    }

    @Override
    public List<? extends PostgreTableColumn> getAttributes(@NotNull DBRProgressMonitor monitor) throws DBException {
        return partitionParent.getAttributes(monitor);
    }

    @NotNull
    static List<PostgreTableBase> loadPartitions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull PostgreTable parent,
        long rootTableId
    ) throws DBException {
        List<PostgreTableBase> result = new ArrayList<>();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, parent, "Read GaussDB partitions");
             JDBCPreparedStatement statement = session.prepareStatement(LOAD_PARTITIONS_SQL)) {
            statement.setLong(1, rootTableId);
            statement.setLong(2, parent.getObjectId());
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(new GaussDBTablePartition(parent, rootTableId, resultSet));
                }
            }
        } catch (SQLException e) {
            throw new DBException("Error reading GaussDB partitions", e);
        }
        return result;
    }

    @NotNull
    static String formatPartitionExpression(@Nullable String strategy, @Nullable String boundaries) {
        if (boundaries == null || boundaries.isBlank()) {
            return "";
        }
        List<BoundaryValue> values = parseBoundaryArray(boundaries.trim());
        String value = values.stream()
            .map(boundary -> formatBoundaryValue(strategy, boundary))
            .collect(java.util.stream.Collectors.joining(","));
        if ("r".equalsIgnoreCase(strategy)) {
            return "VALUES LESS THAN (" + value + ")";
        }
        if ("l".equalsIgnoreCase(strategy)) {
            return "VALUES (" + value + ")";
        }
        if ("h".equalsIgnoreCase(strategy)) {
            return "HASH BUCKET (" + value + ")";
        }
        return "VALUES (" + value + ")";
    }

    @NotNull
    private static String formatBoundaryValue(@Nullable String strategy, @NotNull BoundaryValue boundary) {
        if (!boundary.quoted() && "NULL".equalsIgnoreCase(boundary.value())) {
            return "r".equalsIgnoreCase(strategy) ? "MAXVALUE" : "DEFAULT";
        }
        if ("h".equalsIgnoreCase(strategy) || isNumeric(boundary.value())) {
            return boundary.value();
        }
        return "'" + boundary.value().replace("'", "''") + "'";
    }

    private static boolean isNumeric(@NotNull String value) {
        return value.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");
    }

    @NotNull
    private static List<BoundaryValue> parseBoundaryArray(@NotNull String text) {
        String value = text;
        if (value.startsWith("{") && value.endsWith("}")) {
            value = value.substring(1, value.length() - 1);
        }
        List<BoundaryValue> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean elementWasQuoted = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaped) {
                current.append(ch);
                escaped = false;
            } else if (quoted && ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                quoted = !quoted;
                elementWasQuoted = true;
            } else if (ch == ',' && !quoted) {
                result.add(new BoundaryValue(
                    elementWasQuoted ? current.toString() : current.toString().trim(), elementWasQuoted));
                current.setLength(0);
                elementWasQuoted = false;
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty() || elementWasQuoted || value.endsWith(",")) {
            result.add(new BoundaryValue(
                elementWasQuoted ? current.toString() : current.toString().trim(), elementWasQuoted));
        }
        return result;
    }

    private record BoundaryValue(@NotNull String value, boolean quoted) {
    }
}
