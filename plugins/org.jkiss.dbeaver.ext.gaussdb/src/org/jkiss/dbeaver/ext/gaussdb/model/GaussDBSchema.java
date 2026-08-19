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
import org.jkiss.dbeaver.ext.gaussdb.GaussDBConstants;
import org.jkiss.dbeaver.ext.postgresql.model.*;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement;
import org.jkiss.dbeaver.model.impl.jdbc.cache.JDBCObjectCache;
import org.jkiss.dbeaver.model.impl.jdbc.cache.JDBCObjectLookupCache;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;


public class GaussDBSchema extends PostgreSchema {

    public final PackageCache packageCache;
    private final ProceduresCache proceduresCache;
    private final FunctionsCache functionsCache;
    private final ConstraintCache constraintCache;

    public GaussDBSchema(PostgreDatabase owner, String name, JDBCResultSet resultSet) throws SQLException {
        super(owner, name, resultSet);
        this.packageCache = new PackageCache();
        this.proceduresCache = new ProceduresCache();
        this.functionsCache = new FunctionsCache();
        this.constraintCache = new ConstraintCache();
    }

    public GaussDBSchema(PostgreDatabase database, String name, PostgreRole owner) {
        super(database, name, owner);
        this.packageCache = new PackageCache();
        this.proceduresCache = new ProceduresCache();
        this.functionsCache = new FunctionsCache();
        this.constraintCache = new ConstraintCache();
    }

    @Override
    public boolean isSystem() {
        String lowerName = this.name.toLowerCase(Locale.ENGLISH);
        if ("public".equals(lowerName)) {
            return false;
        }
        if ("dbe_perf".equals(lowerName) || "dbe_pldeveloper".equals(lowerName) || "mls".equals(lowerName)) {
            return true;
        }
        return this.oid < 16384;
    }

    @Override
    public boolean isUtility() {
        String lowerName = this.name.toLowerCase(Locale.ENGLISH);
        return "information_schema".equals(lowerName) || "dbe_perf".equals(lowerName);
    }

    public static boolean isUtilitySchema(String schema) {
        if (schema == null) {
            return false;
        }
        String lower = schema.toLowerCase(Locale.ENGLISH);
        return "information_schema".equals(lower) || "dbe_perf".equals(lower);
    }

    public ProceduresCache getGaussDBProceduresCache() {
        return this.proceduresCache;
    }

    public FunctionsCache getGaussDBFunctionsCache() {
        return this.functionsCache;
    }

    @Association
    public List<GaussDBPackage> getPackages(DBRProgressMonitor monitor) throws DBException {
        return packageCache.getAllObjects(monitor, this);
    }

    @Association
    public List<GaussDBProcedure> getGaussDBProcedures(DBRProgressMonitor monitor) throws DBException {
        return getGaussDBProceduresCache().getAllObjects(monitor, this).stream()
            .filter(e -> e.getPropackageid() == 0 && e.getKind() == PostgreProcedureKind.p)
            .collect(Collectors.toList());
    }

    @Association
    public List<GaussDBFunction> getGaussDBFunctions(DBRProgressMonitor monitor) throws DBException {
        return getGaussDBFunctionsCache().getAllObjects(monitor, this).stream()
            .filter(e -> e.getPropackageid() == 0 && e.getKind() == PostgreProcedureKind.f)
            .collect(Collectors.toList());
    }

    // ---- Shared SQL builder for procedure/function lookup ----

    /**
     * Build the common SQL query for looking up procedures and functions from pg_proc.
     * Both ProceduresCache and FunctionsCache use this to avoid code duplication.
     */
    static JDBCPreparedStatement buildProceduresLookupStatement(
        @NotNull JDBCSession session,
        @NotNull PostgreSchema owner,
        @Nullable PostgreProcedure object
    ) throws SQLException {
        PostgreServerExtension serverType = owner.getDataSource().getServerType();
        String oidColumn = serverType.getProceduresOidColumn();
        JDBCPreparedStatement dbStat = session.prepareStatement(
            "SELECT p." + oidColumn + " as poid,p.*,"
                + (session.getDataSource().isServerVersionAtLeast(8, 4) ? "pg_catalog.pg_get_expr(p.proargdefaults, 0)" : "NULL")
                + " as arg_defaults,d.description\n"
                + "FROM pg_catalog." + serverType.getProceduresSystemTable() + " p\n"
                + "LEFT OUTER JOIN pg_catalog.pg_description d ON d.objoid=p." + oidColumn
                + (session.getDataSource().isServerVersionAtLeast(7, 2) ? " AND d.objsubid = 0" : "")
                + "\nWHERE p.pronamespace=?"
                + (object == null ? "" : " AND p." + oidColumn + "=?")
                + "\nORDER BY p.proname"
        );
        dbStat.setLong(1, owner.getObjectId());
        if (object != null) {
            dbStat.setLong(2, object.getObjectId());
        }
        return dbStat;
    }

