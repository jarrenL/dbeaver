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

import org.jkiss.dbeaver.ext.postgresql.PostgreConstants;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreSetting;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPKeywordType;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.connection.DBPNativeClientLocation;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

public class PostgreServerGaussDBTest {

    private GaussDBDataSource dataSource;

    private PostgreSetting setting;

    @TempDir
    Path tempDir;

    @BeforeEach
    public void setUp() {
        dataSource = Mockito.mock(GaussDBDataSource.class);
        setting = Mockito.mock(PostgreSetting.class);
    }

    @Test
    public void configuresNativeClientLibraryPath() throws IOException {
        Path libraryFolder = Files.createDirectory(tempDir.resolve("lib"));
        DBPNativeClientLocation client = Mockito.mock(DBPNativeClientLocation.class);
        Mockito.when(client.getPath()).thenReturn(tempDir.toFile());
        Map<String, String> environment = new HashMap<>();
        environment.put("LD_LIBRARY_PATH", "/existing/lib");

        new PostgreServerGaussDB(dataSource).configureNativeToolEnvironment(client, environment);

        Assertions.assertEquals(
            libraryFolder.toFile().getAbsolutePath() + File.pathSeparator + "/existing/lib",
            environment.get("LD_LIBRARY_PATH")
        );
        Assertions.assertEquals(libraryFolder.toFile().getAbsolutePath(), environment.get("DYLD_LIBRARY_PATH"));
    }

    @Test
    public void configuresGaussDBKeywordsTypesAndFunctions() {
        GaussDBDialect dialect = new GaussDBDialect();
        PostgreServerGaussDB server = new PostgreServerGaussDB(dataSource);

        server.configureDialect(dialect);

        Assertions.assertFalse(dialect.getMatchedKeywords("PACKAGE").isEmpty());
        Assertions.assertFalse(dialect.getMatchedKeywords("IGNORE").isEmpty());
        Assertions.assertEquals(DBPKeywordType.TYPE, dialect.getKeywordType("FLOATVECTOR"));
        Assertions.assertTrue(dialect.getFunctions().stream().anyMatch("hll_union"::equalsIgnoreCase));
        Assertions.assertTrue(dialect.getFunctions().stream().anyMatch("vector_norm"::equalsIgnoreCase));
        Assertions.assertFalse(dialect.getFunctions().stream().anyMatch("gs_dump"::equalsIgnoreCase));
    }

    @Test
    public void disablesUnsupportedMCompatibilityFeatures() {
        GaussDBServerInfo serverInfo = GaussDBServerInfo.forTest(
            DBCompatibilityEnum.M,
            java.util.Set.of("pg_foreign_server", "pg_partition"),
            java.util.Set.of()
        );
        Mockito.when(dataSource.getServerInfo()).thenReturn(serverInfo);

        PostgreServerGaussDB server = new PostgreServerGaussDB(dataSource);

        Assertions.assertTrue(server.supportsMaterializedViews());
        Assertions.assertTrue(server.supportsPartitions());
        Assertions.assertTrue(server.supportsStoredProcedures());
        Assertions.assertTrue(server.supportsFunctionCreate());
        Assertions.assertFalse(server.supportsForeignServers());
        Assertions.assertTrue(server.supportsTriggers());
        Assertions.assertFalse(server.supportsRules());
        Assertions.assertTrue(server.supportsAggregates());
        Assertions.assertFalse(server.supportsInsertOnConflict());
        Assertions.assertTrue(server.supportsNativeClient());
        Assertions.assertTrue(server.supportsNativeBackupAll());
        Assertions.assertFalse(server.supportsNativeBackupAllPasswordSuppression());
        Assertions.assertFalse(server.supportsNativeBackupAllDatabaseFilter());
        Assertions.assertFalse(server.supportsNativeToolStreaming());
    }

    @Test
    public void enablesGaussDBRlsAndGeneratedColumnCatalogs() {
        GaussDBServerInfo serverInfo = GaussDBServerInfo.forTest(
            DBCompatibilityEnum.POSTGRES,
            java.util.Set.of("pg_rlspolicies", "pg_attrdef"),
            java.util.Set.of("pg_attrdef.adgencol"),
            java.util.Set.of()
        );
        Mockito.when(dataSource.getServerInfo()).thenReturn(serverInfo);

        PostgreServerGaussDB server = new PostgreServerGaussDB(dataSource);

        Assertions.assertTrue(server.supportsRowLevelSecurity());
        Assertions.assertFalse(server.supportsPolicyWithCheck());
        Assertions.assertFalse(server.supportsPolicyInsertEvent());
        Assertions.assertTrue(server.supportsGeneratedColumns());
        Assertions.assertTrue(server.supportsInsertOnConflict());
        Assertions.assertTrue(server.getTablePoliciesQuery().contains("pg_rlspolicies"));
    }

