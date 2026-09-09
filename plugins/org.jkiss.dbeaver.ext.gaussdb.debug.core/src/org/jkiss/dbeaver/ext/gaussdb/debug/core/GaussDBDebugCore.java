/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.debug.DBGConstants;
import org.jkiss.dbeaver.ext.gaussdb.model.*;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreLanguage;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class GaussDBDebugCore {
    public static final String BUNDLE_SYMBOLIC_NAME = "org.jkiss.dbeaver.ext.gaussdb.debug.core"; //$NON-NLS-1$
    private static final Set<String> DEBUGGABLE_LANGUAGES = Set.of("plpgsql", "plsql"); //$NON-NLS-1$ //$NON-NLS-2$

    private GaussDBDebugCore() {
    }

    public static void saveRoutine(GaussDBProcedure routine, Map<String, Object> configuration) {
        DBPDataSourceContainer container = routine.getDataSource().getContainer();
        configuration.put(DBGConstants.ATTR_PROJECT_NAME, container.getProject().getName());
        configuration.put(DBGConstants.ATTR_DATASOURCE_ID, container.getId());
        configuration.put(DBGConstants.ATTR_DEBUG_TYPE, GaussDBDebugConstants.DEBUG_TYPE_ROUTINE);
        configuration.put(GaussDBDebugConstants.ATTR_DATABASE_NAME, routine.getDatabase().getName());
        configuration.put(GaussDBDebugConstants.ATTR_SCHEMA_NAME, routine.getSchema().getName());
        configuration.put(GaussDBDebugConstants.ATTR_ROUTINE_OID, String.valueOf(routine.getObjectId()));
    }

    /**
     * Performs the inexpensive, routine-specific part of debug capability detection. Server API
     * signatures and privileges are checked again on the isolated controller connection when the
     * session starts.
     */
    public static String getRoutineEligibilityError(DBRProgressMonitor monitor, GaussDBProcedure routine)
        throws DBException {
        DBCompatibilityEnum compatibility = routine.getDatabase().getCompatibility();
        if (compatibility == null) {
            return getRoutineEligibilityError(null, routine.isPersisted(), routine.getObjectId(), null);
        }
        if (compatibility == DBCompatibilityEnum.M) {
            return getRoutineEligibilityError(compatibility, routine.isPersisted(), routine.getObjectId(), null);
        }
        if (!routine.isPersisted() || routine.getObjectId() <= 0) {
            return getRoutineEligibilityError(compatibility, routine.isPersisted(), routine.getObjectId(), null);
        }
        PostgreLanguage language = routine.getLanguage(monitor);
        return getRoutineEligibilityError(
            compatibility,
            routine.isPersisted(),
            routine.getObjectId(),
            language == null ? null : language.getName()
        );
    }

    static String getRoutineEligibilityError(
        DBCompatibilityEnum compatibility,
        boolean persisted,
        long routineOid,
        String languageName
    ) {
        if (compatibility == null) {
            return "The GaussDB compatibility mode is unknown; PL/SQL debugging cannot be enabled safely";
        }
        if (compatibility == DBCompatibilityEnum.M) {
            return "PL/SQL debugging is not supported in GaussDB M compatibility mode";
        }
        if (!persisted || routineOid <= 0) {
            return "Save the routine before starting the debugger";
        }
        String normalizedLanguage = languageName == null ? "" : languageName.toLowerCase(Locale.ENGLISH);
        if (!DEBUGGABLE_LANGUAGES.contains(normalizedLanguage)) {
            return "Routine language '" + (languageName == null ? "unknown" : languageName) +
                "' is not supported by the GaussDB PL/SQL debugger";
        }
        return null;
    }

    public static GaussDBProcedure resolveRoutine(
        DBRProgressMonitor monitor,
        DBPDataSourceContainer container,
        Map<String, Object> configuration
    ) throws DBException {
        if (!container.isConnected()) {
            container.connect(monitor, true, true);
        }
        GaussDBDataSource dataSource = (GaussDBDataSource) container.getDataSource();
        String databaseName = (String) configuration.get(GaussDBDebugConstants.ATTR_DATABASE_NAME);
        String schemaName = (String) configuration.get(GaussDBDebugConstants.ATTR_SCHEMA_NAME);
        long oid = CommonUtils.toLong(configuration.get(GaussDBDebugConstants.ATTR_ROUTINE_OID));
        GaussDBDatabase database = (GaussDBDatabase) dataSource.getDatabase(databaseName);
        if (database == null) {
            throw new DBException("Database '" + databaseName + "' not found");
        }
        GaussDBSchema schema = (GaussDBSchema) database.getSchema(monitor, schemaName);
        if (schema == null) {
            throw new DBException("Schema '" + schemaName + "' not found in database " + databaseName);
        }
        GaussDBProcedure routine = schema.getGaussDBProceduresCache().getAllObjects(monitor, schema).stream()
            .filter(candidate -> candidate.getObjectId() == oid)
            .findFirst()
            .orElse(null);
        if (routine == null) {
            routine = schema.getGaussDBFunctionsCache().getAllObjects(monitor, schema).stream()
                .filter(candidate -> candidate.getObjectId() == oid)
                .findFirst()
                .orElse(null);
        }
        if (routine == null) {
            throw new DBException("Routine " + oid + " not found in schema " + schemaName);
        }
        return routine;
    }
}
