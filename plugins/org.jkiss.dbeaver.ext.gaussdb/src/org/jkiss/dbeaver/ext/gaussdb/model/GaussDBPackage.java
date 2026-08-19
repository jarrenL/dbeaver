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
import org.jkiss.dbeaver.model.DBPSystemInfoObject;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GaussDBPackage implements PostgreObject, PostgreScriptObject, DBPSystemInfoObject {

    private static final Log log = Log.getLog(GaussDBPackage.class);
    private final GaussDBSchema schema;
    private long oid;
    private String name;
    private String description;
    private String sourceDeclaration = "";
    private String sourceDefinition = "";

    /**
     * Reuses the schema's procedures cache instead of maintaining a separate copy.
     */
    public GaussDBPackage(@NotNull JDBCSession session, @NotNull GaussDBSchema schema, @NotNull JDBCResultSet dbResult) {
        this.schema = schema;
        this.oid = JDBCUtils.safeGetLong(dbResult, "oid");
        this.name = JDBCUtils.safeGetString(dbResult, "name");
        initialize(session, oid);
    }

    public GaussDBPackage(GaussDBSchema schema, DBRProgressMonitor unusedMonitor, String name) {
        this.schema = schema;
        this.name = name;
    }

    private void initialize(JDBCSession session, long objectId) {
        JDBCPreparedStatement prepareStatement;
        try {
            prepareStatement = session
                .prepareStatement("select pkg.src from DBE_PLDEVELOPER.gs_source pkg where pkg.id = ? and type = ?");
            prepareStatement.setLong(1, objectId);
            prepareStatement.setString(2, "package");
            JDBCResultSet dbResult = prepareStatement.executeQuery();
            if (dbResult.nextRow()) {
                this.sourceDeclaration = JDBCUtils.safeGetString(dbResult, "src");
            }
            prepareStatement.setString(2, "package body");
            dbResult = prepareStatement.executeQuery();
            if (dbResult.nextRow()) {
                this.sourceDefinition = JDBCUtils.safeGetString(dbResult, "src");
            }
        } catch (SQLException | DBCException e) {
            log.error(e);
        }
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
        return true;
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
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        if (CommonUtils.isEmpty(sourceDefinition)) {
            return sourceDeclaration;
        }
        return sourceDeclaration.trim() + "\n" + sourceDefinition;
    }

    @Property(hidden = true, editable = true, updatable = true, order = -1)
    public String getObjectDefinitionText() {
        return sourceDeclaration;
    }

    @Override
    public void setObjectDefinitionText(String sourceText) {
        sourceDeclaration = sourceText;
    }

    @Property(hidden = true, editable = true, updatable = true, order = -1)
    public String getExtendedDefinitionText() {
        return sourceDefinition;
    }

    public void setExtendedDefinitionText(String source) {
        this.sourceDefinition = source;
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
    public List<GaussDBProcedure> getPackageFunctions(DBRProgressMonitor monitor) throws DBException {
        if (oid == 0) {
            return new ArrayList<>();
        }
        return schema.getGaussDBProceduresCache().getAllObjects(monitor, schema).stream()
            .filter(e -> e.getPropackageid() == oid && e.getKind() == PostgreProcedureKind.f)
            .collect(Collectors.toList());
    }
}
