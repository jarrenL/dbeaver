/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreServerExtension;
import org.jkiss.dbeaver.ext.postgresql.tasks.*;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.task.DBTTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** Only a newly initialized throwaway cluster with synthetic role credentials may enable this test. */
class GaussDBDumpAllLiveTest {
    @TempDir Path directory;

    private static List<String> tool(String name, String... args) {
        var command = new ArrayList<>(List.of("docker", "exec", "gaussdb-507-ha-lab", "env",
            "GAUSSHOME=/opt/gaussdb/ha-app", "LD_LIBRARY_PATH=/opt/gaussdb/ha-app/lib",
            "/opt/gaussdb/ha-app/bin/" + name, "-p", "55462"));
        command.addAll(List.of(args)); return command;
    }

    private static String run(List<String> command) throws Exception {
        Path output = Files.createTempFile("review-dumpall-command-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
            return Files.readString(output);
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); }
            Files.deleteIfExists(output);
        }
    }

    @Test
    void realDumpAllSuccessFailureAndCancellationDoNotPublishRoleSecrets() throws Exception {
        assumeTrue("true".equals(System.getenv("GAUSSDB_REVIEW_EMPTY_CLUSTER")),
            "Requires new empty synthetic-only cluster on 55462; never point at an existing customer cluster");
        String data = run(tool("gsql", "-d", "postgres", "-At", "-c", "SHOW data_directory")).strip();
        assertTrue(data.matches("/tmp/gauss-review-cluster-[A-Za-z0-9]+/data"));
        for (String outcome : List.of("success", "failure", "cancel")) {
            Path target = directory.resolve(outcome + ".sql"); Files.writeString(target, "previous backup");
            var settings = mock(PostgreBackupAllSettings.class);
            var info = mock(PostgreDatabaseBackupAllInfo.class);
            var dataSource = mock(PostgreDataSource.class);
            var server = mock(PostgreServerExtension.class);
            var monitor = mock(DBRProgressMonitor.class);
            when(settings.getOutputFile(info)).thenReturn(target.toString());
            when(info.getDataSource()).thenReturn(dataSource); when(dataSource.getServerType()).thenReturn(server);
            Process lock = null;
            try {
                if (!outcome.equals("success")) {
                    lock = new ProcessBuilder(tool("gsql", "-d", "postgres", "-c",
                        "SET application_name='review_dump_lock'; BEGIN; LOCK TABLE review_dump_lock IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(30)"))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    while (!run(tool("gsql", "-d", "postgres", "-At", "-c",
                        "SELECT count(*) FROM pg_stat_activity WHERE application_name='review_dump_lock' AND query LIKE '%pg_sleep%'"))
                        .strip().equals("1")) {
                        assertTrue(System.nanoTime() < deadline); Thread.sleep(100);
                    }
                }
                var handler = new LiveHandler(target, outcome);
                if (outcome.equals("failure")) {
                    assertThrows(IOException.class, () -> handler.executeProcess(monitor, null, settings, info, Log.getLog(getClass())));
                } else {
                    assertEquals(outcome.equals("success"), handler.executeProcess(monitor, null, settings, info, Log.getLog(getClass())));
                }
                assertTrue(handler.sawPassword, "Actual native output must contain a synthetic role password before testing cleanup");
                assertFalse(Files.exists(handler.privateFile));
                String published = Files.readString(target);
                if (outcome.equals("success")) {
                    assertFalse(published.matches("(?s).*PASSWORD\\s+'[^']+'.*"));
                    assertTrue(published.contains("PASSWORD DISABLE"));
                } else { assertEquals("previous backup", published); }
            } finally {
                if (lock != null) {
                    run(tool("gsql", "-d", "postgres", "-At", "-c",
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='review_dump_lock'"));
                    assertTrue(lock.waitFor(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    private static final class LiveHandler extends PostgreDatabaseBackupAllHandler {
        final Path target;
        final String outcome;
        Path privateFile;
        boolean sawPassword;
        LiveHandler(Path target, String outcome) { this.target = target; this.outcome = outcome; }
        @Override protected Path resolveOutputPath(DBRProgressMonitor m, DBTTask t, PostgreBackupAllSettings s,
                                                   PostgreDatabaseBackupAllInfo i) { return target; }
        @Override protected boolean runNativeProcess(DBRProgressMonitor monitor, DBTTask task, PostgreBackupAllSettings settings,
                                                     PostgreDatabaseBackupAllInfo info, Log log) throws IOException, InterruptedException {
            privateFile = Path.of(prepareOutputFile(settings, info));
            String remote = "/tmp/review-dumpall-" + UUID.randomUUID() + ".sql";
            String pidFile = remote + ".pid";
            Path errors = Files.createTempFile("review-dumpall-errors-", ".log");
            Process process = null;
            try {
                var args = tool("gs_dumpall", "--pipeline", "-h", "127.0.0.1", "-U", "gausscore", "-f", remote);
                if (outcome.equals("success")) { args.add("--roles-only"); }
                // exec replaces this owned shell; the recorded PID identifies only this test tool.
                args.add(3, "sh"); args.add(4, "-c"); args.add(5, "echo $$ > '" + pidFile + "'; exec \"$@\""); args.add(6, "review-dumpall");
                args.add(2, "-i");
                process = new ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(errors.toFile()).start();
                Properties credentials = new Properties();
                try (var input = Files.newInputStream(Path.of(System.getenv("GAUSSDB_REVIEW_CONNECTION")))) { credentials.load(input); }
                try (var input = process.getOutputStream()) { input.write((credentials.getProperty("password") + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                if (outcome.equals("failure")) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    while (!run(tool("gsql", "-d", "postgres", "-At", "-c",
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name <> 'review_dump_lock' AND query LIKE 'LOCK TABLE%review_dump_lock%'"))
                        .strip().equals("t")) {
                        assertTrue(System.nanoTime() < deadline, "Native backup must reach the locked fixture table");
                        Thread.sleep(100);
                    }
                }
                if (outcome.equals("cancel")) {
                    Thread.sleep(1500);
                    assertTrue(process.isAlive(), "Dump must still be blocked before cancellation");
                    run(List.of("docker", "exec", "gaussdb-507-ha-lab", "sh", "-c", "kill -TERM $(cat '" + pidFile + "')"));
                    when(monitor.isCanceled()).thenReturn(true);
                }
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Native dump must finish in bounded time");
                run(List.of("docker", "cp", "gaussdb-507-ha-lab:" + remote, privateFile.toString()));
                sawPassword = Files.readString(privateFile).matches("(?s).*PASSWORD\\s+'[^']+'.*");
                if (outcome.equals("failure")) {
                    assertNotEquals(0, process.exitValue());
                    throw new IOException("Actual gs_dumpall backend connection terminated");
                }
                if (outcome.equals("success")) { assertEquals(0, process.exitValue()); }
                return true;
            } catch (IOException | InterruptedException e) { throw e; }
            catch (Exception e) { throw new IOException(e); }
            finally {
                if (process != null && process.isAlive()) { process.destroyForcibly(); }
                new ProcessBuilder("docker", "exec", "gaussdb-507-ha-lab", "rm", "-f", remote, pidFile).start().waitFor();
                Files.deleteIfExists(errors);
            }
        }
    }
}
