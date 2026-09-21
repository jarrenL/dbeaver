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
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBSchema;
import org.jkiss.dbeaver.ext.postgresql.PostgreUtils;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreSchema;
import org.jkiss.dbeaver.model.DBConstants;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.edit.DBECommandContext;
import org.jkiss.dbeaver.model.edit.DBEObjectRenamer;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.edit.SQLDatabasePersistAction;
import org.jkiss.dbeaver.model.impl.sql.edit.SQLObjectEditor;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.cache.DBSObjectCache;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

/**
 * GaussDBProcedureManager
 */
public class GaussDBProcedureManager extends GaussDBRoutineManager<GaussDBProcedure> {

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return super.canCreateObject(container)
            && container instanceof GaussDBSchema schema
            && schema.getDatabase() instanceof org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDatabase database
            && database.isStoredProcedureSupported();
    }

    @Nullable
    @Override
    public DBSObjectCache<GaussDBSchema, GaussDBProcedure> getObjectsCache(GaussDBProcedure object) {
        GaussDBSchema schema = (GaussDBSchema) object.getContainer();
        return schema.getGaussDBProceduresCache();
    }

    @Override
    protected GaussDBProcedure createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull final Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new GaussDBProcedure((PostgreSchema) container);
    }
}

/**
 * Shared, type-safe editor implementation for GaussDB procedures and functions.
 */
abstract class GaussDBRoutineManager<ROUTINE extends GaussDBProcedure>
    extends SQLObjectEditor<ROUTINE, GaussDBSchema>
    implements DBEObjectRenamer<ROUTINE> {

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return container instanceof GaussDBSchema schema
            && schema.getDatabase() instanceof org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDatabase database
            && !database.isMCompatibility()
            && schema.getDataSource().getServerType().supportsFunctionCreate();
    }

    @Override
    public boolean canDeleteObject(@NotNull ROUTINE object) {
        return object.getDataSource().getServerType().supportsFunctionCreate();
    }

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_EDITOR_ON_CREATE;
    }

    @Override
    protected void validateObjectProperties(
        @NotNull DBRProgressMonitor monitor,
        @NotNull ObjectChangeCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        if (CommonUtils.isEmpty(command.getObject().getName())) {
            throw new DBException("Routine name cannot be empty");
        }
    }

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext,
                                          @NotNull List<DBEPersistAction> actions, @NotNull ObjectCreateCommand command,
                                          @NotNull Map<String, Object> options) throws DBException {
        createOrReplaceRoutineQuery(monitor, actions, command.getObject());
    }

    @Override
    protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext,
                                          @NotNull List<DBEPersistAction> actionList, @NotNull ObjectChangeCommand command,
                                          @NotNull Map<String, Object> options) throws DBException {
        if (command.getProperties().size() > 1 || command.getProperty(DBConstants.PROP_ID_DESCRIPTION) == null) {
            createOrReplaceRoutineQuery(monitor, actionList, command.getObject());
        }
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext,
                                          @NotNull List<DBEPersistAction> actions, @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options) {
        String objectType = command.getObject().getProcedureTypeName();
        actions.add(
            new SQLDatabasePersistAction("Drop routine", "DROP " + objectType + " " + command.getObject().getFullQualifiedSignature()) //$NON-NLS-2$
        );
    }

    private void createOrReplaceRoutineQuery(
        @NotNull DBRProgressMonitor monitor,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ROUTINE routine
    ) throws DBException {
        if (routine.getDatabase() instanceof org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDatabase database
            && database.isMCompatibility()) {
            throw new DBException("Routine creation and replacement are not supported in M compatibility mode");
        }
        actions.add(new SQLDatabasePersistAction("Create routine", routine.getCreateStatement(monitor), true));
    }

    @Override
    protected void addObjectExtraActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext,
                                         @NotNull List<DBEPersistAction> actions, @NotNull NestedObjectCommand<ROUTINE, PropertyHandler> command,
                                         @NotNull Map<String, Object> options) {
        if (command.getProperty(DBConstants.PROP_ID_DESCRIPTION) != null) {
            actions.add(new SQLDatabasePersistAction("Comment routine",
                "COMMENT ON " + command.getObject().getProcedureTypeName() + " " + command.getObject().getFullQualifiedSignature()
                    + " IS " + SQLUtils.quoteString(command.getObject(), command.getObject().getDescription())));
        }
        boolean isDDL = CommonUtils.getOption(options, DBPScriptObject.OPTION_DDL_SOURCE);
        if (isDDL) {
            try {
                PostgreUtils.getObjectGrantPermissionActions(monitor, command.getObject(), actions, options);
            } catch (DBException e) {
                log.error(e);
            }
        }

    }

    @Override
    public void renameObject(@NotNull DBECommandContext commandContext, @NotNull ROUTINE object,
        @NotNull Map<String, Object> options, @NotNull String newName) throws DBException {
        processObjectRename(commandContext, object, options, newName);
    }

    @Override
    protected void addObjectRenameActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext,
                                          @NotNull List<DBEPersistAction> actions, @NotNull ObjectRenameCommand command, @NotNull Map<String, Object> options) {
        ROUTINE routine = command.getObject();
        actions.add(new SQLDatabasePersistAction("Rename routine",
            "ALTER " + routine.getProcedureTypeName() + " " + DBUtils.getQuotedIdentifier(routine.getSchema()) + "."
                + GaussDBProcedure.makeOverloadedName(routine.getSchema(), command.getOldName(), routine.getParameters(monitor),
                    true, false, false)
                + " RENAME TO " + DBUtils.getQuotedIdentifier(routine.getDataSource(), command.getNewName())));
    }
}