    @Test
    public void readsBackslashEscapeSupportFromServerSetting() {
        PostgreServerGaussDB server = new PostgreServerGaussDB(dataSource);
        Mockito.when(dataSource.getSetting(PostgreConstants.OPTION_STANDARD_CONFORMING_STRINGS)).thenReturn(setting);

        Mockito.when(setting.getValue()).thenReturn("off");
        Assertions.assertTrue(server.supportsBackslashStringEscape());

        Mockito.when(setting.getValue()).thenReturn("on");
        Assertions.assertFalse(server.supportsBackslashStringEscape());
    }

    @Test
    public void usesConservativePostgreSQLCatalogCompatibilityVersion() {
        GaussDBDataSource source = Mockito.mock(GaussDBDataSource.class, Mockito.CALLS_REAL_METHODS);

        Assertions.assertTrue(source.isServerVersionAtLeast(7, 2));
        Assertions.assertTrue(source.isServerVersionAtLeast(9, 2));
        Assertions.assertFalse(source.isServerVersionAtLeast(9, 3));
        Assertions.assertFalse(source.isServerVersionAtLeast(10, 0));
    }

    @Test
    public void mapsGaussDBCompatibilityTypes() {
        PostgreServerGaussDB server = new PostgreServerGaussDB(dataSource);

        Assertions.assertEquals(Types.TINYINT, server.resolveDataTypeValueType("int1", 5545, null, 1, Types.NUMERIC));
        Assertions.assertEquals(Types.INTEGER, server.resolveDataTypeValueType("mediumint", 9877, null, 4, Types.NUMERIC));
        Assertions.assertEquals(Types.VARBINARY, server.resolveDataTypeValueType("varbinary", 9881, null, -1, Types.VARCHAR));
        Assertions.assertEquals(Types.LONGVARCHAR, server.resolveDataTypeValueType("longtext", 9976, null, -1, Types.VARCHAR));
    }

    @Test
    public void respectsYearIsDateTypeDriverProperty() {
        PostgreServerGaussDB server = new PostgreServerGaussDB(dataSource);
        DBPConnectionConfiguration configuration = new DBPConnectionConfiguration();
        DBPDataSourceContainer container = Mockito.mock(DBPDataSourceContainer.class);
        Mockito.when(dataSource.getContainer()).thenReturn(container);
        Mockito.when(container.getActualConnectionConfiguration()).thenReturn(configuration);

        configuration.setProperty("yearIsDateType", "true");
        Assertions.assertEquals(Types.DATE, server.resolveDataTypeValueType("year", 1038, null, 2, Types.OTHER));

        configuration.setProperty("yearIsDateType", "false");
        Assertions.assertEquals(Types.SMALLINT, server.resolveDataTypeValueType("year", 1038, null, 2, Types.OTHER));
    }

    @Test
    public void selectsReflectionPackageForActiveDriver() {
        PostgreServerGaussDB server = new PostgreServerGaussDB(dataSource);
        DBPDataSourceContainer container = Mockito.mock(DBPDataSourceContainer.class);
        DBPDriver driver = Mockito.mock(DBPDriver.class);
        Mockito.when(dataSource.getContainer()).thenReturn(container);
        Mockito.when(container.getDriver()).thenReturn(driver);

        Mockito.when(driver.getDriverClassName()).thenReturn("org.postgresql.Driver");
        Assertions.assertEquals("org.postgresql", server.getJDBCDriverPackage());

        Mockito.when(driver.getDriverClassName()).thenReturn("com.huawei.gaussdb.jdbc.Driver");
        Assertions.assertEquals("com.huawei.gaussdb.jdbc", server.getJDBCDriverPackage());
    }

    @Test
    public void mapsPostgreSQLNativeToolNamesToGaussDBTools() {
        PostgreServerGaussDB server = new PostgreServerGaussDB(dataSource);

        Assertions.assertEquals("gsql", server.getNativeToolName("psql"));
        Assertions.assertEquals("gs_dump", server.getNativeToolName("pg_dump"));
        Assertions.assertEquals("gs_restore", server.getNativeToolName("pg_restore"));
        Assertions.assertEquals("gs_dumpall", server.getNativeToolName("pg_dumpall"));
        Assertions.assertEquals("custom_tool", server.getNativeToolName("custom_tool"));
    }
}
