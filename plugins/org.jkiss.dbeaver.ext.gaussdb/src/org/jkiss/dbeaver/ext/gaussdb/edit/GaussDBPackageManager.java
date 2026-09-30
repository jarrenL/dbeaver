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

package org.jkiss.dbeaver.ext.gaussdb.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDatabase;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBPackage;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBSchema;
import org.jkiss.dbeaver.model.DBConstants;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.edit.DBECommandContext;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.edit.SQLDatabasePersistAction;
import org.jkiss.dbeaver.model.impl.sql.edit.SQLObjectEditor;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.cache.DBSObjectCache;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

public class GaussDBPackageManager extends SQLObjectEditor<GaussDBPackage, GaussDBDatabase> {

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_EDITOR_ON_CREATE;
    }

    @Override
    public DBSObjectCache<? extends DBSObject, GaussDBPackage> getObjectsCache(GaussDBPackage object) {
        return object.getSchema().packageCache;
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return container instanceof GaussDBSchema schema &&
            ((GaussDBDatabase) schema.getDatabase()).isPackageSupported();
    }

    @Override
    public boolean canDeleteObject(@NotNull GaussDBPackage object) {
        return true;
    }

    @Override
    protected GaussDBPackage createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) throws DBException {
        GaussDBSchema schema = (GaussDBSchema) container;
        return new GaussDBPackage(schema, monitor, "NewPackage");
    }

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext,
                                          @NotNull List<DBEPersistAction> actions, @NotNull SQLObjectEditor<GaussDBPackage, GaussDBDatabase>.ObjectCreateCommand command,
                                          @NotNull Map<String, Object> options) throws DBException {
        GaussDBPackage pack = command.getObject();
        addPackageSourceActions(actions, pack, true, true, false);

    }

    @Override
    protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext,
                                          @NotNull List<DBEPersistAction> actionList, @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options) throws DBException {
        addPackageSourceActions(actionList, command.getObject(),
            command.hasProperty(DBConstants.PARAM_OBJECT_DEFINITION_TEXT),
            command.hasProperty(DBConstants.PARAM_EXTENDED_DEFINITION_TEXT), true);
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext,
                                          @NotNull List<DBEPersistAction> actions, @NotNull SQLObjectEditor<GaussDBPackage, GaussDBDatabase>.ObjectDeleteCommand command,
                                          @NotNull Map<String, Object> options) throws DBException {

        GaussDBPackage pack = command.getObject();
        actions.add(new SQLDatabasePersistAction(
            "Drop package",
            "DROP PACKAGE IF EXISTS " + DBUtils.getObjectFullName(pack, DBPEvaluationContext.DDL)) //$NON-NLS-2$
        );
    }

    private void addPackageSourceActions(List<DBEPersistAction> actionList, GaussDBPackage pack,
                                        boolean declarationChanged, boolean bodyChanged, boolean modify) throws DBException {
        String header = CommonUtils.notEmpty(pack.getObjectDefinitionText()).trim();
        if (declarationChanged && !header.isEmpty()) {
            if (modify) {
                header = replacePackageDeclaration(header);
            }
            if (!header.endsWith(";")) {
                header += ";";
            }
            actionList.add(new SQLDatabasePersistAction("Create package header", header)); // $NON-NLS-1$
        }
        if (!bodyChanged) {
            return;
        }
        String body = CommonUtils.notEmpty(pack.getExtendedDefinitionText()).trim();
        if (!body.isEmpty()) {
            if (modify) {
                body = replacePackageDeclaration(body);
            }
            if (!body.endsWith(";")) {
                body += ";";
            }
            actionList.add(new SQLDatabasePersistAction("Create package body", body));
        } else if (modify) {
            actionList.add(new SQLDatabasePersistAction(
                "Drop package body",
                "DROP PACKAGE BODY IF EXISTS " + DBUtils.getObjectFullName(pack, DBPEvaluationContext.DDL),
                DBEPersistAction.ActionType.OPTIONAL) // $NON-NLS-1$
            );
        }
    }

    // gs_source can return CREATE PACKAGE rather than CREATE OR REPLACE.
    // Change only the leading statement tokens, never strings or nested source.
    private static String replacePackageDeclaration(String sql) {
        int create = skipTrivia(sql, 0);
        int end = create + "CREATE".length();
        if (end >= sql.length() || !sql.regionMatches(true, create, "CREATE", 0, 6)) {
            return sql;
        }
        int next = skipTrivia(sql, end);
        int packageEnd = next + "PACKAGE".length();
        if (next > end && packageEnd < sql.length()
            && sql.regionMatches(true, next, "PACKAGE", 0, 7)
            && !Character.isJavaIdentifierPart(sql.charAt(packageEnd))) {
            return sql.substring(0, end) + " OR REPLACE" + sql.substring(end);
        }
        return sql;
    }

    private static int skipTrivia(String sql, int offset) {
        while (offset < sql.length()) {
            if (Character.isWhitespace(sql.charAt(offset))) {
                offset++;
            } else if (sql.startsWith("--", offset)) {
                while (offset < sql.length() && sql.charAt(offset) != '\n' && sql.charAt(offset) != '\r') {
                    offset++;
                }
            } else if (sql.startsWith("/*", offset)) {
                int depth = 1;
                offset += 2;
                while (offset < sql.length() && depth > 0) {
                    if (sql.startsWith("/*", offset)) {
                        depth++;
                        offset += 2;
                    } else if (sql.startsWith("*/", offset)) {
                        depth--;
                        offset += 2;
                    } else {
                        offset++;
                    }
                }
            } else {
                break;
            }
        }
        return offset;
    }
}
