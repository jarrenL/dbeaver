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
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.gaussdb.GaussDBConstants;
import org.jkiss.dbeaver.ext.postgresql.PostgreUtils;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataType;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreLanguage;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreProcedure;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreProcedureKind;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreSchema;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GaussDBProcedure extends PostgreProcedure {

    private static final Log log = Log.getLog(GaussDBProcedure.class);
    private static final String DEFAULT_ROUTINE_BODY = "\n\t-- Enter routine body here\n";

    public long propackageid;
    public String prokind;
    public String procSrc;

    public long getPropackageid() {
        return propackageid;
    }

    public GaussDBProcedure(PostgreSchema schema) {
        super(schema);
        setKind(PostgreProcedureKind.p);
    }

    public GaussDBProcedure(DBRProgressMonitor monitor, PostgreSchema schema, ResultSet dbResult) {
        super(monitor, schema, dbResult);
        this.propackageid = JDBCUtils.safeGetLong(dbResult, "propackageid");
        this.procSrc = JDBCUtils.safeGetString(dbResult, "prosrc");
    }

    @NotNull
    @Override
    @Property(hidden = true, editable = true, updatable = true, order = -1)
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        if (isAggregate()) {
            return super.getObjectDefinitionText(monitor, options);
        }

        boolean omitHeader = CommonUtils.getOption(options, OPTION_DEBUGGER_SOURCE);
        String procDDL = omitHeader || CommonUtils.getOption(options, OPTION_SKIP_DROPS) ?
            "" :
            "-- DROP " + getProcedureTypeName() + " " + getFullQualifiedSignature() + ";\n\n";

        if (isPersisted() && (!getDataSource().getServerType().supportsFunctionDefRead() || omitHeader)) {
            procDDL = readProcedureSource(monitor, omitHeader, procDDL);
        } else {
            procDDL = readFunctionDefinition(monitor, procDDL);
        }

        if (this.isPersisted() && !omitHeader) {
            String trimmedDDL = procDDL.stripTrailing();
            if (!trimmedDDL.endsWith(";") && !trimmedDDL.endsWith("/")) {
                procDDL += ";\n";
            } else if (!procDDL.endsWith("\n")) {
                procDDL += "\n";
            }

            if (CommonUtils.getOption(options, DBPScriptObject.OPTION_INCLUDE_COMMENTS) && !CommonUtils.isEmpty(getDescription())) {
                procDDL += "\nCOMMENT ON " + getProcedureTypeName() + " " + getFullQualifiedSignature() + " IS "
                    + SQLUtils.quoteString(this, getDescription()) + ";\n";
            }

            if (CommonUtils.getOption(options, DBPScriptObject.OPTION_INCLUDE_PERMISSIONS)) {
                List<DBEPersistAction> actions = new ArrayList<>();
                PostgreUtils.getObjectGrantPermissionActions(monitor, this, actions, options);
                procDDL += "\n" + SQLUtils.generateScript(getDataSource(), actions.toArray(new DBEPersistAction[0]), false);
            }
        }

        return procDDL;
    }

    /**
     * Read procedure body from prosrc column directly.
     * Used when pg_get_functiondef() is not available or not reliable.
     */
    private String readProcedureSource(@NotNull DBRProgressMonitor monitor, boolean omitHeader, @NotNull String procDDL) throws DBCException, DBException {
        if (procSrc == null) {
            try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read procedure body")) {
                procSrc = JDBCUtils.queryString(session, "SELECT prosrc FROM pg_catalog.pg_proc WHERE oid = ?", getObjectId());
            } catch (SQLException e) {
                throw new DBException("Error reading procedure body", e);
            }
        }
        PostgreDataType returnType = getReturnType();
        String returnTypeName = returnType == null ? null : returnType.getFullTypeName();
        return procDDL + (omitHeader ? procSrc : generateGaussDBFunctionDeclaration(getLanguage(monitor), returnTypeName, procSrc));
    }

    /**
     * Read function definition via pg_get_functiondef() or fall back to prosrc.
     */
    private String readFunctionDefinition(@NotNull DBRProgressMonitor monitor, @NotNull String procDDL) throws DBException, DBCException {
        if (body == null) {
            if (!isPersisted()) {
                PostgreDataType returnType = getReturnType();
                String returnTypeName = returnType == null ? null : returnType.getFullTypeName();
                body = generateGaussDBFunctionDeclaration(getLanguage(monitor), returnTypeName, DEFAULT_ROUTINE_BODY);
            } else if (getObjectId() == 0) {
                body = buildDeclarationFromProcedureSource(monitor);
            } else {
                try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read procedure body")) {
                    // Unlike PostgreSQL, GaussDB returns a record with headerlines and definition fields.
                    // Read the definition field explicitly instead of parsing the record's string representation.
                    String definition = JDBCUtils.queryString(
                        session,
                        "SELECT definition FROM pg_catalog.pg_get_functiondef(?::oid)",
                        getObjectId()
                    );
                    if (CommonUtils.isEmpty(definition)) {
                        body = buildDeclarationFromProcedureSource(monitor);
                    } else {
                        body = definition;
                    }
                } catch (SQLException e) {
                    log.warn("Failed to read function definition via pg_get_functiondef(), falling back to prosrc", e);
                    body = buildDeclarationFromProcedureSource(monitor);
                }
            }
        }
        return procDDL + body;
    }

    @NotNull
    private String buildDeclarationFromProcedureSource(@NotNull DBRProgressMonitor monitor) throws DBException, DBCException {
        return readProcedureSource(monitor, false, "");
    }

    /**
     * Returns the source used by create/replace persist actions. User-edited complete DDL is preserved;
     * otherwise the declaration is generated from the routine metadata.
     */
    @NotNull
    public String getCreateStatement(@NotNull DBRProgressMonitor monitor) throws DBException {
        return body != null ? body : getObjectDefinitionText(monitor, Map.of(OPTION_SKIP_DROPS, true));
    }

    /**
     * Generate function/procedure declaration with GaussDB compatibility mode awareness. GaussDB
     * procedures written in PL/pgSQL use AS/IS syntax in every compatibility mode. Functions use
     * that form only in A/ORA mode and retain PostgreSQL syntax elsewhere.
     */
    protected String generateGaussDBFunctionDeclaration(PostgreLanguage language, String returnTypeName, String functionBody) {
        String compatMode = getDatabase().getDatabaseCompatibleMode();
        boolean isOracleMode = GaussDBConstants.GAUSSDB_ORACLE_COMPATIBLE_MODE.equalsIgnoreCase(compatMode)
            || GaussDBConstants.GAUSSDB_ORACLE_COMPATIBLE_MODE_C.equalsIgnoreCase(compatMode);
        boolean isPlPgSql = language == null || "plpgsql".equalsIgnoreCase(language.getName());

        if (getKind() == PostgreProcedureKind.p || (isPlPgSql && isOracleMode)) {
            return generateGaussDBPlSqlDeclaration(returnTypeName, functionBody);
        }
        // Non-PL/pgSQL routines and functions outside A/ORA mode use the PostgreSQL declaration form.
        return generateFunctionDeclaration(language, returnTypeName, functionBody);
    }

    /**
     * Generate GaussDB PL/SQL DDL. The source read from pg_proc is already a complete PL/SQL block,
     * so it must not be given another BEGIN/END wrapper.
     */
    private String generateGaussDBPlSqlDeclaration(String returnTypeName, String functionBody) {
        String lineSeparator = org.jkiss.dbeaver.utils.GeneralUtils.getDefaultLineSeparator();
        StringBuilder decl = new StringBuilder();

        String functionSignature = makeOverloadedName(getSchema(), getName(), params, true, true, true);
        decl.append("CREATE OR REPLACE ").append(getProcedureTypeName()).append(" ")
            .append(DBUtils.getQuotedIdentifier(getContainer())).append(".")
            .append(functionSignature).append(lineSeparator);

        if (getProcedureType().hasReturnValue() && !CommonUtils.isEmpty(returnTypeName)) {
            decl.append("RETURN ").append(returnTypeName).append(lineSeparator);
        }

        if (isSecurityDefiner()) {
            decl.append("SECURITY DEFINER").append(lineSeparator);
        }

        decl.append("AS").append(lineSeparator);
        if (CommonUtils.isEmpty(functionBody) || DEFAULT_ROUTINE_BODY.equals(functionBody)) {
            decl.append("BEGIN").append(lineSeparator)
                .append("\t-- Enter routine body here").append(lineSeparator)
                .append(getProcedureType().hasReturnValue() ? "\tRETURN NULL;" : "\tNULL;").append(lineSeparator)
                .append("END;").append(lineSeparator);
        } else {
            decl.append(functionBody);
            if (!functionBody.endsWith("\n") && !functionBody.endsWith("\r")) {
                decl.append(lineSeparator);
            }
        }

        return decl.toString();
    }

    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        GaussDBSchema schema = (GaussDBSchema) getContainer();
        return schema.getGaussDBProceduresCache().refreshObject(monitor, schema, this);
    }

    @Override
    @NotNull
    public GaussDBDatabase getDatabase() {
        return (GaussDBDatabase) super.getDatabase();
    }
}
