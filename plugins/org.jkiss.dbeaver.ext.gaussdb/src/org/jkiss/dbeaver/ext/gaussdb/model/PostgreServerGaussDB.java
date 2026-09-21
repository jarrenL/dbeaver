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
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.gaussdb.GaussDBConstants;
import org.jkiss.dbeaver.ext.postgresql.PostgreConstants;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDatabase;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDialect;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreSetting;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreClass;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreSchema;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreTableBase;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreTypeCategory;
import org.jkiss.dbeaver.ext.postgresql.model.impls.PostgreServerExtensionBase;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPNativeClientLocation;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.io.File;
import java.sql.Types;
import java.util.Locale;
import java.util.Map;

public class PostgreServerGaussDB extends PostgreServerExtensionBase {
    private static final Log log = Log.getLog(PostgreServerGaussDB.class);

    private boolean supportJobs;

    protected PostgreServerGaussDB(PostgreDataSource dataSource) {
        super(dataSource);
        this.supportJobs = false;
    }

    @NotNull
    @Override
    public String getServerTypeName() {
        return "GaussDB";
    }

    @Override
    public boolean supportsNativeClient() {
        return true;
    }

    @Override
    public boolean supportsNativeBackupAll() {
        return true;
    }

    @Override
    public boolean supportsNativeBackupAllPasswordSuppression() {
        return false;
    }

    @Override
    public boolean supportsNativeBackupAllDatabaseFilter() {
        return false;
    }

    @Override
    public boolean supportsNativeToolStreaming() {
        // gs_dump requires --file for archive formats and gs_restore requires a filename.
        return false;
    }

    @Override
    public boolean usesNativePasswordPipe() {
        return true;
    }

    @Override
    public void configureNativeToolEnvironment(
        @NotNull DBPNativeClientLocation clientHome,
        @NotNull Map<String, String> environment
    ) {
        File home = clientHome.getPath();
        if (GaussDBConstants.BIN_FOLDER.equals(home.getName()) && home.getParentFile() != null) {
            home = home.getParentFile();
        }
        File libraryFolder = new File(home, "lib");
        if (libraryFolder.isDirectory()) {
            prependPath(environment, "LD_LIBRARY_PATH", libraryFolder);
            prependPath(environment, "DYLD_LIBRARY_PATH", libraryFolder);
        }
    }

    private static void prependPath(
        @NotNull Map<String, String> environment,
        @NotNull String variable,
        @NotNull File path
    ) {
        String current = environment.get(variable);
        environment.put(variable, path.getAbsolutePath() +
            (CommonUtils.isEmpty(current) ? "" : File.pathSeparator + current));
    }

    public boolean isSupportJobs() {
        return supportJobs;
    }

    public void setSupportJobs(boolean supportJobs) {
        this.supportJobs = supportJobs;
    }

    @Override
    public boolean supportsJobs() {
        return supportJobs;
    }

    @Override
    public boolean supportsExtensions() {
        // GaussDB does not support PG extensions in the traditional sense
        return false;
    }

    @Override
    public boolean supportsStoredProcedures() {
        return true;
    }

    @Override
    public boolean supportsMaterializedViews() {
        return true;
    }

    @Override
    public boolean supportsPartitions() {
        return getServerInfo().hasRelation("pg_partition");
    }

    @Override
    public boolean supportsTriggers() {
        return true;
    }

    @Override
    public boolean supportsFunctionCreate() {
        return true;
    }

    @Override
    public boolean supportsRules() {
        return false;
    }

    @Override
    public boolean supportsAggregates() {
        return true;
    }

    @Override
    public boolean supportsFunctionDefRead() {
        return getServerInfo().hasFunction("pg_get_functiondef");
    }

    @Override
    public boolean supportsEventTriggers() {
        // GaussDB does not support event triggers
        return false;
    }

    @Override
    public boolean supportsRowLevelSecurity() {
        return getServerInfo().hasRelation("pg_rlspolicies");
    }

    @NotNull
    @Override
    public String getTablePoliciesQuery() {
        return "SELECT policyname,policyroles AS roles,policypermissive AS permissive," +
            "policycmd AS cmd,policyqual AS qual,NULL::text AS with_check " +
            "FROM pg_catalog.pg_rlspolicies WHERE schemaname=? AND tablename=?";
    }

