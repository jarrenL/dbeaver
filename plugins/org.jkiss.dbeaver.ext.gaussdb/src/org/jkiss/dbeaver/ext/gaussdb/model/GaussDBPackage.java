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
import org.jkiss.dbeaver.ext.postgresql.model.*;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBPQualifiedObject;
import org.jkiss.dbeaver.model.DBPRefreshableObject;
import org.jkiss.dbeaver.model.DBPStatefulObject;
import org.jkiss.dbeaver.model.DBPSystemInfoObject;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObjectState;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class GaussDBPackage implements PostgreObject, PostgreScriptObject, DBPSystemInfoObject,
    DBPQualifiedObject, DBPStatefulObject, DBPRefreshableObject {

    private static final Log log = Log.getLog(GaussDBPackage.class);
    private static final Set<String> LOGGED_OPTIONAL_SOURCE_ERRORS = ConcurrentHashMap.newKeySet();
    private final GaussDBSchema schema;
    private long oid;
    private String name;
    private String description;
    private String sourceDeclaration = "";
    private String sourceDefinition = "";
    private volatile boolean sourceLoaded;
    private volatile DBSObjectState specificationState = DBSObjectState.UNKNOWN;
    private volatile DBSObjectState bodyState = DBSObjectState.UNKNOWN;
    private volatile boolean bodyPresent;

    /**
     * Creates a package descriptor from the cache row. Source text is loaded on demand.
     */
    public GaussDBPackage(@NotNull JDBCSession session, @NotNull GaussDBSchema schema, @NotNull JDBCResultSet dbResult) {
        this.schema = schema;
        this.oid = JDBCUtils.safeGetLong(dbResult, "oid");
        this.name = JDBCUtils.safeGetString(dbResult, "name");
        this.specificationState = readState(JDBCUtils.safeGetString(dbResult, "spec_valid"));
        this.bodyState = readState(JDBCUtils.safeGetString(dbResult, "body_valid"));
        this.bodyPresent = JDBCUtils.safeGetBoolean(dbResult, "body_present");
    }

    public GaussDBPackage(GaussDBSchema schema, DBRProgressMonitor unusedMonitor, String name) {
        this.schema = schema;
        this.name = name;
        this.sourceLoaded = true;
    }

    private void loadSource(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (sourceLoaded) {
            return;
        }
        synchronized (this) {
            if (sourceLoaded) {
                return;
            }
            if (oid == 0) {
                sourceLoaded = true;
                return;
            }

            try (JDBCSession session = openSourceSession(monitor)) {
                try (JDBCPreparedStatement statement = session.prepareStatement(
                    "select pkg.src from DBE_PLDEVELOPER.gs_source pkg where pkg.id = ? and type = ?")) {
                    statement.setLong(1, oid);
                    String declaration = readSource(statement, "package");
                    String definition = readSource(statement, "package body");
                    if (CommonUtils.isEmpty(declaration) && CommonUtils.isEmpty(definition)) {
                        loadSourceFromCatalog(session);
                    } else {
                        sourceDeclaration = declaration;
                        sourceDefinition = definition;
                        bodyPresent = !CommonUtils.isEmpty(definition);
                        sourceLoaded = true;
                    }
                } catch (SQLException e) {
                    if (handleOptionalSourceError(e)) {
                        loadSourceFromCatalog(session);
                        return;
                    }
                    throw new DBCException("Error reading GaussDB package source", e, session.getExecutionContext());
                }
            } catch (DBCException e) {
                if (handleOptionalSourceError(e)) {
                    return;
                }
                throw e;
            }
        }
    }

    private void loadSourceFromCatalog(@NotNull JDBCSession session) throws DBException {
        try (JDBCPreparedStatement statement = session.prepareStatement(
            "SELECT pkgspecsrc,pkgbodydeclsrc,pkgbodyinitsrc FROM pg_catalog.gs_package WHERE oid=?")) {
            statement.setLong(1, oid);
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    sourceDeclaration = wrapCatalogSource(
                        JDBCUtils.safeGetString(resultSet, "pkgspecsrc"), false);
                    String body = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "pkgbodydeclsrc"));
                    String initialization = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "pkgbodyinitsrc"));
                    sourceDefinition = wrapCatalogSource(
                        body + (CommonUtils.isEmpty(initialization) ? "" : "\n" + initialization), true);
                    bodyPresent = !CommonUtils.isEmpty(sourceDefinition);
                }
                sourceLoaded = true;
            }
        } catch (SQLException e) {
            if (!handleOptionalSourceError(e)) {
                throw new DBCException("Error reading GaussDB package catalog source", e, session.getExecutionContext());
            }
        }
    }

    @NotNull
    private String wrapCatalogSource(@Nullable String source, boolean body) {
        String text = CommonUtils.notEmpty(source).trim();
        if (text.isEmpty() || text.regionMatches(true, 0, "CREATE", 0, "CREATE".length())) {
            return text;
        }
        String qualifiedName = DBUtils.getObjectFullName(this, org.jkiss.dbeaver.model.DBPEvaluationContext.DDL);
        return "CREATE OR REPLACE PACKAGE " + (body ? "BODY " : "") + qualifiedName + " AS\n" +
            text + (text.endsWith(";") ? "" : "\nEND " + DBUtils.getQuotedIdentifier(this) + ";");
    }

    @NotNull
    JDBCSession openSourceSession(@NotNull DBRProgressMonitor monitor) throws DBCException {
        return DBUtils.openMetaSession(monitor, this, "Read GaussDB package source");
    }

    @NotNull
    private static String readSource(@NotNull JDBCPreparedStatement statement, @NotNull String sourceType)
        throws SQLException {
        statement.setString(2, sourceType);
        try (JDBCResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                String source = JDBCUtils.safeGetString(resultSet, "src");
                return source == null ? "" : source;
            }
            return "";
        }
    }

    private boolean handleOptionalSourceError(@NotNull Throwable error) {
        String sqlState = GaussDBMetadataErrorHandler.getOptionalMetadataSqlState(error);
        if (sqlState == null) {
            return false;
        }
        sourceLoaded = true;
        if (LOGGED_OPTIONAL_SOURCE_ERRORS.add(sqlState)) {
            log.debug("Optional GaussDB package source metadata is unavailable (SQLState " + sqlState + ")", error);
        }
        return true;
    }

    public GaussDBSchema getSchema() {
        return schema;
    }

    @Override
    public DBSObject getParentObject() {
        return schema;
    }

    @NotNull
    @Override
    public String getName() {
        return this.name;
    }

    @Property(viewable = true, order = 1)
    public String getPkgName() {
        return this.name;
    }

    @Override
    public String getDescription() {
        return this.description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean isPersisted() {
        return oid != 0;
    }

    @Override
    @Property(viewable = true, order = 2)
    public long getObjectId() {
        return this.oid;
    }

    public void setName(String name) {
        this.name = name;
    }

    @NotNull
    @Override
    public String getFullyQualifiedName(@NotNull DBPEvaluationContext context) {
        return DBUtils.getFullQualifiedName(getDataSource(), schema, this);
    }

    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        if (specificationState == DBSObjectState.INVALID || bodyPresent && bodyState == DBSObjectState.INVALID) {
            return DBSObjectState.INVALID;
        }
        if (specificationState == DBSObjectState.NORMAL && (!bodyPresent || bodyState == DBSObjectState.NORMAL)) {
            return DBSObjectState.NORMAL;
        }
        return DBSObjectState.UNKNOWN;
    }

    @Property(viewable = true, order = 3)
    @NotNull
    public DBSObjectState getSpecificationState() {
        return specificationState;
    }

    @Property(viewable = true, order = 4)
    @NotNull
    public DBSObjectState getBodyState() {
        return bodyPresent ? bodyState : DBSObjectState.UNKNOWN;
    }

    public boolean isBodyPresent() {
        return bodyPresent;
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) throws DBCException {
        if (!isPersisted()) {
            specificationState = DBSObjectState.UNKNOWN;
            bodyState = DBSObjectState.UNKNOWN;
            bodyPresent = !CommonUtils.isEmpty(sourceDefinition);
            return;
        }
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read GaussDB package state");
             JDBCPreparedStatement statement = session.prepareStatement(
                 "SELECT object_type::text,valid::text FROM pg_catalog.pg_object " +
                     "WHERE object_oid=? AND object_type IN ('S','B')")) {
            statement.setLong(1, oid);
            DBSObjectState newSpecificationState = DBSObjectState.UNKNOWN;
            DBSObjectState newBodyState = DBSObjectState.UNKNOWN;
            boolean newBodyPresent = false;
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String type = JDBCUtils.safeGetString(resultSet, "object_type");
                    DBSObjectState state = readState(JDBCUtils.safeGetString(resultSet, "valid"));
                    if ("S".equalsIgnoreCase(type)) {
                        newSpecificationState = state;
                    } else if ("B".equalsIgnoreCase(type)) {
                        newBodyPresent = true;
                        newBodyState = state;
                    }
                }
            }
            specificationState = newSpecificationState;
            bodyState = newBodyState;
            bodyPresent = newBodyPresent;
        } catch (SQLException e) {
            if (GaussDBMetadataErrorHandler.isOptionalMetadataError(e)) {
                specificationState = DBSObjectState.UNKNOWN;
                bodyState = DBSObjectState.UNKNOWN;
                return;
            }
            throw new DBCException("Error reading GaussDB package state", e);
        }
    }

    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        sourceDeclaration = "";
        sourceDefinition = "";
        sourceLoaded = false;
        refreshObjectState(monitor);
        return this;
    }

    private static DBSObjectState readState(@Nullable String value) {
        if (value == null) {
            return DBSObjectState.UNKNOWN;
        }
        return "t".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value)
            ? DBSObjectState.NORMAL
            : DBSObjectState.INVALID;
    }

    @NotNull
    @Override
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        loadSource(monitor);
        if (CommonUtils.isEmpty(sourceDefinition)) {
            return sourceDeclaration;
        }
        return sourceDeclaration.trim() + "\n" + sourceDefinition;
    }

    @NotNull
    public String getDeclarationText(@NotNull DBRProgressMonitor monitor) throws DBException {
        loadSource(monitor);
        return sourceDeclaration;
    }

    @NotNull
    public String getBodyText(@NotNull DBRProgressMonitor monitor) throws DBException {
        loadSource(monitor);
        return sourceDefinition;
    }

    @Property(hidden = true, editable = true, updatable = true, order = -1)
    public String getObjectDefinitionText() {
        return sourceDeclaration;
    }

    @Override
    public void setObjectDefinitionText(String sourceText) {
        sourceDeclaration = sourceText;
        sourceLoaded = true;
    }

    @Property(hidden = true, editable = true, updatable = true, order = -1)
    public String getExtendedDefinitionText() {
        return sourceDefinition;
    }

    public void setExtendedDefinitionText(String source) {
        this.sourceDefinition = source;
        this.bodyPresent = !CommonUtils.isEmpty(source);
        sourceLoaded = true;
    }

    @NotNull
    @Override
    public GaussDBDataSource getDataSource() {
        return (GaussDBDataSource) schema.getDataSource();
    }

    @NotNull
    @Override
    public GaussDBDatabase getDatabase() {
        return (GaussDBDatabase) schema.getDatabase();
    }

    @Association
    public List<GaussDBProcedure> getPackageProcedures(DBRProgressMonitor monitor) throws DBException {
        if (oid == 0) {
            return new ArrayList<>();
        }
        return schema.getGaussDBProceduresCache().getAllObjects(monitor, schema).stream()
            .filter(e -> e.getPropackageid() == oid && e.getKind() == PostgreProcedureKind.p)
            .collect(Collectors.toList());
    }

    @Association
    public List<GaussDBFunction> getPackageFunctions(DBRProgressMonitor monitor) throws DBException {
        if (oid == 0) {
            return new ArrayList<>();
        }
        return schema.getGaussDBFunctionsCache().getAllObjects(monitor, schema).stream()
            .filter(e -> e.getPropackageid() == oid && e.getKind() == PostgreProcedureKind.f)
            .collect(Collectors.toList());
    }
}
