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
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.postgresql.PostgreConstants;
import org.jkiss.dbeaver.ext.postgresql.model.*;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;

public class GaussDBDataSource extends PostgreDataSource {
    private static final Log log = Log.getLog(GaussDBDataSource.class);

    /**
     * GaussDB's catalog is derived from PostgreSQL but its product version is not a PostgreSQL version.
     * Use the oldest catalog level required by this plugin and never opt into newer PostgreSQL catalog SQL
     * (notably the PostgreSQL 10 partition columns, which GaussDB does not expose in pg_class).
     */
    private static final int POSTGRESQL_CATALOG_COMPATIBILITY_MAJOR = 9;
    private static final int POSTGRESQL_CATALOG_COMPATIBILITY_MINOR = 2;

    private PostgreServerExtension serverExtension;
    private volatile GaussDBServerInfo serverInfo;

    public GaussDBDataSource(DBRProgressMonitor monitor, DBPDataSourceContainer container) throws DBException {
        super(monitor, container, new GaussDBDialect());
        ((GaussDBDialect) getSQLDialect()).setDataSource(this);
    }

    @Override
    protected void initializeRemoteInstance(@NotNull DBRProgressMonitor monitor) throws DBException {
        super.initializeRemoteInstance(monitor);
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read GaussDB server information")) {
            session.enableLogging(false);
            serverInfo = GaussDBServerInfo.read(session);
        } catch (Exception e) {
            log.debug("Error reading GaussDB server information", e);
            serverInfo = GaussDBServerInfo.unknown();
        }
    }

    @NotNull
    public GaussDBServerInfo getServerInfo() {
        GaussDBServerInfo info = serverInfo;
        return info == null ? GaussDBServerInfo.unknown() : info;
    }

    @NotNull
    @Override
    public GaussDBDatabase createDatabaseImpl(@NotNull DBRProgressMonitor monitor, ResultSet dbResult) throws DBException {
        return new GaussDBDatabase(monitor, this, dbResult);
    }

    @NotNull
    @Override
    public GaussDBDatabase createDatabaseImpl(@NotNull DBRProgressMonitor monitor, String name) throws DBException {
        return new GaussDBDatabase(monitor, this, name);
    }

    @NotNull
    @Override
    public GaussDBDatabase createDatabaseImpl(DBRProgressMonitor monitor, String name, PostgreRole owner, String templateName,
        PostgreTablespace tablespace, PostgreCharset encoding) throws DBException {
        return new GaussDBDatabase(monitor, this, name, owner, templateName, tablespace, encoding);
    }

    // True if we need multiple databases
    @Override
    protected boolean isReadDatabaseList(DBPConnectionConfiguration configuration) {
        // It is configurable by default
        return CommonUtils.getBoolean(configuration.getProviderProperty(PostgreConstants.PROP_SHOW_NON_DEFAULT_DB), true);
    }

    @Override
    public boolean isServerVersionAtLeast(int major, int minor) {
        return major < POSTGRESQL_CATALOG_COMPATIBILITY_MAJOR ||
            major == POSTGRESQL_CATALOG_COMPATIBILITY_MAJOR && minor <= POSTGRESQL_CATALOG_COMPATIBILITY_MINOR;
    }

    @Override
    public PostgreServerExtension getServerType() {
        if (serverExtension == null) {
            serverExtension = new PostgreServerGaussDB(this);
        }
        return serverExtension;
    }
}
