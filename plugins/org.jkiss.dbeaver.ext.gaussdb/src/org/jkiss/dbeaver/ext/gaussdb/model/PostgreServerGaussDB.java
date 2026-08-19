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
import org.jkiss.dbeaver.ext.gaussdb.GaussDBConstants;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDatabase;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDialect;
import org.jkiss.dbeaver.ext.postgresql.model.impls.PostgreServerExtensionBase;

public class PostgreServerGaussDB extends PostgreServerExtensionBase {

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
        // GaussDB has gs_dump/gs_restore, but support is not yet implemented
        return false;
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
        // GaussDB supports range/list/hash partitioning
        return true;
    }

    @Override
    public boolean supportsFunctionDefRead() {
        // GaussDB supports pg_get_functiondef()
        return true;
    }

    @Override
    public boolean supportsEventTriggers() {
        // GaussDB does not support event triggers
        return false;
    }

    @Override
    public boolean supportsRowLevelSecurity() {
        // GaussDB does not support RLS in most versions
        return false;
    }

    @Override
    public boolean supportsForeignServers() {
        // GaussDB supports foreign servers (postgres_fdw equivalent)
        return true;
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
        // GaussDB does not support XML format EXPLAIN
        return false;
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
        // GaussDB default is standard_conforming_strings = on
        return false;
    }

    @Override
    public boolean supportsGeneratedColumns() {
        // GaussDB does not support generated columns in most versions
        return false;
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
        // No RLS, so no BYPASSRLS
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
    public boolean isPGObject(@NotNull Object object) {
        // GaussDB uses PG driver in compatibility mode, so PG objects may appear
        String className = object.getClass().getName();
        return "org.postgresql.util.PGobject".equals(className);
    }

    @Override
    public void configureDialect(@NotNull PostgreDialect dialect) {
        // Add GaussDB-specific keywords to the dialect
        for (String keyword : GaussDBConstants.GAUSSDB_EXTRA_KEYWORDS) {
            dialect.addExtraKeywords(keyword);
        }
    }

    @NotNull
    @Override
    public PostgreDatabase.SchemaCache createSchemaCache(@NotNull PostgreDatabase database) {
        return new GaussDBSchemaCache();
    }
}
