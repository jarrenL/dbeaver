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

    @Test
    void actualDumpAndLargeOutputPlainRestoreUsesProductionRedirection() throws Exception {
        String config = System.getenv("GAUSSDB_REVIEW_CONNECTION"), jar = System.getenv("GAUSSDB_REVIEW_JDBC");
        assumeTrue(config != null && jar != null && "true".equals(System.getenv("GAUSSDB_REVIEW_NATIVE")));
        Properties props = new Properties();
        try (var input = Files.newInputStream(Path.of(config))) { props.load(input); }
        String prefix = props.getProperty("review.databasePrefix", "");
        assertTrue(prefix.matches("review_0917_[a-f0-9]{8}_"));
        assertTrue(props.getProperty("url", "").endsWith("/" + prefix + "ora"));
        String user = props.getProperty("user"), database = prefix + "ora";
        assertEquals(prefix, user + "_");
        String remote = "/tmp/review-native-" + UUID.randomUUID() + ".sql";
        Path directory = Files.createTempDirectory("gaussdb-native-live-");
        Path dump = directory.resolve("dump.sql"), errors = directory.resolve("stderr.log");
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Driver driver = (Driver) loader.loadClass(props.getProperty("review.driverClass")).getConstructor().newInstance();
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
                    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*),sum(n) FROM review_native.rows_to_restore")) {
                        assertTrue(rs.next()); assertEquals(3, rs.getInt(1)); assertEquals(6, rs.getInt(2));
                    }
                    run(new ProcessBuilder(tool("gs_dump", database, user, "-F", "c", "-n", "review_native", "-f", remote)), errors, settings);
                    exec(c, "DROP SCHEMA review_native CASCADE");
                    run(new ProcessBuilder(tool("gs_restore", database, user, remote)), errors, settings);
                    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*),sum(n) FROM review_native.rows_to_restore")) {
                        assertTrue(rs.next()); assertEquals(3, rs.getInt(1)); assertEquals(6, rs.getInt(2));
                    }
                } finally { exec(c, "DROP SCHEMA IF EXISTS review_native CASCADE"); }
            }
        } finally {
            // Exact randomly generated test artifact; never delete a user backup.
            run(new ProcessBuilder("docker", "exec", "-u", "0", "gaussdb-507-ha-lab", "rm", "-f", remote)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD), errors);
            Files.deleteIfExists(dump); Files.deleteIfExists(errors); Files.delete(directory);
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
