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
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GaussDBProcedure extends PostgreProcedure {

    private static final Log log = Log.getLog(GaussDBProcedure.class);

    public long propackageid;
    public String prokind;
    public String procSrc;

    public long getPropackageid() {
        return propackageid;
    }

    public GaussDBProcedure(PostgreSchema schema) {
        super(schema);
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
        boolean omitHeader = CommonUtils.getOption(options, OPTION_DEBUGGER_SOURCE);
        String procDDL = omitHeader || CommonUtils.getOption(options, OPTION_SKIP_DROPS) ?
            "" :
            "-- DROP " + getProcedureTypeName() + " " + getFullQualifiedSignature() + ";\n\n";

        if (isPersisted() && (!getDataSource().getServerType().supportsFunctionDefRead() || omitHeader) && !isAggregate()) {
            procDDL = readProcedureSource(monitor, omitHeader, procDDL);
        } else {
            procDDL = readFunctionDefinition(monitor, procDDL);
        }

        if (this.isPersisted() && !omitHeader) {
            procDDL += ";\n";

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
                procSrc = JDBCUtils.queryString(session, "SELECT prosrc FROM pg_proc WHERE oid = ?", getObjectId());
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
                body = generateGaussDBFunctionDeclaration(getLanguage(monitor), returnTypeName, "\n\t-- Enter function body here\n");
            } else if (getObjectId() == 0) {
                body = this.procSrc;
            } else {
                if (isAggregate) {
                    // Delegate to base class for aggregate handling
                    return super.getObjectDefinitionText(monitor, Map.of());
                } else {
                    try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read procedure body")) {
                        String res = JDBCUtils.queryString(session, "SELECT pg_get_functiondef(" + getObjectId() + ")");
                        if (res == null) {
                            body = this.procSrc;
                        } else {
                            // pg_get_functiondef() returns the full CREATE OR REPLACE FUNCTION ... statement.
                            // Use the full output as-is rather than fragile substring offsets.
                            body = res;
                        }
                    } catch (SQLException e) {
                        log.warn("Failed to read function definition via pg_get_functiondef(), falling back to prosrc", e);
                        body = this.procSrc;
                    }
                }
            }
        }
        return procDDL + body;
    }

    /**
     * Generate function/procedure declaration with GaussDB compatibility mode awareness.
     * In Oracle compatibility mode, GaussDB uses AS/IS syntax instead of AS $$...$$.
     */
    protected String generateGaussDBFunctionDeclaration(PostgreLanguage language, String returnTypeName, String functionBody) {
        GaussDBDatabase database = getDatabase();
        String compatMode = database != null ? database.getDatabaseCompatibleMode() : null;
        boolean isOracleMode = GaussDBConstants.GAUSSDB_ORACLE_COMPATIBLE_MODE.equalsIgnoreCase(compatMode);

        if (isOracleMode) {
            return generateOracleCompatDeclaration(language, returnTypeName, functionBody);
        }
        // For PG/MySQL/Teradata modes, use the standard PG declaration
        return generateFunctionDeclaration(language, returnTypeName, functionBody);
    }

    /**
     * Generate Oracle-compatible DDL for GaussDB in Oracle compatibility mode.
     * Uses AS/IS and BEGIN...END syntax instead of AS $$...$$.
     */
    private String generateOracleCompatDeclaration(PostgreLanguage language, String returnTypeName, String functionBody) {
        String lineSeparator = org.jkiss.dbeaver.utils.GeneralUtils.getDefaultLineSeparator();
        StringBuilder decl = new StringBuilder();

        String functionSignature = makeOverloadedName(getSchema(), getName(), params, true, true, true);
        decl.append("CREATE OR REPLACE ").append(getProcedureTypeName()).append(" ")
            .append(DBUtils.getQuotedIdentifier(getContainer())).append(".")
            .append(functionSignature).append(lineSeparator);

        if (getProcedureType().hasReturnValue() && !CommonUtils.isEmpty(returnTypeName)) {
            decl.append("RETURN ").append(returnTypeName).append(lineSeparator);
        }

        if (language != null) {
            decl.append("LANGUAGE ").append(language).append(lineSeparator);
        }

        if (isSecurityDefiner()) {
            decl.append("SECURITY DEFINER").append(lineSeparator);
        }

        // Oracle mode uses AS keyword instead of AS $$
        decl.append("AS").append(lineSeparator);
        if (!CommonUtils.isEmpty(functionBody)) {
            decl.append(functionBody).append(lineSeparator);
        }
        decl.append("END;").append(lineSeparator);

        return decl.toString();
    }

    @Override
    @NotNull
    public GaussDBDatabase getDatabase() {
        return (GaussDBDatabase) super.getDatabase();
    }
}
