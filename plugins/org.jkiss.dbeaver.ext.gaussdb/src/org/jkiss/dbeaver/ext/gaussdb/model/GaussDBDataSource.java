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
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.postgresql.PostgreConstants;
import org.jkiss.dbeaver.ext.postgresql.model.*;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;

public class GaussDBDataSource extends PostgreDataSource {

    private PostgreServerExtension serverExtension;

    public GaussDBDataSource(DBRProgressMonitor monitor, DBPDataSourceContainer container) throws DBException {
        super(monitor, container, new GaussDBDialect());
    }

    @Override
    public void initialize(@NotNull DBRProgressMonitor monitor) throws DBException {
        super.initialize(monitor);
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

    /**
     * GaussDB version numbers (e.g. 8.x) do not align with PostgreSQL version numbers.
     * The base class and PG model code call isServerVersionAtLeast with PG version expectations
     * (e.g. isServerVersionAtLeast(9,3) for materialized views).
     *
     * Since GaussDB is based on PG 9.2/10/12 internals depending on the version, and most
     * features checked by version here are either always supported or always unsupported
     * (handled by PostgreServerGaussDB overrides), we return true for most version checks
     * to ensure the PG model code does not skip features that GaussDB actually supports.
     *
     * The accurate feature gating is done in PostgreServerGaussDB.supports* overrides.
     */
    @Override
    public boolean isServerVersionAtLeast(int major, int minor) {
        // GaussDB supports all features that PG checks via version >= 8.x
        // For very old PG version checks (< 8), be conservative
        if (major < 8) {
            return true;
        }
        if (major < 9) {
            return true;
        }
        // For PG 9.x+ feature checks, GaussDB based on modern PG core supports them
        // (materialized views, event triggers, partitions, etc. are gated by
        // PostgreServerGaussDB overrides, not by this method)
        return true;
    }

    @Override
    public PostgreServerExtension getServerType() {
        if (serverExtension == null) {
            serverExtension = new PostgreServerGaussDB(this);
        }
        return serverExtension;
    }
}
