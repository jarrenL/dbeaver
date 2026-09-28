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
        var dataSource = Mockito.mock(GaussDBDataSource.class);
        var container = Mockito.mock(org.jkiss.dbeaver.model.DBPDataSourceContainer.class);
        Mockito.when(object.getParentObject()).thenReturn(schema);
        Mockito.when(object.getSchema()).thenReturn(schema);
        Mockito.when(schema.getParentObject()).thenReturn(database);
        Mockito.when(database.isInstanceConnected()).thenReturn(true);
        Mockito.when(database.getDefaultContext(Mockito.any(), Mockito.anyBoolean())).thenReturn(context);
        Mockito.when(context.openSession(Mockito.any(), Mockito.any(), Mockito.anyString())).thenReturn(session);
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
        Mockito.verify(context).openSession(Mockito.any(), Mockito.any(), Mockito.anyString());
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
        "compile failed near line 2147483648|1",
        "compile failed near line 0|1",
        "compile failed without a location|1",
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
    public void connectionTerminationAndPermissionErrorsAreNotSourceDiagnostics() {
        for (String state : java.util.List.of("08006", "28P01", "42501", "57P01", "57P02", "57P03")) {
            Assertions.assertTrue(GaussDBPackageCompiler.isInfrastructureError(state), state);
        }
        Assertions.assertFalse(GaussDBPackageCompiler.isInfrastructureError("42601"));
        Assertions.assertFalse(GaussDBPackageCompiler.isInfrastructureError(null));
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
}
