/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** Opt-in live SQL tests. Only model identity is mocked; statements/results use the actual vendor driver. */
class GaussDBReviewLiveTest {
    private Connection connect() throws Exception {
        String config = System.getenv("GAUSSDB_REVIEW_CONNECTION");
        String jar = System.getenv("GAUSSDB_REVIEW_JDBC");
        assumeTrue(config != null && jar != null, "Requires isolated live review database");
        Properties props = new Properties();
        try (var in = Files.newInputStream(Path.of(config))) { props.load(in); }
        assertTrue(props.getProperty("review.databasePrefix", "").matches("review_0917_[a-f0-9]{8}_"));
        assertTrue(props.getProperty("url", "").endsWith("/" + props.getProperty("review.databasePrefix") + "ora"));
        props.setProperty("socketTimeout", "20");
        var loader = new URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        Driver driver = (Driver) loader.loadClass(props.getProperty("review.driverClass")).getConstructor().newInstance();
        return driver.connect(props.getProperty("url"), props);
    }

    private static void execute(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement()) { s.setQueryTimeout(15); s.execute(sql); }
    }

    private static long scalar(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) { assertTrue(rs.next()); return rs.getLong(1); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T bridge(Class<T> type, Object delegate) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            try {
                var jdbcType = delegate instanceof PreparedStatement ? PreparedStatement.class : ResultSet.class;
                Object value = jdbcType.getMethod(method.getName(), method.getParameterTypes()).invoke(delegate, args);
                return value instanceof ResultSet rs ? bridge(JDBCResultSet.class, rs) : value;
            } catch (InvocationTargetException e) { throw e.getCause(); }
        });
    }

    private static JDBCSession session(Connection connection, boolean unavailableCatalog) throws Exception {
        var session = mock(JDBCSession.class);
        when(session.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (unavailableCatalog) {
                // Exercise the real server's 42P01 response without touching its system catalog.
                sql = sql.replace("DBE_PLDEVELOPER.GS_ERRORS", "public.absent_review_diagnostics");
            }
            return bridge(JDBCPreparedStatement.class, connection.prepareStatement(sql));
        });
        return session;
    }

    private static GaussDBPackage object(Connection c) throws Exception {
        var object = mock(GaussDBPackage.class);
        var schema = mock(GaussDBSchema.class);
        when(object.getSchema()).thenReturn(schema);
        long schemaId = scalar(c, "SELECT oid FROM pg_namespace WHERE nspname='review_live'");
        long packageId = scalar(c, "SELECT oid FROM gs_package WHERE pkgname='review_pkg' AND pkgnamespace=" + schemaId);
        when(schema.getObjectId()).thenReturn(schemaId);
        when(object.getObjectId()).thenReturn(packageId);
        when(object.getFullyQualifiedName(DBPEvaluationContext.DDL)).thenReturn("review_live.review_pkg");
        return object;
    }

    @Test
    void realPackageCompileTargetsAndBodyDiagnosticLine() throws Exception {
        try (Connection c = connect()) {
            execute(c, "CREATE SCHEMA review_live");
            try {
                execute(c, "CREATE TABLE review_live.dependency(id integer)");
                execute(c, "CREATE PACKAGE review_live.review_pkg AS FUNCTION value_of RETURN INTEGER; END review_pkg;");
                execute(c, "CREATE PACKAGE BODY review_live.review_pkg AS\n FUNCTION value_of RETURN INTEGER AS\n"
                    + " v review_live.dependency.id%TYPE;\n BEGIN\n RETURN 7;\n END;\nEND review_pkg;");
                var object = object(c);
                for (var target : GaussDBPackageCompileTarget.values()) {
                    execute(c, GaussDBPackageCompiler.getCompileSQL(object, target));
                    assertTrue(GaussDBPackageCompiler.readCompilationDiagnostics(session(c, false), new DBCCompileLogBase(), object, target));
                }
                // Force-store an invalid body. DROP ... CASCADE can instead remove
                // dependent source, which is not a compiler-line diagnostic scenario.
                execute(c, "SET enable_force_create_obj=on");
                execute(c, "SET plsql_show_all_error=off");
                execute(c, "CREATE OR REPLACE PACKAGE BODY review_live.review_pkg AS\n FUNCTION value_of RETURN INTEGER AS\n"
                    + " v review_live.missing_dependency.id%TYPE;\n BEGIN\n RETURN 7;\n END;\nEND review_pkg;");
                try { execute(c, GaussDBPackageCompiler.getCompileSQL(object, GaussDBPackageCompileTarget.BODY)); }
                catch (SQLException expected) { /* Diagnostics below must identify the real body failure. */ }
                var log = new DBCCompileLogBase();
                assertFalse(GaussDBPackageCompiler.readCompilationDiagnostics(session(c, false), log, object, GaussDBPackageCompileTarget.ALL));
                assertFalse(log.getErrorStack().isEmpty());
                var error = (GaussDBPackageCompileError) log.getErrorStack().iterator().next();
                assertEquals(GaussDBPackageCompileTarget.BODY, error.getSourcePart());
                assertTrue(error.getLine() > 0);
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT src FROM dbe_pldeveloper.gs_source WHERE id=" + object.getObjectId() + " AND type='package body'")) {
                    assertTrue(r.next());
                    String[] lines = r.getString(1).split("\\R", -1);
                    System.out.println("LIVE source=" + java.util.Arrays.toString(lines) + " diagnostic=" + error.getLine()
                        + " message=" + error.getMessage());
                    assertTrue(error.getLine() <= lines.length);
                    assertTrue(lines[error.getLine() - 1].contains("missing_dependency"),
                        "Diagnostic must point at the failing declaration in the editor's server source");
                }
                System.out.println("LIVE package body diagnostics line=" + error.getLine());
                execute(c, "CREATE OR REPLACE PACKAGE review_live.review_pkg AS\n"
                    + " v review_live.missing_dependency.id%TYPE;\n FUNCTION value_of RETURN INTEGER;\nEND review_pkg;");
                try { execute(c, GaussDBPackageCompiler.getCompileSQL(object, GaussDBPackageCompileTarget.SPECIFICATION)); }
                catch (SQLException expected) { /* Inspect the server catalog rather than guessing from its message. */ }
                log.clearLog();
                assertFalse(GaussDBPackageCompiler.readCompilationDiagnostics(session(c, false), log, object,
                    GaussDBPackageCompileTarget.SPECIFICATION));
                var specError = (GaussDBPackageCompileError) log.getErrorStack().iterator().next();
                assertEquals(GaussDBPackageCompileTarget.SPECIFICATION, specError.getSourcePart());
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT src FROM dbe_pldeveloper.gs_source WHERE id=" + object.getObjectId() + " AND type='package'")) {
                    assertTrue(r.next());
                    assertTrue(r.getString(1).split("\\R", -1)[specError.getLine() - 1].contains("missing_dependency"));
                }
                System.out.println("LIVE package specification diagnostics line=" + specError.getLine());
            } finally { execute(c, "DROP SCHEMA review_live CASCADE"); }
        }
    }

    @Test
    void realMissingDiagnosticsDoesNotBecomeFakeSourceError() throws Exception {
        try (Connection c = connect()) {
            var log = new DBCCompileLogBase();
            var error = assertThrows(DBException.class, () -> GaussDBPackageCompiler.readCompilationDiagnostics(
                session(c, true), log, mock(GaussDBPackage.class, RETURNS_DEEP_STUBS), GaussDBPackageCompileTarget.ALL));
            assertEquals("42P01", ((SQLException) error.getCause()).getSQLState());
            assertTrue(log.getErrorStack().isEmpty());
        }
    }
}
