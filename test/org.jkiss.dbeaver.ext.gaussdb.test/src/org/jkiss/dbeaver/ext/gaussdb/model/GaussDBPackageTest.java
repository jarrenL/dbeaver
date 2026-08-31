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

import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class GaussDBPackageTest {

    @Test
    public void reportsNewPackageAsNotPersisted() {
        GaussDBPackage gaussPackage = new GaussDBPackage(
            Mockito.mock(GaussDBSchema.class),
            Mockito.mock(DBRProgressMonitor.class),
            "new_package"
        );

        Assertions.assertFalse(gaussPackage.isPersisted());
    }

    @Test
    public void loadsAndClosesSourceResourcesLazily() throws Exception {
        JDBCSession cacheSession = Mockito.mock(JDBCSession.class);
        JDBCSession sourceSession = Mockito.mock(JDBCSession.class);
        JDBCPreparedStatement statement = Mockito.mock(JDBCPreparedStatement.class);
        JDBCResultSet packageResult = Mockito.mock(JDBCResultSet.class);
        JDBCResultSet bodyResult = Mockito.mock(JDBCResultSet.class);
        GaussDBPackage gaussPackage = createPackage(cacheSession, sourceSession);

        Mockito.when(sourceSession.prepareStatement(Mockito.anyString())).thenReturn(statement);
        Mockito.when(statement.executeQuery()).thenReturn(packageResult, bodyResult);
        Mockito.when(packageResult.next()).thenReturn(true);
        Mockito.when(packageResult.getString("src")).thenReturn("CREATE PACKAGE test_package");
        Mockito.when(bodyResult.next()).thenReturn(true);
        Mockito.when(bodyResult.getString("src")).thenReturn("CREATE PACKAGE BODY test_package");

        DBRProgressMonitor monitor = Mockito.mock(DBRProgressMonitor.class);
        Assertions.assertEquals(
            "CREATE PACKAGE test_package\nCREATE PACKAGE BODY test_package",
            gaussPackage.getObjectDefinitionText(monitor, Map.of())
        );
        Assertions.assertEquals(
            "CREATE PACKAGE test_package\nCREATE PACKAGE BODY test_package",
            gaussPackage.getObjectDefinitionText(monitor, Map.of())
        );

        Mockito.verifyNoInteractions(cacheSession);
        Mockito.verify(sourceSession).prepareStatement(Mockito.contains("DBE_PLDEVELOPER.gs_source"));
        Mockito.verify(statement).setLong(1, 73L);
        Mockito.verify(statement).setString(2, "package");
        Mockito.verify(statement).setString(2, "package body");
        Mockito.verify(statement, Mockito.times(2)).executeQuery();
        Mockito.verify(packageResult).close();
        Mockito.verify(bodyResult).close();
        Mockito.verify(statement).close();
        Mockito.verify(sourceSession).close();
    }

    @Test
    public void optionalSourceErrorFallsBackOnceAndIsNotRetried() throws Exception {
        JDBCSession cacheSession = Mockito.mock(JDBCSession.class);
        JDBCSession sourceSession = Mockito.mock(JDBCSession.class);
        JDBCPreparedStatement statement = Mockito.mock(JDBCPreparedStatement.class);
        GaussDBPackage gaussPackage = createPackage(cacheSession, sourceSession);
        Mockito.when(sourceSession.prepareStatement(Mockito.anyString())).thenReturn(statement);
        Mockito.when(statement.executeQuery()).thenThrow(new SQLException("Permission denied", "42501"));

        DBRProgressMonitor monitor = Mockito.mock(DBRProgressMonitor.class);
        Assertions.assertEquals("", gaussPackage.getObjectDefinitionText(monitor, Map.of()));
        Assertions.assertEquals("", gaussPackage.getObjectDefinitionText(monitor, Map.of()));

        Mockito.verify(statement, Mockito.times(2)).executeQuery();
        Mockito.verify(statement, Mockito.times(2)).close();
        Mockito.verify(sourceSession).close();
    }

    @Test
    public void connectionAndAuthenticationSourceErrorsStillPropagate() throws Exception {
        for (String sqlState : List.of("08006", "28P01")) {
            assertSourceErrorPropagates(sqlState);
        }
    }

    private static void assertSourceErrorPropagates(String sqlState) throws Exception {
        JDBCSession cacheSession = Mockito.mock(JDBCSession.class);
        JDBCSession sourceSession = Mockito.mock(JDBCSession.class);
        JDBCPreparedStatement statement = Mockito.mock(JDBCPreparedStatement.class);
        JDBCExecutionContext executionContext = Mockito.mock(JDBCExecutionContext.class);
        GaussDBDataSource dataSource = Mockito.mock(GaussDBDataSource.class);
        GaussDBPackage gaussPackage = createPackage(cacheSession, sourceSession);
        Mockito.when(sourceSession.prepareStatement(Mockito.anyString())).thenReturn(statement);
        Mockito.when(sourceSession.getExecutionContext()).thenReturn(executionContext);
        Mockito.when(executionContext.getDataSource()).thenReturn(dataSource);
        Mockito.when(statement.executeQuery()).thenThrow(new SQLException("Source read failed", sqlState));

        DBCException exception = Assertions.assertThrows(
            DBCException.class,
            () -> gaussPackage.getObjectDefinitionText(Mockito.mock(DBRProgressMonitor.class), Map.of())
        );
        Assertions.assertEquals(sqlState, ((SQLException) exception.getCause()).getSQLState());
        Mockito.verify(statement).close();
        Mockito.verify(sourceSession).close();
    }

    private static GaussDBPackage createPackage(JDBCSession cacheSession, JDBCSession sourceSession) throws SQLException {
        GaussDBSchema schema = Mockito.mock(GaussDBSchema.class);
        JDBCResultSet packageResult = Mockito.mock(JDBCResultSet.class);
        Mockito.when(packageResult.getLong("oid")).thenReturn(73L);
        Mockito.when(packageResult.getString("name")).thenReturn("test_package");
        return new TestGaussDBPackage(cacheSession, schema, packageResult, sourceSession);
    }

    private static final class TestGaussDBPackage extends GaussDBPackage {
        private final JDBCSession sourceSession;

        private TestGaussDBPackage(
            JDBCSession cacheSession,
            GaussDBSchema schema,
            JDBCResultSet resultSet,
            JDBCSession sourceSession
        ) {
            super(cacheSession, schema, resultSet);
            this.sourceSession = sourceSession;
        }

        @Override
        JDBCSession openSourceSession(DBRProgressMonitor monitor) {
            return sourceSession;
        }
    }
}
