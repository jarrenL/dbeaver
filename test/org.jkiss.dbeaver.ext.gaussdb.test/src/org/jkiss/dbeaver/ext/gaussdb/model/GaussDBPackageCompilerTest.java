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
        Assertions.assertSame(GaussDBPackageCompileTarget.BODY, error.getSourcePart());
    }
}
