/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Conditional protocol coverage against an explicitly isolated GaussDB review database. */
class GaussDBPackageDebugLiveTest {
    @Test
    void zeroArgumentPackageTargetHitsExactFunctionBreakpointAndMapsServerFrames() throws Exception {
        try (Connection target = connect(); Connection controller = connect()) {
            execute(controller, "CREATE SCHEMA review_package_stack");
            ExecutorService worker = Executors.newSingleThreadExecutor();
            Future<?> targetJob = null;
            long entryOid = 0;
            try {
                execute(controller, """
                    CREATE PACKAGE review_package_stack.pkg_alpha AS
                      PROCEDURE entry;
                      PROCEDURE middle(result OUT INTEGER);
                      FUNCTION leaf RETURN INTEGER;
                    END pkg_alpha;
                    """);
                execute(controller, """
                    CREATE PACKAGE BODY review_package_stack.pkg_alpha AS
                      FUNCTION leaf RETURN INTEGER AS
                        leaf_value INTEGER := 30;
                      BEGIN
                        leaf_value := leaf_value + 3;
                        RETURN leaf_value;
                      END leaf;
                      PROCEDURE middle(result OUT INTEGER) AS
                        middle_value INTEGER := 20;
                      BEGIN
                        middle_value := leaf();
                        result := middle_value;
                      END middle;
                      PROCEDURE entry AS
                        entry_value INTEGER := 10;
                      BEGIN
                        middle(entry_value);
                      END entry;
                    END pkg_alpha;
                    """);
                execute(controller, """
                    CREATE PACKAGE review_package_stack.pkg_beta AS
                      FUNCTION leaf RETURN INTEGER;
                    END pkg_beta;
                    """);
                execute(controller, """
                    CREATE PACKAGE BODY review_package_stack.pkg_beta AS
                      FUNCTION leaf RETURN INTEGER AS
                      BEGIN
                        RETURN 999;
                      END leaf;
                    END pkg_beta;
                    """);

                entryOid = memberOid(controller, "pkg_alpha", "entry");
                long middleOid = memberOid(controller, "pkg_alpha", "middle");
                long alphaLeafOid = memberOid(controller, "pkg_alpha", "leaf");
                long betaLeafOid = memberOid(controller, "pkg_beta", "leaf");
                assertNotEquals(alphaLeafOid, betaLeafOid);

                int executableLeafLine = Integer.parseInt(scalar(controller,
                    "SELECT max(lineno) FROM DBE_PLDEBUGGER.info_code(" + alphaLeafOid + "::oid) WHERE canbreak"));
                assertTrue(executableLeafLine > 0);

                target.setAutoCommit(false);
                execute(target, "SET LOCAL search_path = review_package_stack, public");
                String node;
                int port;
                try (Statement statement = target.createStatement();
                     ResultSet result = statement.executeQuery(
                         "SELECT * FROM DBE_PLDEBUGGER.turn_on(" + entryOid + "::oid)")) {
                    assertTrue(result.next());
                    node = result.getString(1);
                    port = result.getInt(2);
                }

                targetJob = worker.submit(() -> {
                    try {
                        executeTarget(target, "CALL pkg_alpha.entry()");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                try (PreparedStatement attach = controller.prepareStatement(
                    "SELECT * FROM DBE_PLDEBUGGER.attach(?, ?)")) {
                    attach.setString(1, node);
                    attach.setInt(2, port);
                    attach.setQueryTimeout(10);
                    GaussDBDebugSession.attachWithRetry(attach, new VoidProgressMonitor(), targetJob::isDone);
                }
                assertEquals(entryOid, Long.parseLong(scalar(controller,
                    "SELECT funcoid FROM DBE_PLDEBUGGER.backtrace() WHERE frameno=0")));

                try (PreparedStatement add = controller.prepareStatement(
                    "SELECT DBE_PLDEBUGGER.add_breakpoint(?::oid, ?::integer)")) {
                    add.setLong(1, alphaLeafOid);
                    add.setInt(2, executableLeafLine);
                    try (ResultSet result = add.executeQuery()) {
                        assertTrue(result.next());
                        assertTrue(result.getInt(1) >= 0);
                    }
                }
                assertEquals("1", scalar(controller,
                    "SELECT count(*) FROM DBE_PLDEBUGGER.info_breakpoints()"));

                String paused = scalar(controller, "SELECT query FROM DBE_PLDEBUGGER.continue()");
                assertFalse(paused.contains("[EXECUTION FINISHED]"));
                List<Long> stackOids = columnLongs(controller,
                    "SELECT funcoid FROM DBE_PLDEBUGGER.backtrace() ORDER BY frameno");
                assertTrue(stackOids.equals(List.of(alphaLeafOid, middleOid, entryOid))
                        || stackOids.equals(List.of(alphaLeafOid, entryOid)),
                    "Some GaussDB versions omit an internal Package member from DBE_PLDEBUGGER.backtrace()");
                assertFalse(stackOids.contains(betaLeafOid));
                assertNotNull(frameLocal(controller, 0, "leaf_value"));
                if (stackOids.contains(middleOid)) {
                    assertNotNull(frameLocal(controller, stackOids.indexOf(middleOid), "middle_value"));
                }
                assertNotNull(frameLocal(controller, stackOids.size() - 1, "entry_value"));

                String completed = scalar(controller, "SELECT query FROM DBE_PLDEBUGGER.continue()");
                assertTrue(completed.contains("[EXECUTION FINISHED]"));
                targetJob.get(15, TimeUnit.SECONDS);
                target.rollback();
                execute(target, "SELECT DBE_PLDEBUGGER.turn_off(" + entryOid + "::oid)");
                target.rollback();
            } finally {
                if (targetJob != null && !targetJob.isDone()) {
                    try {
                        execute(controller, "SELECT DBE_PLDEBUGGER.abort()");
                    } catch (Exception ignored) {
                        // Closing the isolated target connection below is the final fallback.
                    }
                }
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
                if (!target.isClosed() && !target.getAutoCommit()) {
                    target.rollback();
                    target.setAutoCommit(true);
                }
                execute(controller, "DROP SCHEMA review_package_stack CASCADE");
            }
        }
    }

    @Test
    void threeStandaloneRoutinesExposeAllFramesAndFrameLocals() throws Exception {
        try (Connection target = connect(); Connection controller = connect()) {
            execute(controller, "CREATE SCHEMA review_standalone_stack");
            ExecutorService worker = Executors.newSingleThreadExecutor();
            Future<?> targetJob = null;
            long entryOid = 0;
            try {
                execute(controller, """
                    CREATE OR REPLACE FUNCTION review_standalone_stack.live_leaf RETURN INTEGER AS
                      leaf_value INTEGER := 30;
                    BEGIN
                      leaf_value := leaf_value + 3;
                      RETURN leaf_value;
                    END;
                    """);
                execute(controller, """
                    CREATE OR REPLACE PROCEDURE review_standalone_stack.live_middle(result OUT INTEGER) AS
                      middle_value INTEGER := 20;
                    BEGIN
                      middle_value := review_standalone_stack.live_leaf();
                      result := middle_value;
                    END;
                    """);
                execute(controller, """
                    CREATE OR REPLACE PROCEDURE review_standalone_stack.live_entry AS
                      entry_value INTEGER := 10;
                    BEGIN
                      review_standalone_stack.live_middle(entry_value);
                    END;
                    """);

                entryOid = standaloneOid(controller, "review_standalone_stack", "live_entry");
                long middleOid = standaloneOid(controller, "review_standalone_stack", "live_middle");
                long leafOid = standaloneOid(controller, "review_standalone_stack", "live_leaf");

                target.setAutoCommit(false);
                String node;
                int port;
                try (Statement statement = target.createStatement();
                     ResultSet result = statement.executeQuery(
                         "SELECT * FROM DBE_PLDEBUGGER.turn_on(" + entryOid + "::oid)")) {
                    assertTrue(result.next());
                    node = result.getString(1);
                    port = result.getInt(2);
                }
                targetJob = worker.submit(() -> {
                    try {
                        executeTarget(target, "CALL review_standalone_stack.live_entry()");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                try (PreparedStatement attach = controller.prepareStatement(
                    "SELECT * FROM DBE_PLDEBUGGER.attach(?, ?)")) {
                    attach.setString(1, node);
                    attach.setInt(2, port);
                    attach.setQueryTimeout(10);
                    GaussDBDebugSession.attachWithRetry(attach, new VoidProgressMonitor(), targetJob::isDone);
                }
                // Validate the user-visible F7 path. On GaussDB 505, jumping directly to a deep breakpoint with
                // continue() may omit an intermediate frame from the server snapshot, while step() retains it.
                long steppedOid = 0;
                for (int attempt = 0; attempt < 32 && steppedOid != leafOid; attempt++) {
                    steppedOid = Long.parseLong(scalar(controller,
                        "SELECT funcoid FROM DBE_PLDEBUGGER.step()"));
                }
                assertEquals(leafOid, steppedOid, "Step Into must enter the leaf routine");
                List<Long> stackOids = columnLongs(controller,
                    "SELECT funcoid FROM DBE_PLDEBUGGER.backtrace() ORDER BY frameno");
                assertEquals(List.of(leafOid, middleOid, entryOid), stackOids,
                    "Step Into must retain every standalone routine frame");
                assertEquals("30", frameLocal(controller, 0, "leaf_value"));
                assertEquals("20", frameLocal(controller, 1, "middle_value"));
                assertEquals("10", frameLocal(controller, 2, "entry_value"));

                assertTrue(scalar(controller, "SELECT query FROM DBE_PLDEBUGGER.continue()")
                    .contains("[EXECUTION FINISHED]"));
                targetJob.get(15, TimeUnit.SECONDS);
                target.rollback();
                execute(target, "SELECT DBE_PLDEBUGGER.turn_off(" + entryOid + "::oid)");
                target.rollback();
            } finally {
                if (targetJob != null && !targetJob.isDone()) {
                    try {
                        execute(controller, "SELECT DBE_PLDEBUGGER.abort()");
                    } catch (Exception ignored) {
                        // Closing the isolated target connection below is the final fallback.
                    }
                }
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
                if (!target.isClosed() && !target.getAutoCommit()) {
                    target.rollback();
                    target.setAutoCommit(true);
                }
                execute(controller, "DROP SCHEMA review_standalone_stack CASCADE");
            }
        }
    }

    private static Connection connect() throws Exception {
        String config = System.getenv("GAUSSDB_REVIEW_CONNECTION");
        String jar = System.getenv("GAUSSDB_REVIEW_JDBC");
        assumeTrue(config != null && jar != null, "Requires isolated live review database");
        Properties properties = new Properties();
        try (var input = Files.newInputStream(Path.of(config))) {
            properties.load(input);
        }
        String prefix = properties.getProperty("review.databasePrefix", "");
        String database = java.net.URI.create(properties.getProperty("url").substring(5)).getPath().substring(1);
        boolean legacyIsolated = prefix.matches("review_0917_[a-f0-9]{8}_") && database.equals(prefix + "ora");
        boolean historicalIsolated = database.matches("dbv_hist_central_[0-9]{8}") &&
            "YES".equals(System.getenv("GAUSSDB_HISTORY_ALLOW_DDL"));
        assertTrue(legacyIsolated || historicalIsolated, "Requires an explicitly isolated review database");
        properties.setProperty("socketTimeout", "20");
        properties.setProperty("sslmode", "disable");
        var loader = new URLClassLoader(
            new java.net.URL[]{Path.of(jar).toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        return ((Driver) loader.loadClass(properties.getProperty(
                "review.driverClass", properties.getProperty("driverClass")))
            .getConstructor().newInstance()).connect(properties.getProperty("url"), properties);
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            statement.execute(sql);
        }
    }

    private static void executeTarget(Connection connection, String sql) throws Exception {
        // Debug target calls are deliberately suspended by the controller.
        // Future.get and the fixture cleanup own the overall timeout instead.
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static long memberOid(Connection connection, String packageName, String memberName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT p.oid
              FROM pg_proc p
              JOIN pg_namespace n ON n.oid=p.pronamespace
              JOIN gs_package pkg ON pkg.oid=p.propackageid
             WHERE n.nspname='review_package_stack' AND pkg.pkgname=? AND p.proname=?
            """)) {
            statement.setString(1, packageName);
            statement.setString(2, memberName);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                long oid = result.getLong(1);
                assertFalse(result.next(), "Package member fixture must resolve to one catalog OID");
                return oid;
            }
        }
    }

    private static long standaloneOid(Connection connection, String schemaName, String routineName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT p.oid
              FROM pg_proc p
              JOIN pg_namespace n ON n.oid=p.pronamespace
             WHERE n.nspname=? AND p.proname=? AND p.propackageid=0
            """)) {
            statement.setString(1, schemaName);
            statement.setString(2, routineName);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                long oid = result.getLong(1);
                assertFalse(result.next(), "Standalone fixture must resolve to one catalog OID");
                return oid;
            }
        }
    }

    private static List<Long> columnLongs(Connection connection, String sql) throws Exception {
        List<Long> values = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getLong(1));
            }
        }
        return values;
    }

    private static String frameLocal(Connection connection, int frame, String name) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM DBE_PLDEBUGGER.info_locals(" + frame + ")")) {
            while (result.next()) {
                if (name.equals(result.getString("varname"))) {
                    return result.getString("value");
                }
            }
        }
        return null;
    }
}
