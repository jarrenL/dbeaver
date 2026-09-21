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

import org.jkiss.dbeaver.ext.postgresql.model.PostgreDatabase;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreProcedureKind;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreRole;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreServerExtension;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.sql.SQLException;
import java.util.List;

public class GaussDBSchemaTest {

    private JDBCSession session;

    private JDBCPreparedStatement statement;

    private GaussDBSchema schema;

    private GaussDBDataSource dataSource;

    private PostgreServerExtension serverExtension;

    @BeforeEach
    public void setUp() {
        session = Mockito.mock(JDBCSession.class);
        statement = Mockito.mock(JDBCPreparedStatement.class);
        schema = Mockito.mock(GaussDBSchema.class);
        dataSource = Mockito.mock(GaussDBDataSource.class);
        serverExtension = Mockito.mock(PostgreServerExtension.class);
    }

    @Test
    public void recognizesPostgreSQLAndGaussDBUtilitySchemas() {
        Assertions.assertTrue(GaussDBSchema.isUtilitySchema("information_schema"));
        Assertions.assertTrue(GaussDBSchema.isUtilitySchema("DBE_PERF"));
        Assertions.assertTrue(GaussDBSchema.isUtilitySchema("pg_toast"));
        Assertions.assertTrue(GaussDBSchema.isUtilitySchema("PG_TEMP_12"));
        Assertions.assertFalse(GaussDBSchema.isUtilitySchema("public"));
        Assertions.assertFalse(GaussDBSchema.isUtilitySchema(null));
    }

    @Test
    public void buildsNameAndKindConstrainedRoutineLookup() throws SQLException {
        Mockito.when(schema.getDataSource()).thenReturn(dataSource);
        Mockito.when(schema.getObjectId()).thenReturn(42L);
        Mockito.when(dataSource.getServerType()).thenReturn(serverExtension);
        Mockito.when(serverExtension.getProceduresOidColumn()).thenReturn("oid");
        Mockito.when(serverExtension.getProceduresSystemTable()).thenReturn("pg_proc");
        Mockito.when(session.getDataSource()).thenReturn(dataSource);
        Mockito.when(dataSource.isServerVersionAtLeast(7, 2)).thenReturn(true);
        Mockito.when(dataSource.isServerVersionAtLeast(8, 4)).thenReturn(true);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.when(session.prepareStatement(sql.capture())).thenReturn(statement);

        for (PostgreProcedureKind kind : List.of(PostgreProcedureKind.p, PostgreProcedureKind.f)) {
            Mockito.clearInvocations(statement);
            JDBCPreparedStatement result = GaussDBSchema.buildProceduresLookupStatement(
                session,
                schema,
                null,
                "calculate_total",
                kind
            );

            Assertions.assertSame(statement, result);
            Assertions.assertTrue(sql.getValue().contains("d.classoid='pg_proc'::regclass"));
            Assertions.assertTrue(sql.getValue().contains("p.prokind=?"));
            Assertions.assertTrue(sql.getValue().contains("p.proname=?"));
            Mockito.verify(statement).setLong(1, 42L);
            Mockito.verify(statement).setString(2, kind.name());
            Mockito.verify(statement).setString(3, "calculate_total");
        }
    }

    @Test
    public void packageCacheDegradesOnlyForOptionalMetadataErrors() {
        GaussDBSchema realSchema = createSchema();

        for (String sqlState : List.of("42501", "42P01", "42703", "42883", "3F000")) {
            realSchema.packageCache.clearCache();
            Assertions.assertTrue(realSchema.handlePackageCacheReadError(
                new SQLException("Optional metadata is unavailable", sqlState)));
            Assertions.assertTrue(realSchema.packageCache.isFullyCached());
            Assertions.assertTrue(realSchema.packageCache.isEmpty());
        }

        Assertions.assertFalse(realSchema.handlePackageCacheReadError(
            new SQLException("Connection failed", "08006")));
        Assertions.assertFalse(realSchema.handlePackageCacheReadError(
            new SQLException("Authentication failed", "28P01")));
    }

    @Test
    public void tableCacheUsesGaussDBPartitionCatalogWhenAvailable() throws SQLException {
        PostgreDatabase database = Mockito.mock(PostgreDatabase.class);
        GaussDBDataSource realDataSource = Mockito.mock(GaussDBDataSource.class);
        PostgreServerExtension realServerExtension = Mockito.mock(PostgreServerExtension.class);
        GaussDBServerInfo serverInfo = GaussDBServerInfo.forTest(
            null,
            java.util.Set.of("pg_partition"),
            java.util.Set.of()
        );
        Mockito.when(database.getDataSource()).thenReturn(realDataSource);
        Mockito.when(realDataSource.getServerType()).thenReturn(realServerExtension);
        Mockito.when(realDataSource.getServerInfo()).thenReturn(serverInfo);
        GaussDBSchema realSchema = new GaussDBSchema(database, "application", (PostgreRole) null);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.when(session.prepareStatement(sql.capture())).thenReturn(statement);

        realSchema.getTableCache().prepareLookupStatement(session, realSchema, null, null);

        Assertions.assertTrue(sql.getValue().contains("c.parttype::text AS gauss_parttype"));
        Assertions.assertTrue(sql.getValue().contains("pg_catalog.pg_partition"));
        Mockito.verify(statement).setLong(1, realSchema.getObjectId());
    }

    private static GaussDBSchema createSchema() {
        PostgreDatabase database = Mockito.mock(PostgreDatabase.class);
        GaussDBDataSource dataSource = Mockito.mock(GaussDBDataSource.class);
        PostgreServerExtension serverExtension = Mockito.mock(PostgreServerExtension.class);
        Mockito.when(database.getDataSource()).thenReturn(dataSource);
        Mockito.when(dataSource.getServerType()).thenReturn(serverExtension);
        return new GaussDBSchema(database, "application", (PostgreRole) null);
    }
}