    @Override
    public boolean supportsPolicyWithCheck() {
        return false;
    }

    @Override
    public boolean supportsPolicyInsertEvent() {
        return false;
    }

    @Override
    public boolean supportsForeignServers() {
        return !getServerInfo().isMCompatibility() && getServerInfo().hasRelation("pg_foreign_server");
    }

    @Override
    public boolean supportsSessionActivity() {
        return getServerInfo().hasRelation("pg_stat_activity");
    }

    @Override
    public boolean supportsLocks() {
        return getServerInfo().hasRelation("pg_locks");
    }

    @Override
    public boolean supportsEntityMetadataInResults() {
        // GaussDB JDBC driver returns source table name in ResultSetMetaData
        return true;
    }

    @Override
    public boolean supportsExplainPlan() {
        return true;
    }

    @Override
    public boolean supportsExplainPlanXML() {
        return true;
    }

    @Override
    public boolean supportsExplainPlanVerbose() {
        return true;
    }

    @Override
    public boolean supportsPGConstraintExpressionColumn() {
        return true;
    }

    @Override
    public boolean supportsHasOidsColumn() {
        return true;
    }

    @Override
    public boolean supportsBackslashStringEscape() {
        final PostgreSetting setting = dataSource.getSetting(PostgreConstants.OPTION_STANDARD_CONFORMING_STRINGS);
        return setting != null && "off".equals(setting.getValue());
    }

    @Override
    public boolean supportsGeneratedColumns() {
        return getServerInfo().hasColumn("pg_attrdef", "adgencol");
    }

    @Override
    public boolean supportsCopyFromStdIn() {
        // GaussDB supports COPY FROM STDIN
        return true;
    }

    @Override
    public boolean supportsDatabaseSize() {
        return true;
    }

    @Override
    public boolean supportsAlterUserChangePassword() {
        return true;
    }

    @Override
    public boolean supportsRoleReplication() {
        // GaussDB supports replication roles
        return true;
    }

    @Override
    public boolean supportsRoleBypassRLS() {
        // Generic PostgreSQL RLS metadata is intentionally disabled above.
        return false;
    }

    @Override
    public boolean supportsDefaultPrivileges() {
        return true;
    }

    @Override
    public boolean supportsCommentsOnRole() {
        return true;
    }

    @Override
    public boolean supportsDistinctForStatementsWithAcl() {
        return true;
    }

    @Override
    public boolean supportsOpFamily() {
        return true;
    }

    @Override
    public boolean supportsAlterTableColumnWithUSING() {
        return true;
    }

    @Override
    public boolean isAlterTableAtomic() {
        // GaussDB does not guarantee atomic ALTER TABLE
        return false;
    }

    @Override
    public boolean supportsAcl() {
        return true;
    }

    @Override
    public boolean supportsCustomDataTypes() {
        return true;
    }

    @Override
    public boolean supportsInsertOnConflict() {
        return getServerInfo().getCompatibility() == DBCompatibilityEnum.POSTGRES;
    }

    @Override
    public int resolveDataTypeValueType(
        @NotNull String typeName,
        long typeId,
        @Nullable PostgreTypeCategory typeCategory,
        int typeLength,
        int defaultValueType
    ) {
        return switch (typeName.toLowerCase(Locale.ENGLISH)) {
            case "int1", "tinyint" -> Types.TINYINT;
            case "uint1" -> Types.SMALLINT;
            case "uint2", "mediumint" -> Types.INTEGER;
            case "uint4" -> Types.BIGINT;
            case "uint8", "int16", "number" -> Types.NUMERIC;
            case "year" -> isYearDateType() ? Types.DATE : Types.SMALLINT;
            case "datetime", "smalldatetime" -> Types.TIMESTAMP;
            case "binary" -> Types.BINARY;
            case "varbinary", "raw" -> Types.VARBINARY;
            case "blob" -> Types.BLOB;
            case "clob", "nclob" -> Types.CLOB;
            case "tinytext", "mediumtext", "longtext" -> Types.LONGVARCHAR;
            case "enum", "set" -> Types.VARCHAR;
            default -> defaultValueType;
        };
    }

    @Override
    public boolean isPGObject(@NotNull Object object) {
        String className = object.getClass().getName();
        return "org.postgresql.util.PGobject".equals(className) ||
            "com.huawei.gaussdb.jdbc.util.PGobject".equals(className);
    }

