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

import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.SQLException;

public class GaussDBPackageCompilerTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"42601", "08006"})
    void statementCloseAfterSuccessfulCompileIsNotASourceDiagnostic(String state) throws Exception {
        var session = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCSession.class);
        var compileStatement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        var diagnosticsStatement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        var result = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet.class);
        var object = Mockito.mock(GaussDBPackage.class);
        var schema = Mockito.mock(GaussDBSchema.class);
        var database = Mockito.mock(GaussDBDatabase.class);
        var context = Mockito.mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreExecutionContext.class);
        var isolatedContext = Mockito.mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreExecutionContext.class);
        var dataSource = Mockito.mock(GaussDBDataSource.class);
        var container = Mockito.mock(org.jkiss.dbeaver.model.DBPDataSourceContainer.class);
        Mockito.when(object.getParentObject()).thenReturn(schema);
        Mockito.when(object.getSchema()).thenReturn(schema);
        Mockito.when(object.getDatabase()).thenReturn(database);
        Mockito.when(schema.getParentObject()).thenReturn(database);
        Mockito.when(database.isInstanceConnected()).thenReturn(true);
        Mockito.when(database.getDefaultContext(Mockito.any(), Mockito.anyBoolean())).thenReturn(context);
        Mockito.when(database.openIsolatedContext(Mockito.any(), Mockito.anyString(), Mockito.isNull()))
            .thenReturn(isolatedContext);
        Mockito.when(isolatedContext.openSession(Mockito.any(), Mockito.any(), Mockito.anyString())).thenReturn(session);
        Mockito.when(object.getDataSource()).thenReturn(dataSource);
        Mockito.when(dataSource.getContainer()).thenReturn(container);
        Mockito.when(object.getFullyQualifiedName(DBPEvaluationContext.DDL)).thenReturn("public.test_pkg");
        Mockito.when(session.prepareStatement(Mockito.anyString())).thenAnswer(invocation ->
            invocation.<String>getArgument(0).startsWith("ALTER PACKAGE") ? compileStatement : diagnosticsStatement);
        Mockito.when(diagnosticsStatement.executeQuery()).thenReturn(result);
        SQLException failure = new SQLException("statement cleanup failed", state);
        Mockito.doThrow(failure).when(compileStatement).close();
        var monitor = new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor();
        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        var actual = Assertions.assertThrows(org.jkiss.dbeaver.DBException.class,
            () -> GaussDBPackageCompiler.compile(monitor, log, object, GaussDBPackageCompileTarget.ALL));
        Assertions.assertSame(failure, actual.getCause());
        Assertions.assertTrue(log.getErrorStack().isEmpty());
        Mockito.verify(compileStatement).execute();
        Mockito.verify(diagnosticsStatement).executeQuery();
        Mockito.verify(isolatedContext).openSession(Mockito.any(), Mockito.any(), Mockito.anyString());
        Mockito.verify(object).refreshObjectState(monitor);
        Mockito.verify(session).close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"next", "field", "result-close", "statement-close"})
    void partialDiagnosticsSurviveReadAndCleanupFailures(String stage) throws Exception {
        var session = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCSession.class);
        var statement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        var result = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet.class);
        var object = Mockito.mock(GaussDBPackage.class);
        var schema = Mockito.mock(GaussDBSchema.class);
        Mockito.when(object.getSchema()).thenReturn(schema);
        Mockito.when(object.getObjectId()).thenReturn(42L);
        Mockito.when(schema.getObjectId()).thenReturn(99L);
        Mockito.when(session.prepareStatement(Mockito.anyString())).thenReturn(statement);
        Mockito.when(statement.executeQuery()).thenReturn(result);
        Mockito.when(result.next()).thenReturn(true, false);
        Mockito.when(result.getString("type")).thenReturn("package body");
        Mockito.when(result.getString("src")).thenReturn("first body diagnostic");
        Mockito.when(result.getInt("line")).thenReturn(7);
        SQLException failure = new SQLException("failure at " + stage, "08006");
        switch (stage) {
            case "next" -> Mockito.when(result.next()).thenReturn(true).thenThrow(failure);
            case "field" -> {
                Mockito.when(result.next()).thenReturn(true, true, false);
                Mockito.when(result.getString("src")).thenReturn("first body diagnostic").thenThrow(failure);
            }
            case "result-close" -> Mockito.doThrow(failure).when(result).close();
            case "statement-close" -> Mockito.doThrow(failure).when(statement).close();
            default -> throw new AssertionError(stage);
        }
        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        var actual = Assertions.assertThrows(org.jkiss.dbeaver.DBException.class,
            () -> GaussDBPackageCompiler.readCompilationDiagnostics(session, log, object, GaussDBPackageCompileTarget.ALL));
        Assertions.assertSame(failure, actual.getCause());
        Assertions.assertEquals(1, log.getErrorStack().size());
        var diagnostic = (GaussDBPackageCompileError) log.getErrorStack().iterator().next();
        Assertions.assertEquals("first body diagnostic", diagnostic.getMessage());
        Assertions.assertEquals(7, diagnostic.getLine());
        Assertions.assertSame(GaussDBPackageCompileTarget.BODY, diagnostic.getSourcePart());
        Mockito.verify(result).close();
        Mockito.verify(statement).close();
        Mockito.verify(statement).setLong(1, 42L);
        Mockito.verify(statement).setLong(2, 99L);
        // The caller owns this session; only resources opened by logErrors are closed here.
        Mockito.verify(session, Mockito.never()).close();
        Mockito.verify(object, Mockito.never()).refreshObjectState(Mockito.any());
    }

    @Test
    void catalogDiagnosticLineAlreadyMatchesCompleteServerSource() throws Exception {
        var session = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCSession.class);
        var statement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        var result = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet.class);
        var object = Mockito.mock(GaussDBPackage.class);
        var schema = Mockito.mock(GaussDBSchema.class);
        String source = "CREATE OR REPLACE PACKAGE BODY review_live.review_pkg AS\n"
            + " FUNCTION value_of RETURN INTEGER AS\n"
            + " v review_live.missing_dependency.id%TYPE;\n"
            + " BEGIN\n RETURN 7;\n END;\nEND review_pkg;";
        Mockito.when(object.getSchema()).thenReturn(schema);
        Mockito.when(object.getObjectId()).thenReturn(42L);
        Mockito.when(schema.getObjectId()).thenReturn(99L);
        Mockito.when(session.prepareStatement(Mockito.anyString())).thenReturn(statement);
        Mockito.when(statement.executeQuery()).thenReturn(result);
        Mockito.when(result.next()).thenReturn(true, false);
        Mockito.when(result.getString("type")).thenReturn("package body");
        Mockito.when(result.getString("src")).thenReturn("invalid type name");
        Mockito.when(result.getString("definition")).thenReturn(source);
        Mockito.when(result.getInt("line")).thenReturn(3);

        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        Assertions.assertFalse(GaussDBPackageCompiler.readCompilationDiagnostics(
            session, log, object, GaussDBPackageCompileTarget.ALL));

        var diagnostic = (GaussDBPackageCompileError) log.getErrorStack().iterator().next();
        Assertions.assertEquals(3, diagnostic.getLine());
        Assertions.assertTrue(source.split("\\R", -1)[diagnostic.getLine() - 1].contains("missing_dependency"));
        Assertions.assertSame(GaussDBPackageCompileTarget.BODY, diagnostic.getSourcePart());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"type", "src", "definition", "line"})
    void diagnosticColumnReadFailureIsNotInventedAsSourceError(String column) throws Exception {
        var session = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCSession.class);
        var statement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        var result = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet.class);
        Mockito.when(result.getSession()).thenReturn(session);
        var dataSource = Mockito.mock(GaussDBDataSource.class);
        var container = Mockito.mock(org.jkiss.dbeaver.model.DBPDataSourceContainer.class);
        Mockito.when(session.getDataSource()).thenReturn(dataSource);
        Mockito.when(dataSource.getContainer()).thenReturn(container);
        Mockito.when(container.getId()).thenReturn("package-diagnostics-test");
        var object = Mockito.mock(GaussDBPackage.class);
        var schema = Mockito.mock(GaussDBSchema.class);
        Mockito.when(object.getSchema()).thenReturn(schema);
        Mockito.when(object.getObjectId()).thenReturn(42L);
        Mockito.when(schema.getObjectId()).thenReturn(99L);
        Mockito.when(session.prepareStatement(Mockito.anyString())).thenReturn(statement);
        Mockito.when(statement.executeQuery()).thenReturn(result);
        Mockito.when(result.next()).thenReturn(true, false);
        Mockito.when(result.getString("type")).thenReturn("package body");
        Mockito.when(result.getString("src")).thenReturn("actual diagnostic");
        SQLException failure = new SQLException("diagnostic column unavailable", "42703");
        if (column.equals("line")) {
            Mockito.when(result.getInt(column)).thenThrow(failure);
        } else {
            Mockito.when(result.getString(column)).thenThrow(failure);
        }
        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        var actual = Assertions.assertThrows(org.jkiss.dbeaver.DBException.class,
            () -> GaussDBPackageCompiler.readCompilationDiagnostics(session, log, object, GaussDBPackageCompileTarget.ALL));
        Assertions.assertSame(failure, actual.getCause());
        Assertions.assertTrue(log.getErrorStack().isEmpty());
        Mockito.verify(result).close();
        Mockito.verify(statement).close();
        Mockito.verify(statement).setLong(1, 42L);
        Mockito.verify(statement).setLong(2, 99L);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "compile failed near line 2147483648|0",
        "compile failed near line 0|0",
        "compile failed without a location|0",
        "compile failed near line 23|23"
    })
    void malformedLocationPreservesDiagnosticWithoutCrashing(String message, int expectedLine) {
        var error = GaussDBPackageCompiler.toCompileError(new SQLException(message, "42601"),
            GaussDBPackageCompileTarget.BODY);
        Assertions.assertEquals(expectedLine, error.getLine());
        Assertions.assertEquals(message, error.getMessage());
        Assertions.assertSame(GaussDBPackageCompileTarget.BODY, error.getSourcePart());
    }

    @Test
    public void preservesRealLineAndColumnFromServerDiagnostic() {
        var error = GaussDBPackageCompiler.toCompileError(
            new SQLException("compile failed near line 8, column 14", "42601"),
            GaussDBPackageCompileTarget.BODY
        );

        Assertions.assertEquals(8, error.getLine());
        Assertions.assertEquals(14, error.getPosition());
        Assertions.assertFalse(error.getMessage().contains("Approximate"));
    }

    @Test
    public void convertsRealCharacterPositionToSourceLineAndColumn() {
        String source = "CREATE PACKAGE BODY p AS\n  bad_token;\nEND p;";
        int offset = source.indexOf("bad_token") + 1;
        var error = GaussDBPackageCompiler.toCompileError(
            new SQLException("syntax error, Position: " + offset, "42601"),
            GaussDBPackageCompileTarget.BODY,
            source
        );

        Assertions.assertEquals(2, error.getLine());
        Assertions.assertEquals(3, error.getPosition());
        Assertions.assertFalse(error.getMessage().contains("Approximate"));
    }

    @Test
    public void mapsReplayPositionBackToTheUnmodifiedEditorSource() {
        String source = "CREATE PACKAGE BODY p AS bad_token;\nEND p;";
        String executionSql = "CREATE OR REPLACE PACKAGE BODY p AS bad_token;\nEND p;";
        int executionOffset = executionSql.indexOf("bad_token") + 1;
        var error = GaussDBPackageCompiler.toCompileError(
            new SQLException("syntax error, Position: " + executionOffset, "42601"),
            GaussDBPackageCompileTarget.BODY,
            source,
            executionSql
        );

        Assertions.assertEquals(1, error.getLine());
        Assertions.assertEquals(source.indexOf("bad_token") + 1, error.getPosition());
        Assertions.assertFalse(error.getMessage().contains("Approximate"));
    }

    @Test
    public void absolutePositionIsNeverTreatedAsAColumnAndFailedMappingStaysUnknown() {
        String source = "CREATE PACKAGE BODY public.p AS\n  bad_token;\nEND p;";
        String unrelatedExecutionSql = "CREATE OR REPLACE PACKAGE BODY public.other AS bad_token; END other;";
        var error = GaussDBPackageCompiler.toCompileError(
            new SQLException("compile failed near line 2, Position: 45", "42601"),
            GaussDBPackageCompileTarget.BODY,
            source,
            unrelatedExecutionSql
        );

        Assertions.assertEquals(0, error.getLine());
        Assertions.assertEquals(0, error.getPosition());
    }

    @Test
    public void invalidLineMappingDoesNotLeakExecutionCoordinatesIntoEditor() {
        String source = "CREATE PACKAGE BODY public.p AS\nEND p;";
        var error = GaussDBPackageCompiler.toCompileError(
            new SQLException("compile failed near line 99, column 80", "42601"),
            GaussDBPackageCompileTarget.BODY,
            source,
            "CREATE OR REPLACE PACKAGE BODY public.p AS\nEND p;"
        );

        Assertions.assertEquals(0, error.getLine());
        Assertions.assertEquals(0, error.getPosition());
    }

    @Test
    public void uniquelyQuotedInvalidTypeGetsExplicitlyApproximateLocation() {
        String source = "CREATE PACKAGE BODY p AS\n" +
            "  missing app.no_such_table.id%TYPE;\n" +
            "END p;";
        var error = GaussDBPackageCompiler.toCompileError(
            new SQLException("invalid type name \"app.no_such_table.id%TYPE\" LINE 1: DECLARE ...", "42601"),
            GaussDBPackageCompileTarget.BODY,
            source
        );

        Assertions.assertEquals(2, error.getLine());
        Assertions.assertEquals(11, error.getPosition());
        Assertions.assertTrue(error.getMessage().contains("Approximate location"));
        Assertions.assertTrue(error.getMessage().contains("inferred client-side"));
    }

    @Test
    public void repeatedInvalidTypeTokenDoesNotInventLocation() {
        String token = "app.no_such_table.id%TYPE";
        var error = GaussDBPackageCompiler.toCompileError(
            new SQLException("invalid type name \"" + token + "\"", "42601"),
            GaussDBPackageCompileTarget.BODY,
            token + "\n" + token
        );

        Assertions.assertEquals(0, error.getLine());
        Assertions.assertEquals(0, error.getPosition());
        Assertions.assertFalse(error.getMessage().contains("Approximate"));
    }

    @Test
    public void nativeCompileFallbackRequiresExplicitUnsupportedSignalOutsideCentralized() {
        SQLException distributed505 = new SQLException(
            "Distributed does not support this feature.", "42809", 10531);
        Assertions.assertTrue(GaussDBPackageCompiler.isNativeCompileUnsupported(
            distributed505, GaussDBServerInfo.Deployment.DISTRIBUTED));
        Assertions.assertFalse(GaussDBPackageCompiler.isNativeCompileUnsupported(
            new SQLException("feature not supported", "0A000"), GaussDBServerInfo.Deployment.UNKNOWN));
        Assertions.assertFalse(GaussDBPackageCompiler.isNativeCompileUnsupported(
            distributed505, GaussDBServerInfo.Deployment.UNKNOWN));
        Assertions.assertTrue(GaussDBPackageCompiler.isNativeCompileUnsupported(
            new SQLException("分布式场景暂不支持该特性", "42809", 10531),
            GaussDBServerInfo.Deployment.DISTRIBUTED));
        Assertions.assertTrue(GaussDBPackageCompiler.isNativeCompileUnsupported(
            new SQLException("operation rejected", "42809", 10531),
            GaussDBServerInfo.Deployment.CLOUD_NATIVE));
        Assertions.assertFalse(GaussDBPackageCompiler.isNativeCompileUnsupported(
            distributed505, GaussDBServerInfo.Deployment.CENTRALIZED));
        Assertions.assertFalse(GaussDBPackageCompiler.isNativeCompileUnsupported(
            new SQLException("wrong object type", "42809", 0), GaussDBServerInfo.Deployment.DISTRIBUTED));
        Assertions.assertFalse(GaussDBPackageCompiler.isNativeCompileUnsupported(
            new SQLException("feature unsupported", "0A000", 10531),
            GaussDBServerInfo.Deployment.DISTRIBUTED));
    }

    @Test
    public void saveErrorFallbackFindsUniqueInvalidTypeInDirtyEditorText() {
        String token = "public.no_such_table.id%TYPE";
        String dirtySource = "CREATE OR REPLACE PACKAGE BODY public.test_pkg AS\n" +
            "  missing " + token + ";\nEND test_pkg;";
        var wrapped = new org.jkiss.dbeaver.DBException("Save failed",
            new SQLException("invalid type name \"" + token + "\"", "42704", 11019));

        Assertions.assertEquals(dirtySource.indexOf(token),
            GaussDBPackageCompiler.findUniqueInvalidTypeOffset(dirtySource, wrapped));
        Assertions.assertEquals(-1,
            GaussDBPackageCompiler.findUniqueInvalidTypeOffset(token + "\n" + token, wrapped));
    }

    @Test
    public void connectionTerminationAndPermissionErrorsAreNotSourceDiagnostics() {
        for (String state : java.util.List.of(
            "08006", "25P02", "28P01", "40001", "40P01", "42501", "53000", "54000", "55006",
            "55P03", "57014", "57P01", "57P02", "57P03", "58000", "XX000")) {
            Assertions.assertTrue(GaussDBPackageCompiler.isInfrastructureError(state), state);
        }
        Assertions.assertFalse(GaussDBPackageCompiler.isInfrastructureError("42601"));
        Assertions.assertFalse(GaussDBPackageCompiler.isInfrastructureError("42704"));
        Assertions.assertFalse(GaussDBPackageCompiler.isInfrastructureError(null));
    }

    @Test
    public void infrastructureClassificationTraversesCauseNextAndSuppressedChains() {
        SQLException ordinary = new SQLException("ordinary package error", "42601");
        ordinary.setNextException(new SQLException("query canceled", "57014"));
        Assertions.assertTrue(GaussDBPackageCompiler.hasInfrastructureError(ordinary));

        var wrapped = new org.jkiss.dbeaver.DBException("wrapped",
            new SQLException("deadlock", "40P01"));
        Assertions.assertTrue(GaussDBPackageCompiler.hasInfrastructureError(wrapped));

        SQLException withSuppressed = new SQLException("ordinary package error", "42601");
        withSuppressed.addSuppressed(new SQLException("out of memory", "53200"));
        Assertions.assertTrue(GaussDBPackageCompiler.hasInfrastructureError(withSuppressed));

        Assertions.assertFalse(GaussDBPackageCompiler.hasInfrastructureError(
            new org.jkiss.dbeaver.DBException("wrapped", new SQLException("syntax", "42601"))));
    }

    @Test
    public void canceledCompilationDoesNotAccessDatabaseOrProduceErrors() throws Exception {
        var monitor = Mockito.mock(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor.class);
        var log = Mockito.mock(org.jkiss.dbeaver.model.exec.compile.DBCCompileLog.class);
        var object = Mockito.mock(GaussDBPackage.class);
        Mockito.when(monitor.isCanceled()).thenReturn(true);
        Assertions.assertFalse(GaussDBPackageCompiler.compile(monitor, log, object, GaussDBPackageCompileTarget.ALL));
        Mockito.verifyNoInteractions(log, object);
    }

    @Test
    public void successfulNativeCompileUsesValidObjectStateWhenGsErrorsIsMissing() throws Exception {
        CompileFixture fixture = compileFixture();
        Mockito.when(fixture.object().getSpecificationState())
            .thenReturn(org.jkiss.dbeaver.model.struct.DBSObjectState.NORMAL);
        Mockito.when(fixture.session().prepareStatement(Mockito.anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("ALTER PACKAGE")) {
                return fixture.statement();
            }
            throw new SQLException("relation DBE_PLDEVELOPER.GS_ERRORS does not exist", "42P01");
        });

        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        Assertions.assertTrue(GaussDBPackageCompiler.compile(
            fixture.monitor(), log, fixture.object(), GaussDBPackageCompileTarget.ALL));
        Assertions.assertTrue(log.getErrorStack().isEmpty());
        Mockito.verify(fixture.object()).refreshObjectState(fixture.monitor());
        Mockito.verify(fixture.container()).fireEvent(Mockito.any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"INVALID", "UNKNOWN"})
    void missingGsErrorsReportsStateWithoutInventingLine(String stateName)
        throws Exception {
        var state = "INVALID".equals(stateName)
            ? org.jkiss.dbeaver.model.struct.DBSObjectState.INVALID
            : org.jkiss.dbeaver.model.struct.DBSObjectState.UNKNOWN;
        CompileFixture fixture = compileFixture();
        Mockito.when(fixture.object().getSpecificationState())
            .thenReturn(org.jkiss.dbeaver.model.struct.DBSObjectState.NORMAL);
        Mockito.when(fixture.object().getBodyState()).thenReturn(state);
        Mockito.when(fixture.session().prepareStatement(Mockito.anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("ALTER PACKAGE")) {
                return fixture.statement();
            }
            throw new SQLException("relation DBE_PLDEVELOPER.GS_ERRORS does not exist", "42P01");
        });

        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        Assertions.assertFalse(GaussDBPackageCompiler.compile(
            fixture.monitor(), log, fixture.object(), GaussDBPackageCompileTarget.BODY));
        Assertions.assertEquals(1, log.getErrorStack().size());
        var error = (GaussDBPackageCompileError) log.getErrorStack().iterator().next();
        Assertions.assertEquals(0, error.getLine());
        Assertions.assertEquals(0, error.getPosition());
        Assertions.assertSame(GaussDBPackageCompileTarget.BODY, error.getSourcePart());
        Assertions.assertTrue(error.getMessage().contains(
            state == org.jkiss.dbeaver.model.struct.DBSObjectState.INVALID ? "remains invalid" : "validity state"));
    }

    @Test
    public void distributedUnsupportedAlterReplaysBodySourceAndValidatesState() throws Exception {
        CompileFixture fixture = compileFixture();
        SourceReadFixture sourceRead = authoritativeSources(
            fixture,
            "CREATE OR REPLACE PACKAGE public.test_pkg AS END test_pkg;",
            "CREATE OR REPLACE PACKAGE BODY public.test_pkg AS\n" +
                "  FUNCTION f RETURN INTEGER AS BEGIN RETURN 1; END;\nEND test_pkg;",
            true
        );
        var replayStatement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        String source = sourceRead.body();
        Mockito.when(fixture.statement().execute()).thenThrow(
            new SQLException("Distributed does not support this feature.", "42809", 10531));
        Mockito.when(fixture.object().getSpecificationState())
            .thenReturn(org.jkiss.dbeaver.model.struct.DBSObjectState.NORMAL);
        Mockito.when(fixture.object().getBodyState())
            .thenReturn(org.jkiss.dbeaver.model.struct.DBSObjectState.NORMAL);
        Mockito.when(sourceRead.session().prepareStatement(source)).thenReturn(replayStatement);

        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        Assertions.assertTrue(GaussDBPackageCompiler.compile(
            fixture.monitor(), log, fixture.object(), GaussDBPackageCompileTarget.BODY));
        Assertions.assertTrue(log.getErrorStack().isEmpty());
        Mockito.verify(replayStatement).execute();
        Mockito.verify(sourceRead.session()).prepareStatement(
            Mockito.eq("SELECT pg_catalog.pg_advisory_xact_lock(?)"));
        var authorityOrder = Mockito.inOrder(sourceRead.session());
        authorityOrder.verify(sourceRead.session()).prepareStatement(
            "SELECT pg_catalog.pg_advisory_xact_lock(?)");
        authorityOrder.verify(sourceRead.session()).prepareStatement(
            Mockito.contains("DBE_PLDEVELOPER.GS_SOURCE"));
        Mockito.verify(fixture.object()).refreshObjectState(fixture.monitor());
        Mockito.verify(fixture.context(), Mockito.times(1))
            .openSession(Mockito.any(), Mockito.any(), Mockito.anyString());
        Mockito.verify(fixture.database(), Mockito.times(2)).openIsolatedContext(
            Mockito.any(), Mockito.anyString(), Mockito.isNull());
        Mockito.verify(fixture.database(), Mockito.never())
            .getDefaultContext(Mockito.any(), Mockito.anyBoolean());
        Mockito.verify(fixture.session()).setAutoCommit(true);
        Mockito.verify(fixture.session()).setAutoCommit(false);
        Mockito.verify(fixture.isolatedContext()).close();
        Mockito.verify(fixture.session()).close();
        Mockito.verify(sourceRead.session()).close();
        Mockito.verify(fixture.object(), Mockito.never()).getBodyText(Mockito.any());
        Mockito.verify(fixture.object(), Mockito.never()).getDeclarationText(Mockito.any());
    }

    @Test
    public void distributedReplayFailureKeepsPreciseApproximateDiagnostic() throws Exception {
        CompileFixture fixture = compileFixture();
        String token = "public.no_such_table.id%TYPE";
        String source = "CREATE PACKAGE BODY public.test_pkg AS missing " + token + ";\nEND test_pkg;";
        SourceReadFixture sourceRead = authoritativeSources(
            fixture,
            "CREATE PACKAGE public.test_pkg AS END test_pkg;",
            source,
            true
        );
        var replayStatement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        String executionSql = "CREATE OR REPLACE PACKAGE BODY public.test_pkg AS missing " + token +
            ";\nEND test_pkg;";
        Mockito.when(fixture.statement().execute()).thenThrow(
            new SQLException("Distributed does not support this feature.", "42809", 10531));
        Mockito.when(sourceRead.session().prepareStatement(executionSql)).thenReturn(replayStatement);
        Mockito.when(replayStatement.execute()).thenThrow(
            new SQLException("invalid type name \"" + token + "\"", "42601"));

        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        Assertions.assertFalse(GaussDBPackageCompiler.compile(
            fixture.monitor(), log, fixture.object(), GaussDBPackageCompileTarget.BODY));
        Assertions.assertEquals(1, log.getErrorStack().size());
        var error = (GaussDBPackageCompileError) log.getErrorStack().iterator().next();
        Assertions.assertEquals(1, error.getLine());
        Assertions.assertEquals(source.indexOf(token) + 1, error.getPosition());
        Assertions.assertTrue(error.getMessage().contains("Approximate location"));
        Mockito.verify(fixture.object()).refreshObjectState(fixture.monitor());
    }

    @Test
    public void serializationFailureDuringReplayRollsBackWithoutRefreshingState() throws Exception {
        CompileFixture fixture = compileFixture();
        String source = "CREATE PACKAGE BODY public.test_pkg AS END test_pkg;";
        SourceReadFixture sourceRead = authoritativeSources(
            fixture, "CREATE PACKAGE public.test_pkg AS END test_pkg;", source, true);
        var replayStatement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        Mockito.when(fixture.statement().execute()).thenThrow(
            new SQLException("Distributed does not support this feature.", "42809", 10531));
        Mockito.when(sourceRead.session().prepareStatement(
            "CREATE OR REPLACE PACKAGE BODY public.test_pkg AS END test_pkg;")).thenReturn(replayStatement);
        SQLException serializationFailure = new SQLException(
            "could not serialize access due to concurrent update", "40001");
        Mockito.when(replayStatement.execute()).thenThrow(serializationFailure);

        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        var failure = Assertions.assertThrows(org.jkiss.dbeaver.DBException.class, () ->
            GaussDBPackageCompiler.compile(
                fixture.monitor(), log, fixture.object(), GaussDBPackageCompileTarget.BODY));
        Assertions.assertSame(serializationFailure, failure.getCause());
        Assertions.assertTrue(log.getErrorStack().isEmpty());
        Mockito.verify(sourceRead.session()).setTransactionIsolation(
            java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        Mockito.verify(sourceRead.session()).setAutoCommit(false);
        Mockito.verify(sourceRead.session()).rollback();
        Mockito.verify(sourceRead.session(), Mockito.never()).commit();
        Mockito.verify(fixture.object(), Mockito.never()).refreshObjectState(Mockito.any());
    }

    @Test
    public void isolatedManualCommitContextIsSupportedAndRestoredWithoutChangingPreference() throws Exception {
        CompileFixture fixture = compileFixture();
        String source = "CREATE PACKAGE BODY public.test_pkg AS END test_pkg;";
        SourceReadFixture sourceRead = authoritativeSources(
            fixture, "CREATE PACKAGE public.test_pkg AS END test_pkg;", source, true);
        Mockito.when(sourceRead.session().getAutoCommit()).thenReturn(false);
        var replayStatement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        Mockito.when(fixture.statement().execute()).thenThrow(
            new SQLException("Distributed does not support this feature.", "42809", 10531));
        Mockito.when(sourceRead.session().prepareStatement(
            "CREATE OR REPLACE PACKAGE BODY public.test_pkg AS END test_pkg;")).thenReturn(replayStatement);
        Mockito.when(fixture.object().getSpecificationState())
            .thenReturn(org.jkiss.dbeaver.model.struct.DBSObjectState.NORMAL);
        Mockito.when(fixture.object().getBodyState())
            .thenReturn(org.jkiss.dbeaver.model.struct.DBSObjectState.NORMAL);

        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        Assertions.assertTrue(GaussDBPackageCompiler.compile(
            fixture.monitor(), log, fixture.object(), GaussDBPackageCompileTarget.BODY));

        Mockito.verify(sourceRead.session()).setTransactionIsolation(
            java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        Mockito.verify(sourceRead.session()).commit();
        Mockito.verify(sourceRead.session(), Mockito.never()).rollback();
        Mockito.verify(sourceRead.session(), Mockito.never()).setAutoCommit(Mockito.anyBoolean());
        Mockito.verify(sourceRead.session()).setTransactionIsolation(
            java.sql.Connection.TRANSACTION_READ_COMMITTED);
    }

    @Test
    public void allReplayFailsClosedBeforeAnyWriteWhenBodySourceAndStateDisagree() throws Exception {
        CompileFixture fixture = compileFixture();
        String specification = "CREATE PACKAGE public.test_pkg AS END test_pkg;";
        String body = "CREATE PACKAGE BODY public.test_pkg AS END test_pkg;";
        SourceReadFixture sourceRead = authoritativeSources(fixture, specification, body, false);
        Mockito.when(fixture.statement().execute()).thenThrow(
            new SQLException("Distributed does not support this feature.", "42809", 10531));

        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        Assertions.assertFalse(GaussDBPackageCompiler.compile(
            fixture.monitor(), log, fixture.object(), GaussDBPackageCompileTarget.ALL));
        Assertions.assertEquals(1, log.getErrorStack().size());
        Assertions.assertTrue(log.getErrorStack().iterator().next().getMessage().contains("inconsistent"));
        Mockito.verify(fixture.context(), Mockito.times(1))
            .openSession(Mockito.any(), Mockito.any(), Mockito.anyString());
    }

    @Test
    public void allReplayFailsClosedBeforeAnyWriteWhenCachedBodyDisappearedOnServer() throws Exception {
        CompileFixture fixture = compileFixture();
        Mockito.when(fixture.object().isBodyPresent()).thenReturn(true);
        SourceReadFixture sourceRead = authoritativeSources(
            fixture, "CREATE PACKAGE public.test_pkg AS END test_pkg;", null, false);
        Mockito.when(fixture.statement().execute()).thenThrow(
            new SQLException("Distributed does not support this feature.", "42809", 10531));

        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        Assertions.assertFalse(GaussDBPackageCompiler.compile(
            fixture.monitor(), log, fixture.object(), GaussDBPackageCompileTarget.ALL));
        Assertions.assertEquals(1, log.getErrorStack().size());
        Assertions.assertTrue(log.getErrorStack().iterator().next().getMessage().contains("inconsistent"));
        // Only the native ALTER attempt and the read-only authority check are allowed.
        // In particular, the specification must not be replayed before BODY is proven complete.
        Mockito.verify(fixture.context(), Mockito.times(1))
            .openSession(Mockito.any(), Mockito.any(), Mockito.anyString());
        Mockito.verify(fixture.object(), Mockito.never()).getDeclarationText(Mockito.any());
        Mockito.verify(fixture.object(), Mockito.never()).getBodyText(Mockito.any());
    }

    @Test
    public void allReplayStopsAfterSpecificationFailure() throws Exception {
        CompileFixture fixture = compileFixture();
        String specification = "CREATE OR REPLACE PACKAGE public.test_pkg AS END test_pkg;";
        SourceReadFixture sourceRead = authoritativeSources(
            fixture, specification,
            "CREATE OR REPLACE PACKAGE BODY public.test_pkg AS END test_pkg;", true);
        var specificationStatement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        Mockito.when(fixture.statement().execute()).thenThrow(
            new SQLException("Distributed does not support this feature.", "42809", 10531));
        Mockito.when(sourceRead.session().prepareStatement(specification)).thenReturn(specificationStatement);
        Mockito.when(specificationStatement.execute()).thenThrow(new SQLException("bad specification", "42601"));

        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        Assertions.assertFalse(GaussDBPackageCompiler.compile(
            fixture.monitor(), log, fixture.object(), GaussDBPackageCompileTarget.ALL));
        Assertions.assertEquals(1, log.getErrorStack().size());
        Mockito.verify(fixture.context(), Mockito.times(1))
            .openSession(Mockito.any(), Mockito.any(), Mockito.anyString());
    }

    @Test
    public void replayCleanupFailureAfterSuccessfulExecutionIsNotSourceDiagnostic() throws Exception {
        CompileFixture fixture = compileFixture();
        String source = "CREATE OR REPLACE PACKAGE BODY public.test_pkg AS END test_pkg;";
        SourceReadFixture sourceRead = authoritativeSources(
            fixture, "CREATE OR REPLACE PACKAGE public.test_pkg AS END test_pkg;", source, true);
        var replayStatement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        SQLException cleanupFailure = new SQLException("close failed", "08006");
        Mockito.when(fixture.statement().execute()).thenThrow(
            new SQLException("Distributed does not support this feature.", "42809", 10531));
        Mockito.when(sourceRead.session().prepareStatement(source)).thenReturn(replayStatement);
        Mockito.doThrow(cleanupFailure).when(replayStatement).close();

        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        var error = Assertions.assertThrows(org.jkiss.dbeaver.DBException.class, () ->
            GaussDBPackageCompiler.compile(fixture.monitor(), log, fixture.object(), GaussDBPackageCompileTarget.BODY));
        Assertions.assertSame(cleanupFailure, error.getCause());
        Assertions.assertTrue(log.getErrorStack().isEmpty());
        Mockito.verify(fixture.object(), Mockito.never()).refreshObjectState(Mockito.any());
    }

    @Test
    public void catalogErrorsPreserveBodyLineAndFilterSpecification() throws Exception {
        var session = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCSession.class);
        var statement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        var result = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet.class);
        var object = Mockito.mock(GaussDBPackage.class);
        var schema = Mockito.mock(GaussDBSchema.class);
        Mockito.when(object.getObjectId()).thenReturn(42L);
        Mockito.when(object.getSchema()).thenReturn(schema);
        Mockito.when(schema.getObjectId()).thenReturn(99L);
        Mockito.when(session.prepareStatement(Mockito.anyString())).thenReturn(statement);
        Mockito.when(statement.executeQuery()).thenReturn(result);
        Mockito.when(result.next()).thenReturn(true, false);
        Mockito.when(result.getString("type")).thenReturn("package body");
        Mockito.when(result.getString("src")).thenReturn("invalid type name");
        Mockito.when(result.getInt("line")).thenReturn(3);
        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        Assertions.assertFalse(GaussDBPackageCompiler.logErrors(session, log, object, GaussDBPackageCompileTarget.BODY));
        Assertions.assertEquals(3, log.getErrorStack().iterator().next().getLine());
        Mockito.verify(statement).setLong(1, 42L);
        Mockito.verify(statement).setLong(2, 99L);
        Mockito.when(result.next()).thenReturn(true, false);
        log.clearLog();
        Assertions.assertTrue(GaussDBPackageCompiler.logErrors(session, log, object, GaussDBPackageCompileTarget.SPECIFICATION));
        Assertions.assertTrue(log.getErrorStack().isEmpty());
    }

    @Test
    public void allCompilationPreservesMixedSourcePartsAndLineNumbers() throws Exception {
        var session = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCSession.class);
        var statement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        var result = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet.class);
        var object = Mockito.mock(GaussDBPackage.class);
        var schema = Mockito.mock(GaussDBSchema.class);
        Mockito.when(object.getSchema()).thenReturn(schema);
        Mockito.when(session.prepareStatement(Mockito.anyString())).thenReturn(statement);
        Mockito.when(statement.executeQuery()).thenReturn(result);
        Mockito.when(result.next()).thenReturn(true, true, false);
        Mockito.when(result.getString("type")).thenReturn("package", "package body");
        Mockito.when(result.getString("src")).thenReturn("spec error", "body error");
        Mockito.when(result.getInt("line")).thenReturn(2, 3);
        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        Assertions.assertFalse(GaussDBPackageCompiler.logErrors(session, log, object, GaussDBPackageCompileTarget.ALL));
        var errors = log.getErrorStack().iterator();
        var spec = (GaussDBPackageCompileError) errors.next();
        var body = (GaussDBPackageCompileError) errors.next();
        Assertions.assertEquals(GaussDBPackageCompileTarget.SPECIFICATION, spec.getSourcePart());
        Assertions.assertEquals(2, spec.getLine());
        Assertions.assertEquals(GaussDBPackageCompileTarget.BODY, body.getSourcePart());
        Assertions.assertEquals(3, body.getLine());
        Assertions.assertFalse(errors.hasNext());
    }

    @Test
    public void generatesAllCompileVariantsWithQualifiedName() {
        GaussDBPackage object = Mockito.mock(GaussDBPackage.class);
        Mockito.when(object.getFullyQualifiedName(DBPEvaluationContext.DDL)).thenReturn("\"Mixed Schema\".\"Test Package\"");

        Assertions.assertEquals(
            "ALTER PACKAGE \"Mixed Schema\".\"Test Package\" COMPILE",
            GaussDBPackageCompiler.getCompileSQL(object, GaussDBPackageCompileTarget.ALL)
        );
        Assertions.assertEquals(
            "ALTER PACKAGE \"Mixed Schema\".\"Test Package\" COMPILE SPECIFICATION",
            GaussDBPackageCompiler.getCompileSQL(object, GaussDBPackageCompileTarget.SPECIFICATION)
        );
        Assertions.assertEquals(
            "ALTER PACKAGE \"Mixed Schema\".\"Test Package\" COMPILE BODY",
            GaussDBPackageCompiler.getCompileSQL(object, GaussDBPackageCompileTarget.BODY)
        );
    }

    @Test
    public void replaySourceIsAlwaysCreateOrReplaceWithoutTouchingCommentsOrExistingClause() {
        Assertions.assertEquals(
            "-- package source\nCREATE OR REPLACE PACKAGE p AS END p;",
            GaussDBPackageCompiler.asCreateOrReplace("-- package source\nCREATE PACKAGE p AS END p;")
        );
        Assertions.assertEquals(
            "/* source */ CREATE OR REPLACE PACKAGE BODY p AS END p;",
            GaussDBPackageCompiler.asCreateOrReplace("/* source */ CREATE OR REPLACE PACKAGE BODY p AS END p;")
        );
        Assertions.assertEquals(
            "/* outer /* nested */ source */ CREATE OR REPLACE PACKAGE p AS END p;",
            GaussDBPackageCompiler.asCreateOrReplace(
                "/* outer /* nested */ source */ CREATE PACKAGE p AS END p;")
        );
        Assertions.assertEquals(
            "CREATE OR /* retained */ REPLACE PACKAGE p AS END p;",
            GaussDBPackageCompiler.asCreateOrReplace(
                "CREATE OR /* retained */ REPLACE PACKAGE p AS END p;")
        );
        Assertions.assertEquals(
            "CREATE PACKAGES p AS END p;",
            GaussDBPackageCompiler.asCreateOrReplace("CREATE PACKAGES p AS END p;")
        );
        Assertions.assertEquals(
            "SELECT 'CREATE PACKAGE p'",
            GaussDBPackageCompiler.asCreateOrReplace("SELECT 'CREATE PACKAGE p'")
        );
    }

    @Test
    public void replaySourceMustBeQualifiedAndMatchSchemaObjectAndPart() {
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "/* leading */ CREATE OR /* safe */ REPLACE PACKAGE BODY public.test_pkg AS END test_pkg;",
            "public", "test_pkg", GaussDBPackageCompileTarget.BODY));
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE \"Mixed Schema\".\"Mixed Package\" AS END \"Mixed Package\";",
            "Mixed Schema", "Mixed Package", GaussDBPackageCompileTarget.SPECIFICATION));
        Assertions.assertFalse(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE \"Schema\".\"A\"\"B\" AS END \"A\"\"B\"; / -- client delimiter",
            "Schema", "A\"B", GaussDBPackageCompileTarget.SPECIFICATION));
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE \"Schema\".\"A\"\"B\" AS END \"A\"\"B\"; -- trailing comment",
            "Schema", "A\"B", GaussDBPackageCompileTarget.SPECIFICATION));
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE BODY public.test_pkg AS " +
                "FUNCTION value_of RETURN INTEGER AS BEGIN " +
                "IF 1=1 THEN RETURN CASE WHEN 2=2 THEN 42 ELSE 0 END; END IF; " +
                "END value_of; BEGIN NULL; END test_pkg;",
            "public", "test_pkg", GaussDBPackageCompileTarget.BODY));
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE BODY public.test_pkg AS " +
                "g int := CASE WHEN 1=1 THEN 1 ELSE 0 END + 0; END test_pkg;",
            "public", "test_pkg", GaussDBPackageCompileTarget.BODY));
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE public.test_pkg AS " +
                "PROCEDURE p(x int DEFAULT CAST(1 AS int)); END test_pkg;",
            "public", "test_pkg", GaussDBPackageCompileTarget.SPECIFICATION));
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE BODY public.test_pkg AS " +
                "FUNCTION f RETURN INTEGER AS v INTEGER := 0; BEGIN " +
                "<<outer_loop>> LOOP v := v + 1; EXIT WHEN v > 1; END LOOP outer_loop; " +
                "RETURN v; END f; END test_pkg;",
            "public", "test_pkg", GaussDBPackageCompileTarget.BODY));
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE BODY public.test_pkg AS " +
                "FUNCTION test_pkg RETURN INTEGER AS BEGIN RETURN 1; END test_pkg; END test_pkg;",
            "public", "test_pkg", GaussDBPackageCompileTarget.BODY));
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE BODY public.test_pkg AS " +
                "FUNCTION f RETURN INTEGER AS BEGIN <<test_pkg>> BEGIN NULL; END test_pkg; " +
                "RETURN 1; END f; END test_pkg;",
            "public", "test_pkg", GaussDBPackageCompileTarget.BODY));
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE BODY public.test_pkg AS " +
                "FUNCTION outer_f RETURN INTEGER AS " +
                "FUNCTION inner_f RETURN INTEGER AS BEGIN RETURN 1; END inner_f; " +
                "v INTEGER := 0; BEGIN FOR i IN 1..2 LOOP IF i = 1 THEN " +
                "CASE i WHEN 1 THEN v := inner_f(); ELSE v := 0; END CASE; " +
                "END IF; END LOOP; RETURN v; END outer_f; " +
                "BEGIN NULL; END test_pkg;",
            "public", "test_pkg", GaussDBPackageCompileTarget.BODY));
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE BODY public.test_pkg AS " +
                "g text := $tag$ END test_pkg; DROP TABLE public.t; $tag$; END test_pkg;",
            "public", "test_pkg", GaussDBPackageCompileTarget.BODY));
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE BODY public.test_pkg AS " +
                "g text := $$ END test_pkg; DROP TABLE public.t; $$; END test_pkg;",
            "public", "test_pkg", GaussDBPackageCompileTarget.BODY));
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE BODY public.test_pkg AS " +
                "g text := E'payload\\' END test_pkg; DROP TABLE public.t;'; END test_pkg;",
            "public", "test_pkg", GaussDBPackageCompileTarget.BODY));
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE BODY public.test_pkg AS " +
                "g text := e'payload\\' END test_pkg; DROP TABLE public.t;'; END test_pkg;",
            "public", "test_pkg", GaussDBPackageCompileTarget.BODY));
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE BODY public.test_pkg AS " +
                "g text := 'payload'' END test_pkg; DROP TABLE public.t;'; END test_pkg;",
            "public", "test_pkg", GaussDBPackageCompileTarget.BODY));
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE BODY public.test_pkg AS " +
                "g text := $_tag9$ END test_pkg; DROP TABLE public.t; $_tag9$; END test_pkg;",
            "public", "test_pkg", GaussDBPackageCompileTarget.BODY));
        for (String unsafe : java.util.List.of(
            "SELECT 'CREATE PACKAGE BODY public.test_pkg'",
            "CREATE PACKAGE BODY test_pkg AS END test_pkg;",
            "CREATE PACKAGE BODY other.test_pkg AS END test_pkg;",
            "CREATE PACKAGE BODY public.other_pkg AS END other_pkg;",
            "CREATE PACKAGE catalog.public.test_pkg AS END test_pkg;",
            "CREATE PACKAGE BODY public.test_pkg AS END test_pkg; SELECT 1;",
            "CREATE PACKAGE BODY public.test_pkg AS END test_pkg; DROP TABLE public.t;",
            "CREATE PACKAGE BODY public.test_pkg AS END test_pkg; BEGIN NULL; END test_pkg;",
            "CREATE PACKAGE BODY public.test_pkg AS END; DROP TABLE public.t; " +
                "CREATE PACKAGE BODY public.test_pkg AS END test_pkg;",
            "CREATE PACKAGE BODY public.test_pkg AS " +
                "g text := 'payload\\'; END test_pkg; DROP TABLE public.t; -- '\nEND test_pkg;",
            "CREATE PACKAGE BODY public.test_pkg AS " +
                "g text := $1tag$ END test_pkg; DROP TABLE public.t; $1tag$; END test_pkg;",
            "CREATE PACKAGE BODY public.test_pkg AS g text := $tag$ never closed; END test_pkg;",
            "CREATE PACKAGE BODY public.test_pkg AS BEGIN NULL; END; BEGIN NULL; END test_pkg;",
            "CREATE PACKAGE BODY public.test_pkg AS " +
                "g int := CASE WHEN 1=1 THEN 1 ELSE 0 END + 0; END; " +
                "DROP TABLE public.t; CREATE PACKAGE BODY public.test_pkg AS END test_pkg;",
            "CREATE PACKAGE public.test_pkg AS " +
                "PROCEDURE p(x int DEFAULT CAST(1 AS int)); END; " +
                "DROP TABLE public.t; CREATE PACKAGE public.test_pkg AS END test_pkg;",
            "CREATE PACKAGE BODY public.test_pkg AS END test_pkg; " +
                "CREATE PACKAGE BODY public.test_pkg AS END test_pkg;",
            "CREATE PACKAGE BODY public.test_pkg AS END;",
            "CREATE PACKAGE BODY public.test_pkg AS END test_pkg; /* unterminated"
        )) {
            Assertions.assertFalse(GaussDBPackageCompiler.isReplaySourceForObject(
                unsafe, "public", "test_pkg", GaussDBPackageCompileTarget.BODY), unsafe);
        }
        Assertions.assertFalse(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE public.test_pkg AS END test_pkg;",
            "public", "test_pkg", GaussDBPackageCompileTarget.BODY));
        Assertions.assertFalse(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE MixedSchema.MixedPkg AS END MixedPkg;",
            "MixedSchema", "MixedPkg", GaussDBPackageCompileTarget.SPECIFICATION));
        Assertions.assertTrue(GaussDBPackageCompiler.isReplaySourceForObject(
            "CREATE PACKAGE mixedschema.mixedpkg AS END mixedpkg;",
            "mixedschema", "mixedpkg", GaussDBPackageCompileTarget.SPECIFICATION));
    }

    @Test
    public void specificationReplayIgnoresExistingInvalidBodyState() throws Exception {
        CompileFixture fixture = compileFixture();
        SourceReadFixture sourceRead = authoritativeSources(
            fixture,
            "CREATE PACKAGE public.test_pkg AS END test_pkg;",
            "CREATE PACKAGE BODY public.test_pkg AS END test_pkg;",
            true
        );
        var replayStatement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        Mockito.when(fixture.statement().execute()).thenThrow(
            new SQLException("Distributed does not support this feature.", "42809", 10531));
        Mockito.when(sourceRead.session().prepareStatement(
            "CREATE OR REPLACE PACKAGE public.test_pkg AS END test_pkg;")).thenReturn(replayStatement);
        Mockito.when(fixture.object().getSpecificationState())
            .thenReturn(org.jkiss.dbeaver.model.struct.DBSObjectState.NORMAL);
        Mockito.when(fixture.object().getBodyState())
            .thenReturn(org.jkiss.dbeaver.model.struct.DBSObjectState.INVALID);

        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        Assertions.assertTrue(GaussDBPackageCompiler.compile(
            fixture.monitor(), log, fixture.object(), GaussDBPackageCompileTarget.SPECIFICATION));
        Assertions.assertTrue(log.getErrorStack().isEmpty());
        Mockito.verify(fixture.object(), Mockito.never()).getBodyState();
    }

    @Test
    public void allReplayUsesFreshServerSourcesAndRequiresBothValidStates() throws Exception {
        CompileFixture fixture = compileFixture();
        String freshSpecification = "CREATE PACKAGE public.test_pkg AS END test_pkg;";
        String freshBody = "CREATE PACKAGE BODY public.test_pkg AS fresh_value INTEGER; END test_pkg;";
        SourceReadFixture sourceRead = authoritativeSources(fixture, freshSpecification, freshBody, true);
        var specificationStatement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        var bodyStatement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        String replayedSpecification = GaussDBPackageCompiler.asCreateOrReplace(freshSpecification);
        String replayedBody = GaussDBPackageCompiler.asCreateOrReplace(freshBody);
        Mockito.when(fixture.statement().execute()).thenThrow(
            new SQLException("Distributed does not support this feature.", "42809", 10531));
        Mockito.when(fixture.object().getDeclarationText(fixture.monitor())).thenReturn(
            "CREATE PACKAGE public.test_pkg AS stale_value INTEGER; END test_pkg;");
        Mockito.when(fixture.object().getBodyText(fixture.monitor())).thenReturn(
            "CREATE PACKAGE BODY public.test_pkg AS stale_value INTEGER; END test_pkg;");
        Mockito.when(fixture.object().getSpecificationState())
            .thenReturn(org.jkiss.dbeaver.model.struct.DBSObjectState.NORMAL);
        Mockito.when(fixture.object().getBodyState())
            .thenReturn(org.jkiss.dbeaver.model.struct.DBSObjectState.NORMAL);
        Mockito.when(sourceRead.session().prepareStatement(replayedSpecification)).thenReturn(specificationStatement);
        Mockito.when(sourceRead.session().prepareStatement(replayedBody)).thenReturn(bodyStatement);

        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        Assertions.assertTrue(GaussDBPackageCompiler.compile(
            fixture.monitor(), log, fixture.object(), GaussDBPackageCompileTarget.ALL));
        Mockito.verify(specificationStatement).execute();
        Mockito.verify(bodyStatement).execute();
        Mockito.verify(fixture.object(), Mockito.never()).getDeclarationText(Mockito.any());
        Mockito.verify(fixture.object(), Mockito.never()).getBodyText(Mockito.any());
    }

    @Test
    public void allReplayDoesNotAcceptMissingBodyStateAfterWrites() throws Exception {
        CompileFixture fixture = compileFixture();
        SourceReadFixture sourceRead = authoritativeSources(
            fixture,
            "CREATE PACKAGE public.test_pkg AS END test_pkg;",
            "CREATE PACKAGE BODY public.test_pkg AS END test_pkg;",
            true
        );
        var specificationStatement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        var bodyStatement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        Mockito.when(fixture.statement().execute()).thenThrow(
            new SQLException("Distributed does not support this feature.", "42809", 10531));
        Mockito.when(sourceRead.session().prepareStatement(
            "CREATE OR REPLACE PACKAGE public.test_pkg AS END test_pkg;")).thenReturn(specificationStatement);
        Mockito.when(sourceRead.session().prepareStatement(
            "CREATE OR REPLACE PACKAGE BODY public.test_pkg AS END test_pkg;")).thenReturn(bodyStatement);
        Mockito.when(fixture.object().getSpecificationState())
            .thenReturn(org.jkiss.dbeaver.model.struct.DBSObjectState.NORMAL);
        Mockito.when(fixture.object().getBodyState())
            .thenReturn(org.jkiss.dbeaver.model.struct.DBSObjectState.UNKNOWN);

        var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
        Assertions.assertFalse(GaussDBPackageCompiler.compile(
            fixture.monitor(), log, fixture.object(), GaussDBPackageCompileTarget.ALL));
        Assertions.assertTrue(log.getErrorStack().iterator().next().getMessage().contains("validity state"));
    }

    @Test
    public void compileErrorUsesLineLevelLocationAndSourcePart() {
        GaussDBPackageCompileError error = new GaussDBPackageCompileError(
            GaussDBPackageCompileTarget.BODY,
            "undefined identifier",
            17
        );

        Assertions.assertTrue(error.isError());
        Assertions.assertEquals(17, error.getLine());
        Assertions.assertEquals(1, error.getPosition());
        Assertions.assertSame(GaussDBPackageCompileTarget.BODY, error.getSourcePart());
    }

    @Test
    public void extractsDirectServerErrorLocationWhenCompilationThrows() {
        GaussDBPackageCompileError error = GaussDBPackageCompiler.toCompileError(
            new SQLException("compilation of PL/pgSQL package body near line 23: undefined identifier", "42601"),
            GaussDBPackageCompileTarget.ALL
        );

        Assertions.assertEquals(23, error.getLine());
        Assertions.assertSame(GaussDBPackageCompileTarget.ALL, error.getSourcePart());
    }

    @Test
    public void missingOrDeniedDiagnosticsAreNotSourceErrors() throws Exception {
        for (String state : java.util.List.of("42P01", "42501", "08006")) {
            var session = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCSession.class);
            var failure = new SQLException("diagnostics unavailable", state);
            Mockito.when(session.prepareStatement(Mockito.anyString())).thenThrow(failure);
            var log = new org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase();
            var error = Assertions.assertThrows(org.jkiss.dbeaver.DBException.class,
                () -> GaussDBPackageCompiler.readCompilationDiagnostics(session, log,
                    Mockito.mock(GaussDBPackage.class), GaussDBPackageCompileTarget.ALL));
            Assertions.assertSame(failure, error.getCause());
            Assertions.assertTrue(error.getMessage().contains("diagnostics"));
            Assertions.assertTrue(log.getErrorStack().isEmpty());
        }
    }

    @Test
    public void explicitTargetIsPreservedButAllNeverGuessesFromErrorText() {
        var failure = new SQLException("referenced package body failed near line 7", "42601");
        Assertions.assertEquals(GaussDBPackageCompileTarget.SPECIFICATION,
            GaussDBPackageCompiler.toCompileError(failure, GaussDBPackageCompileTarget.SPECIFICATION).getSourcePart());
        Assertions.assertEquals(GaussDBPackageCompileTarget.ALL,
            GaussDBPackageCompiler.toCompileError(failure, GaussDBPackageCompileTarget.ALL).getSourcePart());
    }

    private static CompileFixture compileFixture() throws Exception {
        var session = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCSession.class);
        var statement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        var object = Mockito.mock(GaussDBPackage.class);
        var schema = Mockito.mock(GaussDBSchema.class);
        var database = Mockito.mock(GaussDBDatabase.class);
        var context = Mockito.mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreExecutionContext.class);
        var isolatedContext = Mockito.mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreExecutionContext.class);
        var dataSource = Mockito.mock(GaussDBDataSource.class);
        var container = Mockito.mock(org.jkiss.dbeaver.model.DBPDataSourceContainer.class);
        var monitor = new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor();
        Mockito.when(object.getParentObject()).thenReturn(schema);
        Mockito.when(object.getSchema()).thenReturn(schema);
        Mockito.when(object.getDatabase()).thenReturn(database);
        Mockito.when(object.getObjectId()).thenReturn(42L);
        Mockito.when(object.getName()).thenReturn("test_pkg");
        Mockito.when(schema.getObjectId()).thenReturn(99L);
        Mockito.when(schema.getName()).thenReturn("public");
        Mockito.when(schema.getParentObject()).thenReturn(database);
        Mockito.when(database.isInstanceConnected()).thenReturn(true);
        Mockito.when(database.getDefaultContext(Mockito.any(), Mockito.anyBoolean())).thenReturn(context);
        Mockito.when(database.openIsolatedContext(Mockito.any(), Mockito.anyString(), Mockito.isNull()))
            .thenReturn(context, isolatedContext);
        Mockito.when(context.openSession(Mockito.any(), Mockito.any(), Mockito.anyString())).thenReturn(session);
        Mockito.when(object.getDataSource()).thenReturn(dataSource);
        Mockito.when(dataSource.getContainer()).thenReturn(container);
        Mockito.when(dataSource.getServerInfo()).thenReturn(GaussDBServerInfo.forTest(
            GaussDBServerInfo.Deployment.DISTRIBUTED, java.util.Set.of("gs_package")));
        Mockito.when(object.getFullyQualifiedName(DBPEvaluationContext.DDL)).thenReturn("public.test_pkg");
        Mockito.when(session.prepareStatement(Mockito.startsWith("ALTER PACKAGE"))).thenReturn(statement);
        return new CompileFixture(session, statement, object, database, context, isolatedContext, container, monitor);
    }

    private static SourceReadFixture authoritativeSources(
        CompileFixture fixture,
        String specification,
        String body,
        boolean bodyStatePresent
    ) throws Exception {
        var session = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCSession.class);
        var lockStatement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        var statement = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        var result = Mockito.mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet.class);
        Mockito.when(session.getAutoCommit()).thenReturn(true);
        Mockito.when(session.getTransactionIsolation()).thenReturn(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        Mockito.when(fixture.isolatedContext().openSession(
            Mockito.any(), Mockito.any(), Mockito.anyString())).thenReturn(session);
        Mockito.when(session.prepareStatement("SELECT pg_catalog.pg_advisory_xact_lock(?)"))
            .thenReturn(lockStatement);
        Mockito.when(session.prepareStatement(Mockito.contains("DBE_PLDEVELOPER.GS_SOURCE"))).thenReturn(statement);
        Mockito.when(statement.executeQuery()).thenReturn(result);
        boolean hasBodySource = body != null;
        if (hasBodySource) {
            Mockito.when(result.next()).thenReturn(true, true, false);
        } else {
            Mockito.when(result.next()).thenReturn(true, false);
        }
        Mockito.when(result.getLong("oid")).thenReturn(42L);
        Mockito.when(result.getLong("pkgnamespace")).thenReturn(99L);
        Mockito.when(result.getString("pkgname")).thenReturn("test_pkg");
        Mockito.when(result.getLong("spec_oid")).thenReturn(42L);
        Mockito.when(result.getString("spec_valid")).thenReturn("true");
        Mockito.when(result.getLong("body_oid")).thenReturn(bodyStatePresent ? 42L : 0L);
        Mockito.when(result.getString("body_valid")).thenReturn(bodyStatePresent ? "true" : null);
        if (hasBodySource) {
            Mockito.when(result.getString("type")).thenReturn("package", "package body");
            Mockito.when(result.getString("src")).thenReturn(specification, body);
        } else {
            Mockito.when(result.getString("type")).thenReturn("package");
            Mockito.when(result.getString("src")).thenReturn(specification);
        }
        return new SourceReadFixture(session, statement, result, specification, body);
    }

    private record CompileFixture(
        org.jkiss.dbeaver.model.exec.jdbc.JDBCSession session,
        org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement statement,
        GaussDBPackage object,
        GaussDBDatabase database,
        org.jkiss.dbeaver.ext.postgresql.model.PostgreExecutionContext context,
        org.jkiss.dbeaver.ext.postgresql.model.PostgreExecutionContext isolatedContext,
        org.jkiss.dbeaver.model.DBPDataSourceContainer container,
        org.jkiss.dbeaver.model.runtime.VoidProgressMonitor monitor
    ) {
    }

    private record SourceReadFixture(
        org.jkiss.dbeaver.model.exec.jdbc.JDBCSession session,
        org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement statement,
        org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet result,
        String specification,
        String body
    ) {
    }
}
