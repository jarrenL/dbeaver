/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource;
import org.jkiss.dbeaver.ext.postgresql.tasks.PostgreDatabaseRestoreHandler;
import org.jkiss.dbeaver.ext.postgresql.tasks.PostgreDatabaseRestoreSettings;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** Opt-in native vendor tools on the isolated local 507 package lab. */
class GaussDBNativeLiveTest {
    private static List<String> tool(String binary, String database, String user, String... options) {
        List<String> args = new ArrayList<>(List.of("docker", "exec", "-i", "gaussdb-507-ha-lab", "env",
            "GAUSSHOME=/opt/gaussdb/ha-app", "LD_LIBRARY_PATH=/opt/gaussdb/ha-app/lib",
            "/opt/gaussdb/ha-app/bin/" + binary, "--pipeline", "-h", "127.0.0.1", "-p", "55452", "-U", user));
        args.addAll(List.of(options));
        if (binary.equals("gsql") || binary.equals("gs_restore")) { args.add("-d"); }
        args.add(database);
        return args;
    }

    private static void run(ProcessBuilder builder, Path errorLog) throws Exception {
        run(builder, errorLog, null);
    }

    private static void run(ProcessBuilder builder, Path errorLog, PostgreDatabaseRestoreSettings settings) throws Exception {
        Restore handler = new Restore();
        if (settings != null) { handler.configure(settings, builder); }
        builder.redirectError(errorLog.toFile());
        Process process = builder.start();
        try {
            if (settings != null) { handler.authenticate(settings, process); }
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Native tool timed out");
            assertEquals(0, process.exitValue(), Files.readString(errorLog));
        } finally { process.destroyForcibly(); }
    }

    private static void exec(Connection connection, String sql) throws Exception {
        try (Statement s = connection.createStatement()) { s.setQueryTimeout(15); s.execute(sql); }
    }

    private static byte[] largeBinaryPayload() {
        byte[] bytes = new byte[1024 * 1024 + 3];
        new Random(240924L).nextBytes(bytes);
        return bytes;
    }