    @Override
    public boolean isPGArray(@NotNull Object object) {
        String className = object.getClass().getName();
        return "org.postgresql.jdbc.PgArray".equals(className) ||
            "com.huawei.gaussdb.jdbc.jdbc.PgArray".equals(className);
    }

    @Override
    public boolean isPSQLException(@NotNull Throwable error) {
        String className = error.getClass().getName();
        return "org.postgresql.util.PSQLException".equals(className) ||
            "com.huawei.gaussdb.jdbc.util.PSQLException".equals(className);
    }

    @Override
    public boolean isPSQLWarning(@NotNull Throwable warning) {
        String className = warning.getClass().getName();
        return "org.postgresql.util.PSQLWarning".equals(className) ||
            "com.huawei.gaussdb.jdbc.util.PSQLWarning".equals(className);
    }

    @NotNull
    @Override
    public String getJDBCDriverPackage() {
        return isNativeDriver() ? "com.huawei.gaussdb.jdbc" : "org.postgresql";
    }

    @NotNull
    @Override
    public String getNativeToolName(@NotNull String postgreSQLToolName) {
        return switch (postgreSQLToolName) {
            case "psql" -> "gsql";
            case "pg_dump" -> "gs_dump";
            case "pg_restore" -> "gs_restore";
            case "pg_dumpall" -> "gs_dumpall";
            default -> postgreSQLToolName;
        };
    }

    @Nullable
    @Override
    public String readTableDDL(@NotNull DBRProgressMonitor monitor, @NotNull PostgreTableBase table)
        throws DBException {
        if (!getServerInfo().hasFunction("pg_get_tabledef")) {
            return null;
        }
        try (JDBCSession session = DBUtils.openMetaSession(monitor, table, "Read GaussDB table definition");
             JDBCPreparedStatement statement = session.prepareStatement(
                 "SELECT pg_catalog.pg_get_tabledef(?::oid::regclass)")) {
            statement.setLong(1, table.getObjectId());
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        } catch (Exception e) {
            // The vendor function can be absent or restricted on older/low-privilege installations.
            // Returning null preserves the generic DBeaver DDL generator as a safe fallback.
            log.debug("Error reading GaussDB table definition", e);
            return null;
        }
    }

    @Nullable
    @Override
    public PostgreTableBase createRelationOfClass(
        @NotNull PostgreSchema schema,
        @NotNull PostgreClass.RelKind kind,
        @NotNull JDBCResultSet resultSet
    ) {
        if (kind == PostgreClass.RelKind.r && schema instanceof GaussDBSchema) {
            return new GaussDBTable(schema, resultSet);
        }
        return super.createRelationOfClass(schema, kind, resultSet);
    }

    @Override
    public void configureDialect(@NotNull PostgreDialect dialect) {
        dialect.addExtraKeywords(GaussDBConstants.GAUSSDB_EXTRA_KEYWORDS);
        if (dialect instanceof GaussDBDialect gaussDBDialect) {
            gaussDBDialect.addExtraDataTypes(GaussDBConstants.GAUSSDB_DATA_TYPES);
        }
        dialect.addExtraFunctions(GaussDBConstants.GAUSSDB_FUNCTIONS);
    }

    @NotNull
    @Override
    public PostgreDatabase.SchemaCache createSchemaCache(@NotNull PostgreDatabase database) {
        return new GaussDBSchemaCache();
    }

    private boolean isYearDateType() {
        DBPConnectionConfiguration configuration = dataSource.getContainer().getActualConnectionConfiguration();
        return configuration == null || CommonUtils.getBoolean(configuration.getProperty("yearIsDateType"), true);
    }

    private boolean isNativeDriver() {
        DBPDataSourceContainer container = dataSource.getContainer();
        return container != null && container.getDriver() != null &&
            GaussDBConstants.GAUSSDB_DRIVER_CLASS_NATIVE.equals(container.getDriver().getDriverClassName());
    }

    @NotNull
    private GaussDBServerInfo getServerInfo() {
        GaussDBServerInfo info = ((GaussDBDataSource) dataSource).getServerInfo();
        return info == null ? GaussDBServerInfo.unknown() : info;
    }
}
