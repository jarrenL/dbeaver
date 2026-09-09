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
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBPEvent;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.compile.DBCCompileLog;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GaussDBPackageCompiler {
    private static final Pattern ERROR_LINE_PATTERN = Pattern.compile("(?i)(?:near\\s+)?line\\s+(\\d+)");

    private GaussDBPackageCompiler() {
    }

    @NotNull
    public static String getCompileSQL(
        @NotNull GaussDBPackage object,
        @NotNull GaussDBPackageCompileTarget target
    ) {
        return "ALTER PACKAGE " + object.getFullyQualifiedName(DBPEvaluationContext.DDL) +
            " COMPILE" + target.getSqlSuffix();
    }

    public static boolean compile(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCCompileLog compileLog,
        @NotNull GaussDBPackage object,
        @NotNull GaussDBPackageCompileTarget target
    ) throws DBException {
        String sql = getCompileSQL(object, target);
        compileLog.trace(sql);
        try (JDBCSession session = DBUtils.openUtilSession(monitor, object, "Compile GaussDB package");
             JDBCPreparedStatement statement = session.prepareStatement(sql)) {
            // Keep the JDBC SQLException (executeStatement wraps it in DBCException).
            statement.execute();
            boolean success = logErrors(session, compileLog, object, target);
            object.refreshObjectState(monitor);
            object.getDataSource().getContainer().fireEvent(new DBPEvent(DBPEvent.Action.OBJECT_UPDATE, object));
            return success;
        } catch (SQLException e) {
            String sqlState = e.getSQLState();
            if (sqlState != null && (sqlState.startsWith("08") || sqlState.startsWith("28"))) {
                throw new DBException("Error compiling GaussDB package " + object.getName(), e);
            }
            boolean errorsFound = false;
            try (JDBCSession errorsSession = DBUtils.openMetaSession(monitor, object, "Read package compilation errors")) {
                errorsFound = !logErrors(errorsSession, compileLog, object, target);
            } catch (SQLException metadataError) {
                if (!GaussDBMetadataErrorHandler.isOptionalMetadataError(metadataError)) {
                    throw new DBException("Error reading package compilation errors", metadataError);
                }
            }
            if (!errorsFound) {
                compileLog.error(toCompileError(e, target));
            }
            object.refreshObjectState(monitor);
            object.getDataSource().getContainer().fireEvent(new DBPEvent(DBPEvent.Action.OBJECT_UPDATE, object));
            return false;
        }
    }

    @NotNull
    static GaussDBPackageCompileError toCompileError(
        @NotNull SQLException error,
        @NotNull GaussDBPackageCompileTarget target
    ) {
        String message = error.getMessage() == null ? error.toString() : error.getMessage();
        Matcher matcher = ERROR_LINE_PATTERN.matcher(message);
        int line = matcher.find() ? Integer.parseInt(matcher.group(1)) : 1;
        GaussDBPackageCompileTarget sourcePart = target;
        if (sourcePart == GaussDBPackageCompileTarget.ALL) {
            sourcePart = message.toLowerCase().contains("package body")
                ? GaussDBPackageCompileTarget.BODY
                : GaussDBPackageCompileTarget.SPECIFICATION;
        }
        return new GaussDBPackageCompileError(sourcePart, message, line);
    }

    static boolean logErrors(
        @NotNull JDBCSession session,
        @NotNull DBCCompileLog compileLog,
        @NotNull GaussDBPackage object,
        @NotNull GaussDBPackageCompileTarget target
    ) throws SQLException {
        String sql = "SELECT type,line,src FROM DBE_PLDEVELOPER.GS_ERRORS " +
            "WHERE id=? AND nspid=? AND lower(type) IN ('package','package body') " +
            "ORDER BY CASE lower(type) WHEN 'package' THEN 0 ELSE 1 END,line";
        boolean success = true;
        try (JDBCPreparedStatement statement = session.prepareStatement(sql)) {
            statement.setLong(1, object.getObjectId());
            statement.setLong(2, object.getSchema().getObjectId());
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String type = JDBCUtils.safeGetString(resultSet, "type");
                    GaussDBPackageCompileTarget sourcePart = "package body".equalsIgnoreCase(type)
                        ? GaussDBPackageCompileTarget.BODY
                        : GaussDBPackageCompileTarget.SPECIFICATION;
                    if (target != GaussDBPackageCompileTarget.ALL && target != sourcePart) {
                        continue;
                    }
                    compileLog.error(new GaussDBPackageCompileError(
                        sourcePart,
                        JDBCUtils.safeGetString(resultSet, "src"),
                        JDBCUtils.safeGetInt(resultSet, "line")
                    ));
                    success = false;
                }
            }
        }
        return success;
    }
}