    private static void verifyPayload(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id,label,amount,occurred,raw_bytes,enabled,disabled FROM review_native.payload ORDER BY id")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertEquals("中文'引号\\反斜杠\n第二行\t制表", rows.getString(2));
            assertEquals(new java.math.BigDecimal("12345678901234567890.123456789"), rows.getBigDecimal(3));
            assertEquals(Timestamp.valueOf("2024-02-29 12:34:56.001234"), rows.getTimestamp(4));
            assertArrayEquals(new byte[]{0, 1, 127, (byte) 128, (byte) 255}, rows.getBytes(5));
            assertTrue(rows.getBoolean(6));
            assertFalse(rows.wasNull());
            assertFalse(rows.getBoolean(7));
            assertFalse(rows.wasNull());
            assertTrue(rows.next());
            assertEquals(2, rows.getInt(1));
            assertNull(rows.getString(2));
            assertNull(rows.getBigDecimal(3));
            assertNull(rows.getTimestamp(4));
            assertNull(rows.getBytes(5));
            assertFalse(rows.getBoolean(6));
            assertTrue(rows.wasNull(), "SQL NULL must not become boolean false during restore");
            assertFalse(rows.getBoolean(7));
            assertTrue(rows.wasNull());
            assertTrue(rows.next());
            assertEquals(3, rows.getInt(1));
            assertArrayEquals(new byte[0], rows.getBytes(5));
            assertFalse(rows.wasNull(), "Empty bytea must remain distinct from SQL NULL");
            assertTrue(rows.next());
            assertEquals(4, rows.getInt(1));
            byte[] actual = rows.getBytes(5);
            assertNotNull(actual);
            byte[] expected = largeBinaryPayload();
            assertEquals(expected.length, actual.length);
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            assertArrayEquals(digest.digest(expected), digest.digest(actual), "Large binary payload must not be truncated or changed");
            assertFalse(rows.next());
        }
    }

    @Test
    void actualDumpAndLargeOutputPlainRestoreUsesProductionRedirection() throws Exception {
        String config = System.getenv("GAUSSDB_REVIEW_CONNECTION"), jar = System.getenv("GAUSSDB_REVIEW_JDBC");
        if (System.getenv("GAUSSDB_NATIVE_CONNECTION") != null) {
            config = System.getenv("GAUSSDB_NATIVE_CONNECTION");
        }
        if (System.getenv("GAUSSDB_NATIVE_JDBC") != null) {
            jar = System.getenv("GAUSSDB_NATIVE_JDBC");
        }
        assumeTrue(config != null && jar != null && "true".equals(System.getenv("GAUSSDB_REVIEW_NATIVE")));
        Properties props = new Properties();
        try (var input = Files.newInputStream(Path.of(config))) { props.load(input); }
        String prefix = props.getProperty("review.databasePrefix", "");
        String user = props.getProperty("user");
        var endpoint = java.net.URI.create(props.getProperty("url").substring(5));
        String database = endpoint.getPath().substring(1);
        boolean legacyIsolated = prefix.matches("review_0917_[a-f0-9]{8}_")
            && database.equals(prefix + "ora") && prefix.equals(user + "_");
        boolean historicalIsolated = database.matches("dbv_hist_central_[0-9]{8}") && database.equals(user)
            && "YES".equals(System.getenv("GAUSSDB_HISTORY_ALLOW_DDL"));
        assertTrue(legacyIsolated || historicalIsolated, "Native restore requires an isolated test identity/database");
        assertEquals("127.0.0.1", endpoint.getHost());
        assertEquals(55452, endpoint.getPort());
        String remote = "/tmp/review-native-" + UUID.randomUUID() + ".sql";
        Path directory = Files.createTempDirectory("gaussdb-native-live-");
        Path dump = directory.resolve("dump.sql"), errors = directory.resolve("stderr.log");
        Path dataArchive = directory.resolve("data-only.backup");
        Path emptyImport = directory.resolve("empty-import.bin");
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Driver driver = (Driver) loader.loadClass(props.getProperty("review.driverClass", props.getProperty("driverClass")))
                .getConstructor().newInstance();
            try (Connection c = driver.connect(props.getProperty("url"), props)) {
                exec(c, "CREATE SCHEMA review_native");
                var settings = mock(PostgreDatabaseRestoreSettings.class);
                var container = mock(DBPDataSourceContainer.class);
                var source = mock(PostgreDataSource.class);
                var server = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreServerExtension.class);
                when(settings.getToolUserPassword()).thenReturn(props.getProperty("password"));
                when(settings.getDataSourceContainer()).thenReturn(container);
                when(container.getDataSource()).thenReturn(source);
                when(source.getServerType()).thenReturn(server);
                when(server.usesNativePasswordPipe()).thenReturn(true);
                try {
                    exec(c, "CREATE TABLE review_native.rows_to_restore(n integer)");
                    exec(c, "INSERT INTO review_native.rows_to_restore VALUES(1),(2),(3)");
                    exec(c, "CREATE TABLE review_native.payload(id integer PRIMARY KEY,label text,amount numeric(38,9),"
                        + "occurred timestamp(6),raw_bytes bytea,enabled boolean,disabled boolean)");
                    try (PreparedStatement insert = c.prepareStatement("INSERT INTO review_native.payload VALUES(?,?,?,?,?,?,?)")) {
                        insert.setInt(1, 1);
                        insert.setString(2, "中文'引号\\反斜杠\n第二行\t制表");
                        insert.setBigDecimal(3, new java.math.BigDecimal("12345678901234567890.123456789"));
                        insert.setTimestamp(4, Timestamp.valueOf("2024-02-29 12:34:56.001234"));
                        insert.setBytes(5, new byte[]{0, 1, 127, (byte) 128, (byte) 255});
                        insert.setBoolean(6, true);
                        insert.setBoolean(7, false);
                        assertEquals(1, insert.executeUpdate());
                    }
                    exec(c, "INSERT INTO review_native.payload VALUES(2,NULL,NULL,NULL,NULL,NULL,NULL)");
                    exec(c, "INSERT INTO review_native.payload(id) VALUES(3)");
                    // Exercise the production GaussDB binding, not a server-side fixture workaround.
                    try (PreparedStatement insert = c.prepareStatement("UPDATE review_native.payload SET raw_bytes=? WHERE id=3")) {
                        var jdbc = (org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement) java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(), new Class<?>[]{org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class},
                            (proxy, method, args) -> {
                                try { return PreparedStatement.class.getMethod(method.getName(), method.getParameterTypes()).invoke(insert, args); }
                                catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                            });
                        var type = mock(org.jkiss.dbeaver.model.struct.DBSTypedObject.class);
                        when(type.getTypeName()).thenReturn("bytea");
                        when(type.getTypeID()).thenReturn(Types.BINARY);
                        var session = mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCSession.class);
                        var handler = new org.jkiss.dbeaver.ext.gaussdb.model.data.GaussDBValueHandlerProvider()
                            .getValueHandler(source, mock(org.jkiss.dbeaver.model.data.DBDFormatSettings.class), type);
                        assertNotNull(handler);
                        var context = mock(org.jkiss.dbeaver.model.exec.DBCExecutionContext.class);
                        Files.createFile(emptyImport);
                        var fileContent = new org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBLOB(context, null);
                        fileContent.updateContents(new VoidProgressMonitor(),
                            new org.jkiss.dbeaver.model.data.storage.TemporaryContentStorage(
                                mock(org.jkiss.dbeaver.model.app.DBPPlatform.class), emptyImport, "UTF-8", false));
                        var contents = List.of(
                            new org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBytes(context, new byte[0]),
                            new org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBLOB(context, new javax.sql.rowset.serial.SerialBlob(new byte[0])),
                            fileContent);
                        for (var content : contents) {
                            try {
                                handler.bindValueObject(session, jdbc, type, 0, content);
                                assertEquals(1, insert.executeUpdate());
                                try (Statement query = c.createStatement(); ResultSet result = query.executeQuery(
                                    "SELECT raw_bytes IS NULL,octet_length(raw_bytes),raw_bytes FROM review_native.payload WHERE id=3")) {
                                    assertTrue(result.next());
                                    assertFalse(result.getBoolean(1), content.getClass().getSimpleName());
                                    assertEquals(0, result.getInt(2));
                                    assertFalse(result.wasNull());
                                    assertArrayEquals(new byte[0], result.getBytes(3));
                                    assertFalse(result.next());
                                }
                            } finally { content.release(); }
                        }
                        assertTrue(Files.exists(emptyImport), "Imported source file must not be deleted by binding");
                    }
                    try (PreparedStatement insert = c.prepareStatement("INSERT INTO review_native.payload(id,raw_bytes) VALUES(?,?)")) {
                        insert.setInt(1, 4);
                        insert.setBytes(2, largeBinaryPayload());
                        assertEquals(1, insert.executeUpdate());
                    }
                    verifyPayload(c); // Establish the exact pre-backup baseline before attributing a failure to restore.
                    var backup = new ProcessBuilder(tool("gs_dump", database, user, "-n", "review_native", "-f", remote))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    run(backup, errors, settings);
                    run(new ProcessBuilder("docker", "cp", "gaussdb-507-ha-lab:" + remote, dump.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD), errors);
                    assertTrue(Files.size(dump) > 0);
                    Files.writeString(dump, "\nSELECT repeat('x',1024) FROM generate_series(1,4096);\n", StandardOpenOption.APPEND);
                    run(new ProcessBuilder("docker", "cp", dump.toString(), "gaussdb-507-ha-lab:" + remote)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD), errors);
                    run(new ProcessBuilder("docker", "exec", "-u", "0", "gaussdb-507-ha-lab", "chown", "gausscore:dbgrp", remote)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD), errors);
                    exec(c, "DROP SCHEMA review_native CASCADE");
                    var builder = new ProcessBuilder(tool("gsql", database, user, "-v", "ON_ERROR_STOP=1", "-f", remote));
                    new Restore().configure(settings, builder);
                    assertFalse(builder.environment().containsKey("PGPASSWORD"));
                    assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectOutput());
                    run(builder, errors, settings);
                    verifyPayload(c);
                    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*),sum(n) FROM review_native.rows_to_restore")) {
                        assertTrue(rs.next()); assertEquals(3, rs.getInt(1)); assertEquals(6, rs.getInt(2));
                    }
                    run(new ProcessBuilder(tool("gs_dump", database, user, "-F", "c", "-n", "review_native", "-f", remote)), errors, settings);
                    exec(c, "DROP SCHEMA review_native CASCADE");
                    run(new ProcessBuilder(tool("gs_restore", database, user, remote)), errors, settings);
                    verifyPayload(c);
                    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*),sum(n) FROM review_native.rows_to_restore")) {
                        assertTrue(rs.next()); assertEquals(3, rs.getInt(1)); assertEquals(6, rs.getInt(2));
                    }
                    // Independently backed up structure and data must reconstruct the same schema.
                    run(new ProcessBuilder(tool("gs_dump", database, user, "-F", "c", "--data-only",
                        "-n", "review_native", "-f", remote)), errors, settings);
                    run(new ProcessBuilder("docker", "cp", "gaussdb-507-ha-lab:" + remote, dataArchive.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD), errors);
                    run(new ProcessBuilder(tool("gs_dump", database, user, "--schema-only",
                        "-n", "review_native", "-f", remote)), errors, settings);
                    exec(c, "DROP SCHEMA review_native CASCADE");
                    run(new ProcessBuilder(tool("gsql", database, user, "-v", "ON_ERROR_STOP=1", "-f", remote)), errors, settings);
                    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                        "SELECT (SELECT count(*) FROM review_native.payload), (SELECT count(*) FROM review_native.rows_to_restore)")) {
                        assertTrue(rs.next());
                        assertEquals(0, rs.getInt(1));
                        assertEquals(0, rs.getInt(2));
                    }
                    run(new ProcessBuilder("docker", "cp", dataArchive.toString(), "gaussdb-507-ha-lab:" + remote)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD), errors);
                    run(new ProcessBuilder("docker", "exec", "-u", "0", "gaussdb-507-ha-lab", "chown", "gausscore:dbgrp", remote)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD), errors);
                    run(new ProcessBuilder(tool("gs_restore", database, user, remote)), errors, settings);
                    verifyPayload(c);
                    SQLException duplicate = assertThrows(SQLException.class,
                        () -> exec(c, "INSERT INTO review_native.payload(id) VALUES(1)"));
                    assertEquals("23505", duplicate.getSQLState(), "Schema-only backup must preserve the primary key");
                    SQLException notNull = assertThrows(SQLException.class,
                        () -> exec(c, "INSERT INTO review_native.payload(id) VALUES(NULL)"));
                    assertEquals("23502", notNull.getSQLState());
                    verifyPayload(c);
                    // A corrupt archive must not be accepted as a successful restore.
                    Files.writeString(dump, "not-a-valid-native-archive\n");
                    run(new ProcessBuilder("docker", "cp", dump.toString(), "gaussdb-507-ha-lab:" + remote)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD), errors);
                    run(new ProcessBuilder("docker", "exec", "-u", "0", "gaussdb-507-ha-lab", "chown", "gausscore:dbgrp", remote)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD), errors);
                    var corrupt = new ProcessBuilder(tool("gs_restore", database, user, remote));
                    AssertionError corruptFailure = assertThrows(AssertionError.class, () -> run(corrupt, errors, settings));
                    assertFalse(corruptFailure.getMessage().contains("Native tool timed out"));
                    // Some vendor versions return nonzero without writing stderr for invalid headers.
                    assertFalse(Files.readString(errors).contains(props.getProperty("password")));
                    verifyPayload(c);
                    // Restore the valid data archive after the rejected archive, into empty fixture tables.
                    exec(c, "TRUNCATE review_native.payload, review_native.rows_to_restore");
                    run(new ProcessBuilder("docker", "cp", dataArchive.toString(), "gaussdb-507-ha-lab:" + remote)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD), errors);
                    run(new ProcessBuilder("docker", "exec", "-u", "0", "gaussdb-507-ha-lab", "chown", "gausscore:dbgrp", remote)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD), errors);
                    run(new ProcessBuilder(tool("gs_restore", database, user, remote)), errors, settings);
                    verifyPayload(c);
                    // A failed script must report failure and stop before the following destructive statement.
                    Files.writeString(dump, "SELECT 1/0;\nDELETE FROM review_native.rows_to_restore;\n");
                    run(new ProcessBuilder("docker", "cp", dump.toString(), "gaussdb-507-ha-lab:" + remote)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD), errors);
                    run(new ProcessBuilder("docker", "exec", "-u", "0", "gaussdb-507-ha-lab", "chown", "gausscore:dbgrp", remote)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD), errors);
                    var invalid = new ProcessBuilder(tool("gsql", database, user, "-v", "ON_ERROR_STOP=1", "-f", remote));
                    AssertionError failure = assertThrows(AssertionError.class, () -> run(invalid, errors, settings));
                    String safeFailure = failure.getMessage().replace(props.getProperty("password"), "[REDACTED]");
                    assertTrue(safeFailure.toLowerCase(Locale.ROOT).contains("division by zero"), safeFailure);
                    assertFalse(Files.readString(errors).contains(props.getProperty("password")));
                    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*),sum(n) FROM review_native.rows_to_restore")) {
                        assertTrue(rs.next()); assertEquals(3, rs.getInt(1)); assertEquals(6, rs.getInt(2));
                    }
                    Files.writeString(dump, "INSERT INTO review_native.rows_to_restore VALUES(4);\n");
                    run(new ProcessBuilder("docker", "cp", dump.toString(), "gaussdb-507-ha-lab:" + remote)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD), errors);
                    run(new ProcessBuilder("docker", "exec", "-u", "0", "gaussdb-507-ha-lab", "chown", "gausscore:dbgrp", remote)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD), errors);
                    run(new ProcessBuilder(tool("gsql", database, user, "-v", "ON_ERROR_STOP=1", "-f", remote)), errors, settings);
                    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*),sum(n) FROM review_native.rows_to_restore")) {
                        assertTrue(rs.next()); assertEquals(4, rs.getInt(1)); assertEquals(10, rs.getInt(2));
                    }
                } finally { exec(c, "DROP SCHEMA IF EXISTS review_native CASCADE"); }
            }
        } finally {
            // Exact randomly generated test artifact; never delete a user backup.
            run(new ProcessBuilder("docker", "exec", "-u", "0", "gaussdb-507-ha-lab", "rm", "-f", remote)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD), errors);
            Files.deleteIfExists(dump); Files.deleteIfExists(dataArchive); Files.deleteIfExists(errors);
            Files.deleteIfExists(emptyImport); Files.delete(directory);
        }
    }

    private static class Restore extends PostgreDatabaseRestoreHandler {
        void authenticate(PostgreDatabaseRestoreSettings settings, Process process) throws java.io.IOException {
            writeNativePassword(new VoidProgressMonitor(), settings, process);
        }
        void configure(PostgreDatabaseRestoreSettings settings, ProcessBuilder builder) {
            setupProcessParameters(new VoidProgressMonitor(), settings, null, builder);
        }
    }
}
