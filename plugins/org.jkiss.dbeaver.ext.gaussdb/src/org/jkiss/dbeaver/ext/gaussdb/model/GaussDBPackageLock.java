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
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * Serializes DBeaver package persistence and distributed compile fallback.
 *
 * <p>The lock is deliberately transaction-scoped. It coordinates DBeaver clients
 * that use this same contract; an external client that does not acquire this lock
 * remains outside that contract.</p>
 */
public final class GaussDBPackageLock {
    private static final String TRANSACTION_LOCK_SQL = "SELECT pg_catalog.pg_advisory_xact_lock(?)";
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private GaussDBPackageLock() {
    }

    public static boolean isRequired(@NotNull GaussDBPackage object) {
        GaussDBDataSource dataSource = object.getDataSource();
        if (dataSource == null) {
            return false;
        }
        GaussDBServerInfo serverInfo = dataSource.getServerInfo();
        if (serverInfo == null) {
            return false;
        }
        return serverInfo.getDeployment() == GaussDBServerInfo.Deployment.DISTRIBUTED ||
            serverInfo.getDeployment() == GaussDBServerInfo.Deployment.CLOUD_NATIVE;
    }

    public static long getKey(@NotNull GaussDBPackage object) {
        long hash = FNV_OFFSET_BASIS;
        hash = update(hash, Long.toString(object.getDatabase().getObjectId()));
        hash = update(hash, Long.toString(object.getSchema().getObjectId()));
        hash = update(hash, object.getSchema().getName());
        return update(hash, object.getName());
    }

    @NotNull
    public static String getSQL(@NotNull GaussDBPackage object) {
        return "SELECT pg_catalog.pg_advisory_xact_lock(" + getKey(object) + ")";
    }

    public static void acquireTransaction(@NotNull JDBCSession session, @NotNull GaussDBPackage object)
        throws SQLException {
        try (JDBCPreparedStatement statement = session.prepareStatement(TRANSACTION_LOCK_SQL)) {
            statement.setLong(1, getKey(object));
            statement.execute();
        }
    }

    private static long update(long hash, @NotNull String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        hash = mixLong(hash, bytes.length);
        for (byte valueByte : bytes) {
            hash ^= valueByte & 0xffL;
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private static long mixLong(long hash, long value) {
        for (int index = 0; index < Long.BYTES; index++) {
            hash ^= value & 0xffL;
            hash *= FNV_PRIME;
            value >>>= Byte.SIZE;
        }
        return hash;
    }
}