    class PackageCache extends JDBCObjectCache<GaussDBSchema, GaussDBPackage> {

        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session,
            @NotNull GaussDBSchema owner) throws SQLException {
            final JDBCPreparedStatement dbStat = session
                .prepareStatement("select g.oid, g.pkgnamespace, g.pkgname as name from gs_package g where g.pkgnamespace = ?");
            dbStat.setLong(1, GaussDBSchema.this.getObjectId());
            return dbStat;
        }

        @Override
        protected GaussDBPackage fetchObject(@NotNull JDBCSession session, @NotNull GaussDBSchema owner,
            @NotNull JDBCResultSet dbResult) throws SQLException, DBException {
            return new GaussDBPackage(session, owner, dbResult);
        }
    }

    public static class ProceduresCache extends JDBCObjectLookupCache<GaussDBSchema, GaussDBProcedure> {

        public ProceduresCache() {
            super();
        }

        @NotNull
        @Override
        public JDBCStatement prepareLookupStatement(@NotNull JDBCSession session, @NotNull GaussDBSchema owner,
            @Nullable GaussDBProcedure object, @Nullable String objectName) throws SQLException {
            return buildProceduresLookupStatement(session, owner, object);
        }

        @Override
        protected GaussDBProcedure fetchObject(@NotNull JDBCSession session, @NotNull GaussDBSchema owner,
            @NotNull JDBCResultSet dbResult) throws SQLException, DBException {
            return new GaussDBProcedure(session.getProgressMonitor(), owner, dbResult);
        }
    }

    public static class FunctionsCache extends JDBCObjectLookupCache<GaussDBSchema, GaussDBFunction> {

        public FunctionsCache() {
            super();
        }

        @NotNull
        @Override
        public JDBCStatement prepareLookupStatement(@NotNull JDBCSession session, @NotNull GaussDBSchema owner,
            @Nullable GaussDBFunction object, @Nullable String objectName) throws SQLException {
            return buildProceduresLookupStatement(session, owner, object);
        }

        @Override
        protected GaussDBFunction fetchObject(@NotNull JDBCSession session, @NotNull GaussDBSchema owner,
            @NotNull JDBCResultSet dbResult) throws SQLException, DBException {
            return new GaussDBFunction(session.getProgressMonitor(), owner, dbResult);
        }
    }

    public class ConstraintCache extends PostgreSchema.ConstraintCache {
        protected ConstraintCache() {}

        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@Nullable JDBCSession session, @Nullable PostgreTableContainer container,
                                                        @Nullable PostgreTableBase forParent) throws SQLException {
            StringBuilder sql = new StringBuilder(
                "SELECT c.oid,c.*,t.relname as tabrelname,rt.relnamespace as refnamespace,d.description" +
                    (!getDataSource().getServerType().supportsPGConstraintExpressionColumn() ? ", null as consrc_copy" :
                        ", case when c.contype='c' then " + (isMMode(container) ? "substring" : "\"substring\"") +
                            "(pg_get_constraintdef(c.oid), 7) else null end consrc_copy") +
                    "\nFROM pg_catalog.pg_constraint c" +
                    "\nINNER JOIN pg_catalog.pg_class t ON t.oid=c.conrelid" +
                    "\nLEFT OUTER JOIN pg_catalog.pg_class rt ON rt.oid=c.confrelid" +
                    "\nLEFT OUTER JOIN " +
                    "pg_catalog.pg_description d ON d.objoid=c.oid AND d.objsubid=0 AND d.classoid='pg_constraint'::regclass" +
                    "\nWHERE ");
            if (forParent == null) {
                sql.append("t.relnamespace=?");
            } else {
                sql.append("c.conrelid=?");
            }
            sql.append("\nORDER BY c.oid");
            JDBCPreparedStatement dbStat = session.prepareStatement(sql.toString());
            if (forParent == null) {
                dbStat.setLong(1, container.getSchema().getObjectId());
            } else {
                dbStat.setLong(1, forParent.getObjectId());
            }
            return dbStat;
        }
    }

    @Override
    public ConstraintCache getConstraintCache() {
        return this.constraintCache;
    }

    /**
     * Check if the database is in MySQL "M" compatibility mode.
     * In M mode, "substring" is a keyword and must not be quoted.
     */
    private boolean isMMode(@NotNull PostgreTableContainer tableContainer) {
        GaussDBDatabase database = (GaussDBDatabase) tableContainer.getDatabase();
        String compatibilityMode = database.getDatabaseCompatibleMode();
        if (GaussDBConstants.GAUSSDB_M_COMPATIBLE_MODE.equals(compatibilityMode)) {
            return true;
        }
        // Also check for the distributed MySQL value
        return GaussDBConstants.GAUSSDB_MYSQL_COMPATIBLE_MODE.equalsIgnoreCase(compatibilityMode)
            || GaussDBConstants.GAUSSDB_MYSQL_COMPATIBLE_MODE_C.equalsIgnoreCase(compatibilityMode);
    }
}
