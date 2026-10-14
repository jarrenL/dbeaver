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
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.compile.DBCCompileLog;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObjectState;
import org.jkiss.utils.CommonUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GaussDBPackageCompiler {
    private static final Pattern ERROR_LINE_PATTERN = Pattern.compile("(?i)(?:near\\s+)?line\\s+(\\d+)");
    private static final Pattern ERROR_COLUMN_PATTERN = Pattern.compile("(?i)\\bcolumn\\s*[:=]?\\s*(\\d+)");
    private static final Pattern ERROR_OFFSET_PATTERN = Pattern.compile("(?i)\\bposition\\s*:\\s*(\\d+)");
    private static final Pattern INVALID_TYPE_PATTERN = Pattern.compile(
        "(?i)invalid\\s+type\\s+name\\s+[\\\"']([^\\\"']+)[\\\"']"
    );
    private static final String APPROXIMATE_LOCATION_NOTE =
        " [Approximate location: inferred client-side from a unique token in the package source]";
    private static final int DISTRIBUTED_COMPILE_UNSUPPORTED_VENDOR_CODE = 10531;
    private static final String AUTHORITATIVE_SOURCE_SQL =
        "SELECT g.oid,g.pkgnamespace,g.pkgname,s.type,s.src," +
            "spec.object_oid AS spec_oid,spec.valid::text AS spec_valid," +
            "body.object_oid AS body_oid,body.valid::text AS body_valid " +
        "FROM pg_catalog.gs_package g " +
        "LEFT JOIN DBE_PLDEVELOPER.GS_SOURCE s ON s.id=g.oid " +
            "AND lower(s.type) IN ('package','package body') " +
        "LEFT JOIN pg_catalog.pg_object spec ON spec.object_oid=g.oid AND spec.object_type='S' " +
        "LEFT JOIN pg_catalog.pg_object body ON body.object_oid=g.oid AND body.object_type='B' " +
        "WHERE g.oid=? AND g.pkgnamespace=? AND g.pkgname=? " +
        "ORDER BY CASE lower(s.type) WHEN 'package' THEN 0 WHEN 'package body' THEN 1 ELSE 2 END";

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
        if (monitor.isCanceled()) {
            return false;
        }
        String sql = getCompileSQL(object, target);
        compileLog.trace(sql);
        boolean compileExecuted = false;
        String purpose = "Compile GaussDB package";
        try (var isolatedContext = object.getDatabase().openIsolatedContext(monitor, purpose, null);
             JDBCSession session = isolatedContext.openSession(monitor, DBCExecutionPurpose.UTIL, purpose);
             IsolatedAutoCommit autoCommit = new IsolatedAutoCommit(session);
             JDBCPreparedStatement statement = session.prepareStatement(sql)) {
            // Keep the JDBC SQLException (executeStatement wraps it in DBCException).
            statement.execute();
            compileExecuted = true;
            if (monitor.isCanceled()) {
                return false;
            }
            try {
                boolean success = readCompilationDiagnostics(session, compileLog, object, target);
                refreshAndNotify(monitor, object);
                return success;
            } catch (DBException diagnosticsError) {
                if (!GaussDBMetadataErrorHandler.isOptionalMetadataError(diagnosticsError)) {
                    throw diagnosticsError;
                }
                // Distributed deployments can compile packages but do not expose
                // DBE_PLDEVELOPER.GS_ERRORS. The authoritative fallback is the
                // package validity in pg_object, not a fabricated source line.
                refreshAndNotify(monitor, object);
                return evaluateRefreshedState(compileLog, object, target,
                    target == GaussDBPackageCompileTarget.BODY ||
                        target == GaussDBPackageCompileTarget.ALL && object.isBodyPresent());
            }
        } catch (SQLException e) {
            // Do not issue metadata SQL on a canceled/aborted connection.
            if (monitor.isCanceled()) {
                return false;
            }
            if (compileExecuted) {
                // A cleanup failure after acknowledged execution is not a source error.
                // Do not reopen metadata sessions or manufacture a source-line diagnostic.
                throw new DBException("Package compilation executed, but resource cleanup failed", e);
            }
            GaussDBServerInfo serverInfo = object.getDataSource().getServerInfo();
            GaussDBServerInfo.Deployment deployment = serverInfo == null
                ? GaussDBServerInfo.Deployment.UNKNOWN : serverInfo.getDeployment();
            if (isNativeCompileUnsupported(e, deployment)) {
                return compileByReplayingSource(monitor, compileLog, object, target);
            }
            if (hasInfrastructureError(e)) {
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
                compileLog.error(toCompileError(e, target, getSourceForDiagnostic(monitor, object, target)));
            }
            refreshAndNotify(monitor, object);
            return false;
        }
    }

    private static boolean compileByReplayingSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCCompileLog compileLog,
        @NotNull GaussDBPackage object,
        @NotNull GaussDBPackageCompileTarget target
    ) throws DBException {
        ReplayPlan plan;
        boolean success = true;
        String purpose = "Safely recompile authoritative GaussDB package source";
        try (var isolatedContext = object.getDatabase().openIsolatedContext(monitor, purpose, null);
             JDBCSession session = isolatedContext.openSession(monitor, DBCExecutionPurpose.UTIL, purpose);
             ReplayTransaction transaction = new ReplayTransaction(session)) {
            GaussDBPackageLock.acquireTransaction(session, object);
            plan = getReplayPlan(session, object, target);
            for (ReplaySource source : plan.sources()) {
                if (monitor.isCanceled()) {
                    success = false;
                    break;
                }
                if (CommonUtils.isEmpty(source.executionSql())) {
                    compileLog.error(new GaussDBPackageCompileError(
                        source.target(), "Package source is unavailable for client-side recompilation", 0, 0));
                    success = false;
                    break;
                }
                compileLog.trace("Recompile GaussDB package by replaying its " +
                    source.target().name().toLowerCase(Locale.ROOT) + " CREATE OR REPLACE source");
                boolean replayExecuted = false;
                try (JDBCPreparedStatement statement = session.prepareStatement(source.executionSql())) {
                    statement.execute();
                    replayExecuted = true;
                } catch (SQLException e) {
                    if (monitor.isCanceled()) {
                        success = false;
                        break;
                    }
                    if (replayExecuted) {
                        throw new DBException("Package source was recompiled, but resource cleanup failed", e);
                    }
                    if (hasInfrastructureError(e)) {
                        throw e;
                    }
                    // The editor still displays the stored source. Locate diagnostics against that
                    // text, not against executionSql where OR REPLACE may have been inserted.
                    compileLog.error(toCompileError(
                        e, source.target(), source.originalSource(), source.executionSql()));
                    success = false;
                    break;
                }
            }
            if (success) {
                transaction.commit();
            }
        } catch (UnsafeReplaySourceException e) {
            if (e.getSuppressed().length > 0) {
                throw new DBException("Package source replay was refused and transaction cleanup failed", e);
            }
            compileLog.error(new GaussDBPackageCompileError(e.target, e.getMessage(), 0, 0));
            refreshAndNotify(monitor, object);
            return false;
        } catch (SQLException e) {
            throw new DBException("Error safely recompiling GaussDB package " + object.getName(), e);
        }
        refreshAndNotify(monitor, object);
        return success && evaluateRefreshedState(compileLog, object, target, plan.bodyExpected());
    }

    @NotNull
    private static ReplayPlan getReplayPlan(
        @NotNull JDBCSession session,
        @NotNull GaussDBPackage object,
        @NotNull GaussDBPackageCompileTarget target
    ) throws DBException {
        long objectId = object.getObjectId();
        long schemaId = object.getSchema().getObjectId();
        String schemaName = object.getSchema().getName();
        String objectName = object.getName();
        if (objectId <= 0 || schemaId <= 0 || CommonUtils.isEmpty(schemaName) || CommonUtils.isEmpty(objectName)) {
            throw new UnsafeReplaySourceException(target,
                "Package identity is incomplete; server source replay was refused");
        }

        List<ReplaySource> allSources = new ArrayList<>(2);
        boolean rowFound = false;
        boolean specificationStateKnown = false;
        boolean bodyStateKnown = false;
        boolean serverBodyPresent = false;
        Set<GaussDBPackageCompileTarget> seenTargets = new HashSet<>();
        try (JDBCPreparedStatement statement = session.prepareStatement(AUTHORITATIVE_SOURCE_SQL)) {
            statement.setLong(1, objectId);
            statement.setLong(2, schemaId);
            statement.setString(3, objectName);
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rowFound = true;
                    long returnedObjectId = resultSet.getLong("oid");
                    long returnedSchemaId = resultSet.getLong("pkgnamespace");
                    String returnedName = resultSet.getString("pkgname");
                    if (returnedObjectId != objectId || returnedSchemaId != schemaId ||
                        !objectName.equals(returnedName)) {
                        throw new UnsafeReplaySourceException(target,
                            "Authoritative package source identity changed; source replay was refused");
                    }
                    long specOid = resultSet.getLong("spec_oid");
                    String specValid = resultSet.getString("spec_valid");
                    long bodyOid = resultSet.getLong("body_oid");
                    String bodyValid = resultSet.getString("body_valid");
                    specificationStateKnown |= specOid == objectId && !CommonUtils.isEmpty(specValid);
                    bodyStateKnown |= bodyOid == objectId && !CommonUtils.isEmpty(bodyValid);
                    serverBodyPresent |= bodyOid == objectId;

                    String sourceType = resultSet.getString("type");
                    if (CommonUtils.isEmpty(sourceType)) {
                        continue;
                    }
                    GaussDBPackageCompileTarget sourceTarget;
                    if ("package".equalsIgnoreCase(sourceType)) {
                        sourceTarget = GaussDBPackageCompileTarget.SPECIFICATION;
                    } else if ("package body".equalsIgnoreCase(sourceType)) {
                        sourceTarget = GaussDBPackageCompileTarget.BODY;
                    } else {
                        throw new UnsafeReplaySourceException(target,
                            "Authoritative package source returned an unexpected source type");
                    }
                    if (!seenTargets.add(sourceTarget)) {
                        throw new UnsafeReplaySourceException(sourceTarget,
                            "Authoritative package source is ambiguous; duplicate source rows were returned");
                    }
                    String source = resultSet.getString("src");
                    if (!isReplaySourceForObject(source, schemaName, objectName, sourceTarget)) {
                        throw new UnsafeReplaySourceException(sourceTarget,
                            "Authoritative package source does not match the selected schema, package, or source part; " +
                                "source replay was refused");
                    }
                    allSources.add(new ReplaySource(sourceTarget, source, asCreateOrReplace(source)));
                }
            }
        } catch (SQLException e) {
            throw new DBException("Error reading authoritative GaussDB package source", e);
        }

        if (!rowFound || !specificationStateKnown) {
            throw new UnsafeReplaySourceException(GaussDBPackageCompileTarget.SPECIFICATION,
                "Package specification source or state is unavailable; source replay was refused");
        }
        ReplaySource specification = findSource(allSources, GaussDBPackageCompileTarget.SPECIFICATION);
        ReplaySource body = findSource(allSources, GaussDBPackageCompileTarget.BODY);
        if (specification == null) {
            throw new UnsafeReplaySourceException(GaussDBPackageCompileTarget.SPECIFICATION,
                "Authoritative package specification source is unavailable; source replay was refused");
        }
        if (serverBodyPresent != bodyStateKnown || serverBodyPresent != (body != null) ||
            object.isBodyPresent() && !serverBodyPresent) {
            throw new UnsafeReplaySourceException(GaussDBPackageCompileTarget.BODY,
                "Package body source and state are inconsistent; source replay was refused");
        }
        if (target == GaussDBPackageCompileTarget.BODY && !serverBodyPresent) {
            throw new UnsafeReplaySourceException(GaussDBPackageCompileTarget.BODY,
                "Authoritative package body source is unavailable; source replay was refused");
        }

        List<ReplaySource> selected = new ArrayList<>(2);
        if (target != GaussDBPackageCompileTarget.BODY) {
            selected.add(specification);
        }
        if (target != GaussDBPackageCompileTarget.SPECIFICATION && body != null) {
            selected.add(body);
        }
        boolean bodyExpected = target == GaussDBPackageCompileTarget.BODY ||
            target == GaussDBPackageCompileTarget.ALL && serverBodyPresent;
        return new ReplayPlan(List.copyOf(selected), bodyExpected);
    }

    private static ReplaySource findSource(
        @NotNull List<ReplaySource> sources,
        @NotNull GaussDBPackageCompileTarget target
    ) {
        return sources.stream().filter(source -> source.target() == target).findFirst().orElse(null);
    }

    static boolean isReplaySourceForObject(
        String source,
        @NotNull String schemaName,
        @NotNull String objectName,
        @NotNull GaussDBPackageCompileTarget expectedTarget
    ) {
        PackageHeader header = parsePackageHeader(CommonUtils.notEmpty(source));
        return header != null && header.target() == expectedTarget &&
            identifierMatches(header.schema(), schemaName) && identifierMatches(header.object(), objectName) &&
            hasSinglePackageTerminator(CommonUtils.notEmpty(source), objectName);
    }

    private static PackageHeader parsePackageHeader(@NotNull String source) {
        int cursor = skipTrivia(source, 0);
        if (!matchesToken(source, cursor, "CREATE")) {
            return null;
        }
        cursor = skipTrivia(source, cursor + "CREATE".length());
        if (matchesToken(source, cursor, "OR")) {
            cursor = skipTrivia(source, cursor + "OR".length());
            if (!matchesToken(source, cursor, "REPLACE")) {
                return null;
            }
            cursor = skipTrivia(source, cursor + "REPLACE".length());
        }
        if (!matchesToken(source, cursor, "PACKAGE")) {
            return null;
        }
        cursor = skipTrivia(source, cursor + "PACKAGE".length());
        GaussDBPackageCompileTarget target = GaussDBPackageCompileTarget.SPECIFICATION;
        if (matchesToken(source, cursor, "BODY")) {
            target = GaussDBPackageCompileTarget.BODY;
            cursor = skipTrivia(source, cursor + "BODY".length());
        }
        ParsedIdentifier schema = parseIdentifier(source, cursor);
        if (schema == null) {
            return null;
        }
        cursor = skipTrivia(source, schema.end());
        if (cursor >= source.length() || source.charAt(cursor) != '.') {
            // An unqualified source can be redirected by search_path; never replay it.
            return null;
        }
        ParsedIdentifier object = parseIdentifier(source, skipTrivia(source, cursor + 1));
        if (object == null) {
            return null;
        }
        cursor = skipTrivia(source, object.end());
        if (cursor < source.length() && source.charAt(cursor) == '.') {
            // Reject three-part or otherwise unexpected target names.
            return null;
        }
        return new PackageHeader(target, schema, object);
    }

    private static ParsedIdentifier parseIdentifier(@NotNull String source, int offset) {
        if (offset >= source.length()) {
            return null;
        }
        if (source.charAt(offset) == '"') {
            StringBuilder value = new StringBuilder();
            for (int index = offset + 1; index < source.length(); index++) {
                char current = source.charAt(index);
                if (current == '"') {
                    if (index + 1 < source.length() && source.charAt(index + 1) == '"') {
                        value.append('"');
                        index++;
                    } else {
                        return new ParsedIdentifier(value.toString(), true, index + 1);
                    }
                } else {
                    value.append(current);
                }
            }
            return null;
        }
        if (!isIdentifierStart(source.charAt(offset))) {
            return null;
        }
        int end = offset + 1;
        while (end < source.length() && isIdentifierPart(source.charAt(end))) {
            end++;
        }
        return new ParsedIdentifier(source.substring(offset, end), false, end);
    }

    private static boolean identifierMatches(@NotNull ParsedIdentifier parsed, @NotNull String catalogName) {
        // GaussDB follows PostgreSQL folding for ordinary identifiers: an
        // unquoted MixedName denotes catalog name mixedname, not quoted
        // "MixedName". Case-insensitive comparison could therefore authorize
        // replay against a different object.
        return parsed.quoted()
            ? parsed.value().equals(catalogName)
            : parsed.value().toLowerCase(Locale.ROOT).equals(catalogName);
    }

    private static boolean hasSinglePackageTerminator(@NotNull String source, @NotNull String objectName) {
        List<ReplayToken> tokens = tokenizeReplaySource(source);
        if (tokens == null || tokens.isEmpty()) {
            return false;
        }
        int cursor = tokens.size() - 1;
        if (tokens.get(cursor).isSymbol("/")) {
            // '/' is a client-side script delimiter, not JDBC SQL. Fail closed
            // instead of accidentally sending it as part of replay DDL.
            return false;
        }
        if (cursor < 2 || !tokens.get(cursor).isSymbol(";")) {
            return false;
        }
        int nameIndex = cursor - 1;
        ReplayToken endName = tokens.get(nameIndex);
        if (!endName.identifier() || !identifierMatches(
            new ParsedIdentifier(endName.value(), endName.quoted(), 0), objectName) ||
            !tokens.get(nameIndex - 1).isIdentifier("END")) {
            return false;
        }
        return hasNoEarlyPackageTerminator(tokens, nameIndex - 1);
    }

    /**
     * Reject a complete package followed by another JDBC statement. Package
     * specifications can end with an unqualified {@code END;} and package bodies
     * can do the same after their initialization block, so checking only for an
     * earlier named {@code END package_name;} is not sufficient.
     *
     * <p>This is intentionally a conservative structural pass, not a PL/SQL
     * compiler. It tracks routine bodies and the block constructs that may contain
     * legitimate inner END tokens. Any inconsistent nesting fails closed.</p>
     */
    private static boolean hasNoEarlyPackageTerminator(
        @NotNull List<ReplayToken> tokens,
        int finalEndIndex
    ) {
        ArrayDeque<String> scopes = new ArrayDeque<>();
        boolean routineSignature = false;
        int routineSignatureParenthesisDepth = -1;
        int parenthesisDepth = 0;
        boolean pendingIf = false;
        int pendingIfParenthesisDepth = -1;
        int pendingIfScopeDepth = -1;
        for (int index = 0; index < finalEndIndex; index++) {
            ReplayToken token = tokens.get(index);
            if (!token.identifier() || token.quoted()) {
                if (token.isSymbol("(")) {
                    parenthesisDepth++;
                } else if (token.isSymbol(")")) {
                    if (parenthesisDepth == 0) {
                        return false;
                    }
                    parenthesisDepth--;
                } else if (token.isSymbol(";") && routineSignature &&
                    parenthesisDepth == routineSignatureParenthesisDepth) {
                    // A package specification or forward declaration.
                    routineSignature = false;
                    routineSignatureParenthesisDepth = -1;
                }
                continue;
            }
            boolean followsEnd = index > 0 && tokens.get(index - 1).isIdentifier("END");
            if (!followsEnd && isRoutineDeclarationStart(tokens, index) &&
                (token.isIdentifier("FUNCTION") || token.isIdentifier("PROCEDURE"))) {
                routineSignature = true;
                routineSignatureParenthesisDepth = parenthesisDepth;
                continue;
            }
            if (routineSignature && parenthesisDepth == routineSignatureParenthesisDepth &&
                (token.isIdentifier("AS") || token.isIdentifier("IS"))) {
                scopes.addLast("ROUTINE");
                routineSignature = false;
                routineSignatureParenthesisDepth = -1;
                continue;
            }
            if (!followsEnd && token.isIdentifier("BEGIN")) {
                scopes.addLast("BEGIN");
                continue;
            }
            if (!followsEnd && token.isIdentifier("IF")) {
                pendingIf = true;
                pendingIfParenthesisDepth = parenthesisDepth;
                pendingIfScopeDepth = scopes.size();
                continue;
            }
            if (pendingIf && token.isIdentifier("THEN") &&
                parenthesisDepth == pendingIfParenthesisDepth && scopes.size() == pendingIfScopeDepth) {
                scopes.addLast("IF");
                pendingIf = false;
                pendingIfParenthesisDepth = -1;
                pendingIfScopeDepth = -1;
                continue;
            }
            if (!followsEnd && token.isIdentifier("LOOP")) {
                scopes.addLast("LOOP");
                continue;
            }
            if (!followsEnd && token.isIdentifier("CASE")) {
                scopes.addLast("CASE");
                continue;
            }
            if (!token.isIdentifier("END") || index + 1 >= finalEndIndex) {
                continue;
            }

            ReplayToken endQualifier = tokens.get(index + 1);
            // SQL CASE expressions commonly continue with an operator, alias or
            // enclosing delimiter rather than a semicolon (for example
            // "CASE ... END + 1"). Close CASE here so its END cannot hide a later
            // top-level package END from this structural scan.
            if (!scopes.isEmpty() && "CASE".equals(scopes.peekLast()) &&
                !endQualifier.isIdentifier("CASE")) {
                scopes.removeLast();
                continue;
            }
            if (endQualifier.isIdentifier("IF") || endQualifier.isIdentifier("LOOP") ||
                endQualifier.isIdentifier("CASE")) {
                int semicolonIndex = index + 2;
                if (endQualifier.isIdentifier("LOOP") && semicolonIndex < tokens.size() &&
                    tokens.get(semicolonIndex).identifier()) {
                    // PL/SQL permits END LOOP label_name;
                    semicolonIndex++;
                }
                if (semicolonIndex >= tokens.size() || !tokens.get(semicolonIndex).isSymbol(";")) {
                    return false;
                }
                if (scopes.isEmpty() ||
                    !scopes.removeLast().equals(endQualifier.value().toUpperCase(Locale.ROOT))) {
                    return false;
                }
                continue;
            }
            if (endQualifier.identifier() && index + 2 < tokens.size() && tokens.get(index + 2).isSymbol(";")) {
                // A nested routine or labeled block may legitimately have the
                // same name as its package. It is an early package terminator
                // only when no inner scope (or only the top-level initialization
                // BEGIN) remains.
                if (scopes.isEmpty() || closesTopLevelPackageBlock(scopes)) {
                    return false;
                }
            } else if (endQualifier.isSymbol(";")) {
                if (scopes.isEmpty() || closesTopLevelPackageBlock(scopes)) {
                    return false;
                }
            }
        }
        // Immediately before the final named END, either no inner scope remains,
        // or exactly the package initialization BEGIN remains for that END to close.
        return parenthesisDepth == 0 && !routineSignature && !pendingIf &&
            (scopes.isEmpty() || scopes.size() == 1 && "BEGIN".equals(scopes.peekLast()));
    }

    private static boolean isRoutineDeclarationStart(@NotNull List<ReplayToken> tokens, int index) {
        if (index == 0) {
            return false;
        }
        ReplayToken previous = tokens.get(index - 1);
        return previous.isSymbol(";") || previous.isIdentifier("AS") || previous.isIdentifier("IS");
    }

    private static boolean closesTopLevelPackageBlock(@NotNull ArrayDeque<String> scopes) {
        String closed = scopes.removeLast();
        if ("IF".equals(closed) || "LOOP".equals(closed)) {
            // These constructs require END IF / END LOOP. A plain END cannot
            // consume them to conceal a package terminator.
            return true;
        }
        boolean routineClosed = false;
        if ("BEGIN".equals(closed) && !scopes.isEmpty() && "ROUTINE".equals(scopes.peekLast())) {
            scopes.removeLast();
            routineClosed = true;
        } else if ("ROUTINE".equals(closed)) {
            routineClosed = true;
        }
        return "BEGIN".equals(closed) && scopes.isEmpty() && !routineClosed;
    }

    private static List<ReplayToken> tokenizeReplaySource(@NotNull String source) {
        List<ReplayToken> tokens = new ArrayList<>();
        int cursor = 0;
        while (cursor < source.length()) {
            char current = source.charAt(cursor);
            if (Character.isWhitespace(current)) {
                cursor++;
                continue;
            }
            if (source.startsWith("--", cursor)) {
                cursor += 2;
                while (cursor < source.length() && source.charAt(cursor) != '\n' && source.charAt(cursor) != '\r') {
                    cursor++;
                }
                continue;
            }
            if (source.startsWith("/*", cursor)) {
                int depth = 1;
                cursor += 2;
                while (cursor < source.length() && depth > 0) {
                    if (source.startsWith("/*", cursor)) {
                        depth++;
                        cursor += 2;
                    } else if (source.startsWith("*/", cursor)) {
                        depth--;
                        cursor += 2;
                    } else {
                        cursor++;
                    }
                }
                if (depth != 0) {
                    return null;
                }
                continue;
            }
            if ((current == 'E' || current == 'e') && cursor + 1 < source.length() &&
                source.charAt(cursor + 1) == '\'' &&
                (cursor == 0 || !isIdentifierPart(source.charAt(cursor - 1)))) {
                cursor = skipQuotedString(source, cursor + 1, true);
                if (cursor < 0) {
                    return null;
                }
                continue;
            }
            if (current == '\'') {
                cursor = skipQuotedString(source, cursor, false);
                if (cursor < 0) {
                    return null;
                }
                continue;
            }
            if (current == '$') {
                int delimiterEnd = source.indexOf('$', cursor + 1);
                if (delimiterEnd > cursor && isDollarTag(source, cursor + 1, delimiterEnd)) {
                    String delimiter = source.substring(cursor, delimiterEnd + 1);
                    int close = source.indexOf(delimiter, delimiterEnd + 1);
                    if (close < 0) {
                        return null;
                    }
                    cursor = close + delimiter.length();
                    continue;
                }
            }
            ParsedIdentifier identifier = parseIdentifier(source, cursor);
            if (identifier != null) {
                tokens.add(new ReplayToken(identifier.value(), true, identifier.quoted()));
                cursor = identifier.end();
                continue;
            }
            tokens.add(new ReplayToken(String.valueOf(current), false, false));
            cursor++;
        }
        return tokens;
    }

    private static int skipQuotedString(@NotNull String source, int quote, boolean escapeString) {
        for (int cursor = quote + 1; cursor < source.length(); cursor++) {
            char current = source.charAt(cursor);
            if (escapeString && current == '\\' && cursor + 1 < source.length()) {
                cursor++;
            } else if (current == '\'') {
                if (cursor + 1 < source.length() && source.charAt(cursor + 1) == '\'') {
                    cursor++;
                } else {
                    return cursor + 1;
                }
            }
        }
        return -1;
    }

    private static boolean isDollarTag(@NotNull String source, int start, int end) {
        if (start == end) {
            return true;
        }
        if (!isIdentifierStart(source.charAt(start))) {
            return false;
        }
        for (int cursor = start + 1; cursor < end; cursor++) {
            char current = source.charAt(cursor);
            if (!Character.isLetterOrDigit(current) && current != '_') {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifierStart(char value) {
        return Character.isLetter(value) || value == '_';
    }

    private static boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$' || value == '#';
    }

    @NotNull
    static String asCreateOrReplace(String sql) {
        String source = CommonUtils.notEmpty(sql);
        int create = skipTrivia(source, 0);
        int createEnd = create + "CREATE".length();
        if (!matchesToken(source, create, "CREATE")) {
            return source;
        }
        int next = skipTrivia(source, createEnd);
        if (matchesToken(source, next, "OR")) {
            int replace = skipTrivia(source, next + "OR".length());
            int packageToken = skipTrivia(source, replace + "REPLACE".length());
            // A stored source that already has the complete clause must be replayed verbatim.
            if (matchesToken(source, replace, "REPLACE") && matchesToken(source, packageToken, "PACKAGE")) {
                return source;
            }
            // Any other CREATE OR... statement is not a package declaration we should rewrite.
            return source;
        }
        if (next > createEnd && matchesToken(source, next, "PACKAGE")) {
            return source.substring(0, createEnd) + " OR REPLACE" + source.substring(createEnd);
        }
        return source;
    }

    private static boolean matchesToken(@NotNull String sql, int offset, @NotNull String token) {
        int end = offset + token.length();
        return offset >= 0 && end <= sql.length()
            && sql.regionMatches(true, offset, token, 0, token.length())
            && (end == sql.length() || !Character.isJavaIdentifierPart(sql.charAt(end)));
    }

    private static int skipTrivia(@NotNull String sql, int offset) {
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

    private static boolean evaluateRefreshedState(
        @NotNull DBCCompileLog compileLog,
        @NotNull GaussDBPackage object,
        @NotNull GaussDBPackageCompileTarget target,
        boolean bodyExpected
    ) {
        DBSObjectState specificationState = object.getSpecificationState();
        DBSObjectState bodyState = bodyExpected ? object.getBodyState() : DBSObjectState.NORMAL;
        boolean specificationValid = specificationState == DBSObjectState.NORMAL;
        boolean bodyValid = !bodyExpected || bodyState == DBSObjectState.NORMAL;
        if (specificationValid && bodyValid) {
            return true;
        }
        String description = switch (target) {
            case SPECIFICATION -> "package specification";
            case BODY -> "package body";
            case ALL -> "package";
        };
        DBSObjectState failedState = !specificationValid ? specificationState : bodyState;
        String message = failedState == DBSObjectState.INVALID
            ? "The " + description + " remains invalid; server compilation diagnostics are unavailable"
            : "The " + description + " compilation completed, but server diagnostics and validity state are unavailable";
        compileLog.error(new GaussDBPackageCompileError(target, message, 0, 0));
        return false;
    }

    private static void refreshAndNotify(
        @NotNull DBRProgressMonitor monitor,
        @NotNull GaussDBPackage object
    ) throws DBException {
        object.refreshObjectState(monitor);
        object.getDataSource().getContainer().fireEvent(new DBPEvent(DBPEvent.Action.OBJECT_UPDATE, object));
    }

    static boolean isNativeCompileUnsupported(
        @NotNull SQLException error,
        @NotNull GaussDBServerInfo.Deployment deployment
    ) {
        boolean knownDistributed = deployment == GaussDBServerInfo.Deployment.DISTRIBUTED
            || deployment == GaussDBServerInfo.Deployment.CLOUD_NATIVE;
        if (!knownDistributed) {
            return false;
        }
        for (SQLException current = error; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            // This exact pair was verified against GaussDB 505.2.1.SPC0800 distributed.
            // A generic 42809/0A000 can mean a different object/type error and must not
            // authorize replaying persisted DDL.
            if ("42809".equals(state) && current.getErrorCode() == DISTRIBUTED_COMPILE_UNSUPPORTED_VENDOR_CODE) {
                return true;
            }
        }
        return false;
    }

    static boolean readCompilationDiagnostics(
        @NotNull JDBCSession session, @NotNull DBCCompileLog compileLog,
        @NotNull GaussDBPackage object, @NotNull GaussDBPackageCompileTarget target
    ) throws DBException {
        try {
            return logErrors(session, compileLog, object, target);
        } catch (SQLException diagnosticsError) {
            // ALTER completed, but its validation result is unknown. Do not turn
            // an unavailable diagnostics catalog into a source line error.
            throw new DBException("Package compilation executed, but compilation diagnostics could not be read", diagnosticsError);
        }
    }

    static boolean isInfrastructureError(String sqlState) {
        return sqlState != null && (sqlState.startsWith("08") || sqlState.startsWith("25")
            || sqlState.startsWith("28") || sqlState.startsWith("40") || sqlState.startsWith("53")
            || sqlState.startsWith("54") || sqlState.startsWith("55") || sqlState.startsWith("57")
            || sqlState.startsWith("58") || sqlState.startsWith("XX") || "42501".equals(sqlState));
    }

    static boolean hasInfrastructureError(Throwable error) {
        if (error == null) {
            return false;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(error);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof SQLException sqlException) {
                if (isInfrastructureError(sqlException.getSQLState())) {
                    return true;
                }
                SQLException next = sqlException.getNextException();
                if (next != null) {
                    pending.addLast(next);
                }
            }
            Throwable cause = current.getCause();
            if (cause != null) {
                pending.addLast(cause);
            }
            for (Throwable suppressed : current.getSuppressed()) {
                if (suppressed != null) {
                    pending.addLast(suppressed);
                }
            }
        }
        return false;
    }

    @NotNull
    static GaussDBPackageCompileError toCompileError(
        @NotNull SQLException error,
        @NotNull GaussDBPackageCompileTarget target
    ) {
        return toCompileError(error, target, null);
    }

    @NotNull
    static GaussDBPackageCompileError toCompileError(
        @NotNull SQLException error,
        @NotNull GaussDBPackageCompileTarget target,
        String source
    ) {
        return toCompileError(error, target, source, source);
    }

    @NotNull
    static GaussDBPackageCompileError toCompileError(
        @NotNull SQLException error,
        @NotNull GaussDBPackageCompileTarget target,
        String source,
        String reportedSource
    ) {
        String message = error.getMessage() == null ? error.toString() : error.getMessage();
        int line = 0;
        int position = 0;
        Matcher invalidTypeMatcher = INVALID_TYPE_PATTERN.matcher(message);
        if (!CommonUtils.isEmpty(source) && invalidTypeMatcher.find()) {
            int offset = uniqueIndexOfIgnoreCase(source, invalidTypeMatcher.group(1));
            if (offset >= 0) {
                int[] location = toLineAndColumn(source, offset);
                line = location[0];
                position = location[1];
                message += APPROXIMATE_LOCATION_NOTE;
            }
            // GaussDB may report LINE 1 for its rewritten DECLARE block. It is
            // not an editor line, so a non-unique token deliberately stays at 0.
            return new GaussDBPackageCompileError(target, message, line, position);
        }
        Matcher offsetMatcher = ERROR_OFFSET_PATTERN.matcher(message);
        if (offsetMatcher.find()) {
            int absolutePosition = parsePositiveInt(offsetMatcher.group(1));
            int mappedOffset = mapReportedOffset(source, reportedSource, absolutePosition - 1);
            if (absolutePosition > 0 && mappedOffset >= 0 && mappedOffset < CommonUtils.notEmpty(source).length()) {
                int[] location = toLineAndColumn(source, mappedOffset);
                line = location[0];
                position = location[1];
            }
            // "Position" is an absolute character offset, never a column. If it
            // cannot be mapped back to the editor source, expose no location.
            return new GaussDBPackageCompileError(target, message, line, position);
        }

        Matcher lineMatcher = ERROR_LINE_PATTERN.matcher(message);
        if (lineMatcher.find()) {
            int reportedLine = parsePositiveInt(lineMatcher.group(1));
            int reportedColumn = 1;
            Matcher columnMatcher = ERROR_COLUMN_PATTERN.matcher(message.substring(lineMatcher.end()));
            if (columnMatcher.find()) {
                reportedColumn = parsePositiveInt(columnMatcher.group(1));
            }
            if (reportedLine > 0 && reportedColumn > 0) {
                if (CommonUtils.isEmpty(source) && CommonUtils.isEmpty(reportedSource)) {
                    line = reportedLine;
                    position = reportedColumn;
                } else {
                    int[] mapped = mapReportedLocation(source, reportedSource, reportedLine, reportedColumn);
                    if (mapped != null) {
                        line = mapped[0];
                        position = mapped[1];
                    }
                }
            }
        }
        GaussDBPackageCompileTarget sourcePart = target;
        // Only GS_ERRORS.type reliably identifies the failed source part for ALL.
        // Error text may mention another package body; do not guess a source tab.
        return new GaussDBPackageCompileError(sourcePart, message, line, position);
    }

    private static int parsePositiveInt(@NotNull String text) {
        try {
            int value = Integer.parseInt(text);
            return Math.max(0, value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int[] mapReportedLocation(String source, String reportedSource, int line, int column) {
        if (CommonUtils.isEmpty(source) || CommonUtils.isEmpty(reportedSource)) {
            return null;
        }
        int reportedOffset = toOffset(reportedSource, line, column);
        int mappedOffset = mapReportedOffset(source, reportedSource, reportedOffset);
        return mappedOffset < 0 ? null : toLineAndColumn(source, mappedOffset);
    }

    private static int mapReportedOffset(String source, String reportedSource, int reportedOffset) {
        if (CommonUtils.isEmpty(source) || CommonUtils.isEmpty(reportedSource)
            || reportedOffset < 0 || reportedOffset >= reportedSource.length()) {
            return -1;
        }
        if (source.equals(reportedSource)) {
            return reportedOffset;
        }
        int prefix = 0;
        int maxPrefix = Math.min(source.length(), reportedSource.length());
        while (prefix < maxPrefix && source.charAt(prefix) == reportedSource.charAt(prefix)) {
            prefix++;
        }
        int inserted = reportedSource.length() - source.length();
        if (inserted <= 0 || !reportedSource.regionMatches(prefix + inserted, source, prefix, source.length() - prefix)) {
            return -1;
        }
        if (reportedOffset < prefix) {
            return reportedOffset;
        }
        if (reportedOffset >= prefix + inserted) {
            return reportedOffset - inserted;
        }
        return -1;
    }

    private static int toOffset(@NotNull String source, int line, int column) {
        if (line <= 0 || column <= 0) {
            return -1;
        }
        int currentLine = 1;
        int lineStart = 0;
        for (int i = 0; i < source.length() && currentLine < line; i++) {
            if (source.charAt(i) == '\n') {
                currentLine++;
                lineStart = i + 1;
            }
        }
        if (currentLine != line) {
            return -1;
        }
        int lineEnd = source.indexOf('\n', lineStart);
        if (lineEnd < 0) {
            lineEnd = source.length();
        }
        int offset = lineStart + column - 1;
        return offset >= lineStart && offset < lineEnd ? offset : -1;
    }

    @NotNull
    private static int[] toLineAndColumn(@NotNull String source, int offset) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new int[] {line, column};
    }

    private static int uniqueIndexOfIgnoreCase(@NotNull String source, @NotNull String token) {
        String lowerSource = source.toLowerCase(Locale.ROOT);
        String lowerToken = token.toLowerCase(Locale.ROOT);
        int first = lowerSource.indexOf(lowerToken);
        if (first < 0 || lowerSource.indexOf(lowerToken, first + lowerToken.length()) >= 0) {
            return -1;
        }
        return first;
    }

    static int findUniqueInvalidTypeOffset(@NotNull String source, @NotNull Throwable error) {
        Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable current = error; current != null && visited.add(current); current = current.getCause()) {
            String message = current.getMessage();
            if (!CommonUtils.isEmpty(message)) {
                Matcher matcher = INVALID_TYPE_PATTERN.matcher(message);
                if (matcher.find()) {
                    return uniqueIndexOfIgnoreCase(source, matcher.group(1));
                }
            }
            if (current instanceof SQLException sqlException) {
                for (SQLException next = sqlException.getNextException(); next != null && visited.add(next);
                     next = next.getNextException()) {
                    String nextMessage = next.getMessage();
                    if (!CommonUtils.isEmpty(nextMessage)) {
                        Matcher matcher = INVALID_TYPE_PATTERN.matcher(nextMessage);
                        if (matcher.find()) {
                            return uniqueIndexOfIgnoreCase(source, matcher.group(1));
                        }
                    }
                }
            }
        }
        return -1;
    }

    private static String getSourceForDiagnostic(
        @NotNull DBRProgressMonitor monitor,
        @NotNull GaussDBPackage object,
        @NotNull GaussDBPackageCompileTarget target
    ) {
        try {
            return switch (target) {
                case SPECIFICATION -> object.getDeclarationText(monitor);
                case BODY -> object.getBodyText(monitor);
                case ALL -> null;
            };
        } catch (DBException ignored) {
            return null;
        }
    }

    private record ReplaySource(
        @NotNull GaussDBPackageCompileTarget target,
        String originalSource,
        String executionSql
    ) {
    }

    private record ReplayPlan(@NotNull List<ReplaySource> sources, boolean bodyExpected) {
    }

    private record ParsedIdentifier(@NotNull String value, boolean quoted, int end) {
    }

    private record PackageHeader(
        @NotNull GaussDBPackageCompileTarget target,
        @NotNull ParsedIdentifier schema,
        @NotNull ParsedIdentifier object
    ) {
    }

    private record ReplayToken(@NotNull String value, boolean identifier, boolean quoted) {
        private boolean isIdentifier(@NotNull String token) {
            return identifier && !quoted && value.equalsIgnoreCase(token);
        }

        private boolean isSymbol(@NotNull String symbol) {
            return !identifier && value.equals(symbol);
        }
    }

    /**
     * Runs the authority read and every replay DDL in one isolated transaction.
     * A transaction-scoped application lock serializes this fallback with package
     * persistence performed by DBeaver. Existing user transactions are never reused.
     */
    private static final class ReplayTransaction implements AutoCloseable {
        private final JDBCSession session;
        private final boolean previousAutoCommit;
        private final int previousIsolation;
        private boolean committed;

        private ReplayTransaction(@NotNull JDBCSession session) throws SQLException {
            this.session = session;
            previousAutoCommit = session.getAutoCommit();
            previousIsolation = session.getTransactionIsolation();
            boolean isolationChanged = false;
            try {
                if (previousIsolation != Connection.TRANSACTION_REPEATABLE_READ) {
                    session.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                    isolationChanged = true;
                }
                if (previousAutoCommit) {
                    session.setAutoCommit(false);
                }
            } catch (SQLException e) {
                if (isolationChanged) {
                    try {
                        session.setTransactionIsolation(previousIsolation);
                    } catch (SQLException restoreError) {
                        e.addSuppressed(restoreError);
                    }
                }
                throw e;
            }
        }

        private void commit() throws SQLException {
            session.commit();
            committed = true;
        }

        @Override
        public void close() throws SQLException {
            SQLException failure = null;
            if (!committed) {
                try {
                    session.rollback();
                } catch (SQLException e) {
                    failure = e;
                }
            }
            // Enabling auto-commit after a failed rollback could commit unknown
            // work, so leave the connection unusable and let close discard it.
            if (failure == null && previousAutoCommit) {
                try {
                    session.setAutoCommit(true);
                } catch (SQLException e) {
                    failure = e;
                }
            }
            if (failure == null && previousIsolation != Connection.TRANSACTION_REPEATABLE_READ) {
                try {
                    session.setTransactionIsolation(previousIsolation);
                } catch (SQLException e) {
                    failure = e;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /**
     * Native ALTER PACKAGE probing must not run in the user's default transaction:
     * the expected 505 error aborts that transaction. The caller owns a newly opened
     * isolated context, so making this one statement auto-commit cannot commit user work.
     */
    private static final class IsolatedAutoCommit implements AutoCloseable {
        private final JDBCSession session;
        private final boolean previousAutoCommit;

        private IsolatedAutoCommit(@NotNull JDBCSession session) throws SQLException {
            this.session = session;
            previousAutoCommit = session.getAutoCommit();
            if (!previousAutoCommit) {
                session.setAutoCommit(true);
            }
        }

        @Override
        public void close() throws SQLException {
            if (!previousAutoCommit) {
                session.setAutoCommit(false);
            }
        }
    }

    private static final class UnsafeReplaySourceException extends DBException {
        private final GaussDBPackageCompileTarget target;

        private UnsafeReplaySourceException(@NotNull GaussDBPackageCompileTarget target, @NotNull String message) {
            super(message);
            this.target = target;
        }
    }

    static boolean logErrors(
        @NotNull JDBCSession session,
        @NotNull DBCCompileLog compileLog,
        @NotNull GaussDBPackage object,
        @NotNull GaussDBPackageCompileTarget target
    ) throws SQLException {
        String sql = "SELECT e.type,e.line,e.src,s.src AS definition FROM DBE_PLDEVELOPER.GS_ERRORS e " +
            "LEFT JOIN DBE_PLDEVELOPER.GS_SOURCE s ON s.id=e.id AND lower(s.type)=lower(e.type) " +
            "WHERE e.id=? AND e.nspid=? AND lower(e.type) IN ('package','package body') " +
            "ORDER BY CASE lower(e.type) WHEN 'package' THEN 0 ELSE 1 END,e.line";
        boolean success = true;
        try (JDBCPreparedStatement statement = session.prepareStatement(sql)) {
            statement.setLong(1, object.getObjectId());
            statement.setLong(2, object.getSchema().getObjectId());
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String type = resultSet.getString("type");
                    GaussDBPackageCompileTarget sourcePart = "package body".equalsIgnoreCase(type)
                        ? GaussDBPackageCompileTarget.BODY
                        : GaussDBPackageCompileTarget.SPECIFICATION;
                    if (target != GaussDBPackageCompileTarget.ALL && target != sourcePart) {
                        continue;
                    }
                    compileLog.error(new GaussDBPackageCompileError(
                        sourcePart,
                        resultSet.getString("src"),
                        GaussDBPackageSourceLines.toEditorLine(
                            resultSet.getString("definition"), resultSet.getInt("line"))
                    ));
                    success = false;
                }
            }
        }
        return success;
    }
}
