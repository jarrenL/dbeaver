/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.eclipse.core.resources.IMarkerDelta;
import org.jkiss.dbeaver.debug.DBGConstants;
import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.debug.core.breakpoints.DatabaseLineBreakpoint;
import org.jkiss.dbeaver.debug.core.model.DatabaseDebugTarget;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugConstants;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.jdbc.*;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** Real vendor JDBC behind DBeaver session interfaces; no SWT lifecycle claim. */
class GaussDBSessionLiveTest {
    private Connection connect() throws Exception {
        return connect(0);
    }

    private Connection connect(int proxyPort) throws Exception {
        return connect(proxyPort, 3);
    }

    private Connection connect(int proxyPort, int timeout) throws Exception {
        String config = System.getenv("GAUSSDB_REVIEW_CONNECTION"), jar = System.getenv("GAUSSDB_REVIEW_JDBC");
        assumeTrue(config != null && jar != null, "Requires isolated live review database");
        Properties props = new Properties();
        try (var in = Files.newInputStream(Path.of(config))) { props.load(in); }
        assertTrue(props.getProperty("review.databasePrefix", "").matches("review_0917_[a-f0-9]{8}_"));
        assertTrue(props.getProperty("url", "").endsWith("/" + props.getProperty("review.databasePrefix") + "ora"));
        props.setProperty("socketTimeout", "20");
        props.setProperty("sslmode", "disable");
        String url = props.getProperty("url");
        if (proxyPort != 0) {
            url = url.replaceFirst("//[^/]+/", "//127.0.0.1:" + proxyPort + "/");
            props.setProperty("socketTimeout", Integer.toString(timeout));
        }
        var loader = new URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        return ((Driver) loader.loadClass(props.getProperty("review.driverClass")).getConstructor().newInstance())
            .connect(url, props);
    }

    @Test
    void blackholedCommitWithoutReadTimeoutBoundsCancellationAndClose() throws Exception {
        try (Connection observer = connect(); CommitReplyProxy proxy = new CommitReplyProxy();
             Connection target = connect(proxy.port(), 0)) {
            exec(observer, "CREATE SCHEMA review_commit_cancel");
            exec(observer, "CREATE TABLE review_commit_cancel.rows(id integer)");
            var workers = Executors.newFixedThreadPool(2);
            try {
                target.setAutoCommit(false); exec(target, "INSERT INTO review_commit_cancel.rows VALUES(1)");
                var session = new GaussDBDebugSession(mock(GaussDBDebugController.class), context(observer),
                    context(target), mock(GaussDBProcedure.class));
                for (String name : List.of("transactionCompletionPending", "targetSucceeded")) {
                    Field field = GaussDBDebugSession.class.getDeclaredField(name);
                    field.setAccessible(true); field.set(session, true);
                }
                var monitor = mock(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor.class);
                Future<?> commit = workers.submit(() -> {
                    session.completeTransaction(monitor, org.jkiss.dbeaver.debug.DBGTransactionAction.COMMIT); return null;
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (proxy.commits.get() == 0) { assertTrue(System.nanoTime() < deadline); Thread.sleep(20); }
                while (!scalar(observer, "SELECT count(*) FROM review_commit_cancel.rows").equals("1")) {
                    assertTrue(System.nanoTime() < deadline); Thread.sleep(20);
                }
                when(monitor.isCanceled()).thenReturn(true);
                Future<?> close = workers.submit(() -> { session.closeSession(monitor); return null; });
                // The product, not the test proxy, must break the network wait.
                ExecutionException failure = assertThrows(ExecutionException.class, () -> commit.get(15, TimeUnit.SECONDS));
                assertTrue(failure.getCause().getMessage().contains("not confirmed"));
                close.get(5, TimeUnit.SECONDS);
                when(monitor.isCanceled()).thenReturn(false);
                assertThrows(DBGException.class, () -> session.completeTransaction(monitor,
                    org.jkiss.dbeaver.debug.DBGTransactionAction.COMMIT));
                assertEquals(1, proxy.commits.get());
            } finally {
                if (proxy.client != null) { proxy.client.close(); }
                workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
                exec(observer, "DROP SCHEMA review_commit_cancel CASCADE");
            }
        }
    }

    @Test
    void lostCommitReplyIsUnknownAndNeverRetriedAlthoughDatabaseCommitted() throws Exception {
        try (Connection observer = connect(); CommitReplyProxy proxy = new CommitReplyProxy();
             Connection target = connect(proxy.port())) {
            exec(observer, "CREATE SCHEMA review_commit_fault");
            exec(observer, "CREATE TABLE review_commit_fault.rows(id integer)");
            try {
                target.setAutoCommit(false);
                exec(target, "INSERT INTO review_commit_fault.rows VALUES (1)");
                var session = new GaussDBDebugSession(mock(GaussDBDebugController.class), context(observer),
                    context(target), mock(GaussDBProcedure.class));
                for (String name : List.of("transactionCompletionPending", "targetSucceeded")) {
                    Field field = GaussDBDebugSession.class.getDeclaredField(name);
                    field.setAccessible(true); field.set(session, true);
                }
                long start = System.nanoTime();
                DBGException failure = assertThrows(DBGException.class, () -> session.completeTransaction(
                    new VoidProgressMonitor(), org.jkiss.dbeaver.debug.DBGTransactionAction.COMMIT));
                assertTrue(failure.getMessage().contains("not confirmed"));
                assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10);
                assertEquals("1", scalar(observer, "SELECT count(*) FROM review_commit_fault.rows"));
                assertTrue(session.isTransactionCompletionPending());
                DBGException retry = assertThrows(DBGException.class, () -> session.completeTransaction(
                    new VoidProgressMonitor(), org.jkiss.dbeaver.debug.DBGTransactionAction.COMMIT));
                assertTrue(retry.getMessage().contains("must not be retried"));
                assertEquals(1, proxy.commits.get());
            } finally { exec(observer, "DROP SCHEMA review_commit_fault CASCADE"); }
        }
    }

    /** Drops only server replies after forwarding the actual COMMIT on a dedicated test socket. */
    private static final class CommitReplyProxy implements AutoCloseable {
        final java.net.ServerSocket listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final java.util.concurrent.atomic.AtomicBoolean dropping = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();
        volatile java.net.Socket client;
        volatile java.net.Socket server;

        CommitReplyProxy() throws Exception {
            Properties props = new Properties();
            try (var input = Files.newInputStream(Path.of(System.getenv("GAUSSDB_REVIEW_CONNECTION")))) { props.load(input); }
            var endpoint = java.net.URI.create(props.getProperty("url").substring(5));
            pool.submit(() -> {
                try {
                    client = listener.accept(); server = new java.net.Socket(endpoint.getHost(), endpoint.getPort());
                    pool.submit(() -> {
                        try {
                            byte[] buffer = new byte[8192]; int count;
                            while ((count = server.getInputStream().read(buffer)) >= 0) {
                                if (!dropping.get()) { client.getOutputStream().write(buffer, 0, count); client.getOutputStream().flush(); }
                            }
                        } catch (java.io.IOException expectedDisconnect) { }
                    });
                    // Startup packet has no type byte; all following frontend packets do.
                    var input = new java.io.DataInputStream(client.getInputStream());
                    var output = new java.io.DataOutputStream(server.getOutputStream());
                    int size = input.readInt(); output.writeInt(size); output.write(input.readNBytes(size - 4)); output.flush();
                    while (true) {
                        int type = input.read(); if (type < 0) { break; }
                        int length = input.readInt(); byte[] body = input.readNBytes(length - 4);
                        String sql = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                        if ((type == 'Q' || type == 'P') && sql.contains("COMMIT")) {
                            commits.incrementAndGet(); dropping.set(true);
                        }
                        output.writeByte(type); output.writeInt(length); output.write(body); output.flush();
                    }
                } catch (java.io.IOException expectedDisconnect) { }
            });
        }
        int port() { return listener.getLocalPort(); }
        @Override public void close() throws Exception {
            listener.close(); if (client != null) { client.close(); } if (server != null) { server.close(); }
            pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T bridge(Class<T> type, Object delegate) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (type == JDBCSession.class && method.getName().equals("close")) { return null; }
            Class<?> jdbcType = delegate instanceof Connection ? Connection.class
                : delegate instanceof PreparedStatement ? PreparedStatement.class : ResultSet.class;
            try {
                Object value = jdbcType.getMethod(method.getName(), method.getParameterTypes()).invoke(delegate, args);
                if (value instanceof PreparedStatement s) { return bridge(JDBCPreparedStatement.class, s); }
                if (value instanceof ResultSet r) { return bridge(JDBCResultSet.class, r); }
                return value;
            } catch (InvocationTargetException e) { throw e.getCause(); }
        });
    }

    private static JDBCExecutionContext context(Connection connection) {
        var context = mock(JDBCExecutionContext.class);
        when(context.openSession(any(), any(), anyString())).thenAnswer(c -> bridge(JDBCSession.class, connection));
        return context;
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement()) { s.setQueryTimeout(15); s.execute(sql); }
    }

    private static String scalar(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { assertTrue(r.next()); return r.getString(1); }
    }

    @Test
    void defaultOverloadValidationUsesRealCatalogAndRejectsAmbiguity() throws Exception {
        try (Connection c = connect()) {
            exec(c, "CREATE SCHEMA review_defaults");
            try {
                exec(c, "CREATE FUNCTION review_defaults.f(p integer DEFAULT 7) RETURNS integer AS $$ BEGIN RETURN p; END; $$ LANGUAGE plpgsql");
                long oid = Long.parseLong(scalar(c, "SELECT p.oid FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='review_defaults' AND p.proname='f'"));
                var routine = mock(GaussDBProcedure.class);
                when(routine.getObjectId()).thenReturn(oid);
                var session = new GaussDBDebugSession(mock(GaussDBDebugController.class), context(c), context(c), routine);
                session.validateDefaultInvocation(new VoidProgressMonitor());
                exec(c, "CREATE FUNCTION review_defaults.f(p text) RETURNS text AS $$ BEGIN RETURN p; END; $$ LANGUAGE plpgsql");
                assertThrows(DBGException.class, () -> session.validateDefaultInvocation(new VoidProgressMonitor()));
            } finally { exec(c, "DROP SCHEMA review_defaults CASCADE"); }
        }
    }

    @Test
    void productionBreakpointCommandsAndDeletedMarkerOperateOnRealDebugger() throws Exception {
        try (Connection t = connect(); Connection c = connect()) {
            exec(t, "CREATE SCHEMA review_breakpoints");
            var pool = Executors.newSingleThreadExecutor();
            Future<?> targetJob = null;
            try {
                exec(t, "CREATE PROCEDURE review_breakpoints.p() AS DECLARE\nv integer := 0;\nBEGIN\nv := v + 1;\nv := v + 2;\nEND;");
                long oid = Long.parseLong(scalar(t, "SELECT p.oid FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='review_breakpoints'"));
                String database = scalar(t, "SELECT current_database()");
                t.setAutoCommit(false);
                String node; int port;
                try (Statement s = t.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM DBE_PLDEBUGGER.turn_on(" + oid + "::oid)")) {
                    assertTrue(r.next()); node = r.getString(1); port = r.getInt(2);
                }
                targetJob = pool.submit(() -> { try { exec(t, "CALL review_breakpoints.p()"); } catch (Exception e) { throw new RuntimeException(e); } });
                try (PreparedStatement attach = c.prepareStatement("SELECT * FROM DBE_PLDEBUGGER.attach(?, ?)")) {
                    attach.setString(1, node); attach.setInt(2, port); attach.setQueryTimeout(10);
                    GaussDBDebugSession.attachWithRetry(attach, new VoidProgressMonitor(), () -> false);
                }
                int line = Integer.parseInt(scalar(c, "SELECT max(lineno) FROM DBE_PLDEBUGGER.info_code(" + oid + "::oid) WHERE canbreak"));
                var container = mock(DBPDataSourceContainer.class);
                when(container.getId()).thenReturn("live-review");
                var controller = new GaussDBDebugController(container, Map.of(GaussDBDebugConstants.ATTR_DATABASE_NAME, database));
                var session = new GaussDBDebugSession(controller, context(c), context(t), mock(GaussDBProcedure.class));
                var descriptor = new GaussDBDebugBreakpointDescriptor(oid, line, database);
                assertNull(controller.describeBreakpoint(new GaussDBDebugBreakpointDescriptor(oid, line, database + "_other").toMap()));
                assertNull(controller.describeBreakpoint(new GaussDBDebugBreakpointDescriptor(oid, line).toMap()));
                session.addBreakpoint(new VoidProgressMonitor(), controller.describeBreakpoint(descriptor.toMap()));
                session.addBreakpoint(new VoidProgressMonitor(), descriptor);
                assertEquals("1", scalar(c, "SELECT count(*) FROM DBE_PLDEBUGGER.info_breakpoints()"));
                session.disableBreakpoint(new VoidProgressMonitor(), descriptor);
                session.enableBreakpoint(new VoidProgressMonitor(), descriptor);
                var contenders = Executors.newFixedThreadPool(4);
                try {
                    var start = new CyclicBarrier(4);
                    List<Future<?>> jobs = new ArrayList<>();
                    for (int worker = 0; worker < 4; worker++) {
                        jobs.add(contenders.submit(() -> {
                            start.await(5, TimeUnit.SECONDS);
                            for (int iteration = 0; iteration < 10; iteration++) {
                                var concurrent = new GaussDBDebugBreakpointDescriptor(oid, line, database);
                                session.addBreakpoint(new VoidProgressMonitor(), concurrent);
                                session.disableBreakpoint(new VoidProgressMonitor(), concurrent);
                                session.enableBreakpoint(new VoidProgressMonitor(), concurrent);
                                session.removeBreakpoint(new VoidProgressMonitor(), concurrent);
                            }
                            return null;
                        }));
                    }
                    for (Future<?> job : jobs) { job.get(15, TimeUnit.SECONDS); }
                } finally { contenders.shutdownNow(); assertTrue(contenders.awaitTermination(5, TimeUnit.SECONDS)); }
                session.addBreakpoint(new VoidProgressMonitor(), descriptor);
                assertEquals("1", scalar(c, "SELECT count(*) FROM DBE_PLDEBUGGER.info_breakpoints()"));
                assertEquals(1, session.getBreakpoints().size());
                var target = mock(DatabaseDebugTarget.class, CALLS_REAL_METHODS);
                for (var entry : Map.of("controller", controller, "session", session,
                    "breakpointIdentities", new java.util.concurrent.ConcurrentHashMap<>()).entrySet()) {
                    Field f = DatabaseDebugTarget.class.getDeclaredField(entry.getKey()); f.setAccessible(true); f.set(target, entry.getValue());
                }
                var marker = mock(DatabaseLineBreakpoint.class);
                when(marker.getModelIdentifier()).thenReturn(DBGConstants.MODEL_IDENTIFIER_DATABASE);
                var delta = mock(IMarkerDelta.class);
                var attributes = descriptor.toMap(); attributes.put(DBGConstants.BREAKPOINT_ATTRIBUTE_DATASOURCE_ID, "live-review");
                when(delta.getAttributes()).thenReturn(attributes);
                target.breakpointRemoved(marker, delta);
                assertEquals("0", scalar(c, "SELECT count(*) FROM DBE_PLDEBUGGER.info_breakpoints()"));
                verify(marker, never()).getMarker();
                verifyRealWorkspaceMarkerDeletion(c, controller, session, descriptor);
            } finally {
                if (targetJob != null && !targetJob.isDone()) {
                    try { exec(c, "SELECT DBE_PLDEBUGGER.abort()"); } catch (Exception ignored) { }
                }
                if (targetJob != null) {
                    try { targetJob.get(15, TimeUnit.SECONDS); } catch (ExecutionException expectedAbort) { }
                }
                pool.shutdownNow();
                if (!t.getAutoCommit()) { t.rollback(); t.setAutoCommit(true); }
                exec(t, "DROP SCHEMA review_breakpoints CASCADE");
            }
        }
    }

    private static void verifyRealWorkspaceMarkerDeletion(Connection connection, GaussDBDebugController controller,
                                                          GaussDBDebugSession session,
                                                          GaussDBDebugBreakpointDescriptor descriptor) throws Exception {
        var project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot()
            .getProject("gauss-review-marker-" + UUID.randomUUID());
        var plugin = org.eclipse.debug.core.DebugPlugin.getDefault();
        var manager = plugin.getBreakpointManager();
        var target = new DatabaseDebugTarget(DBGConstants.MODEL_IDENTIFIER_DATABASE,
            mock(org.eclipse.debug.core.ILaunch.class), mock(org.eclipse.debug.core.model.IProcess.class), controller);
        Field sessionField = DatabaseDebugTarget.class.getDeclaredField("session");
        sessionField.setAccessible(true); sessionField.set(target, session);
        var breakpoint = new WorkspaceBreakpoint();
        CountDownLatch removed = new CountDownLatch(1);
        var nullRemovalDelta = new java.util.concurrent.atomic.AtomicBoolean();
        var listener = new org.eclipse.debug.core.IBreakpointListener() {
            public void breakpointAdded(org.eclipse.debug.core.model.IBreakpoint b) { }
            public void breakpointChanged(org.eclipse.debug.core.model.IBreakpoint b, IMarkerDelta delta) { }
            public void breakpointRemoved(org.eclipse.debug.core.model.IBreakpoint b, IMarkerDelta delta) {
                if (b == breakpoint) { nullRemovalDelta.set(delta == null); removed.countDown(); }
            }
        };
        manager.addBreakpointListener(listener);
        try {
            project.create(null); project.open(null);
            var marker = project.createMarker(DBGConstants.BREAKPOINT_ID_DATABASE_LINE);
            var attributes = descriptor.toMap();
            // Match DatabaseLineBreakpoint's constructor: resource markers only accept int, not long.
            attributes.put(org.eclipse.core.resources.IMarker.LINE_NUMBER, Math.toIntExact(descriptor.getLineNumber()));
            attributes.put(DBGConstants.BREAKPOINT_ATTRIBUTE_DATASOURCE_ID, "live-review");
            attributes.put(org.eclipse.debug.core.model.IBreakpoint.ID, DBGConstants.MODEL_IDENTIFIER_DATABASE);
            attributes.put(org.eclipse.debug.core.model.IBreakpoint.ENABLED, true);
            marker.setAttributes(attributes); breakpoint.assign(marker);
            manager.addBreakpoint(breakpoint);
            assertEquals("1", scalar(connection, "SELECT count(*) FROM DBE_PLDEBUGGER.info_breakpoints()"));
            org.eclipse.core.resources.ResourcesPlugin.getWorkspace().build(
                org.eclipse.core.resources.IncrementalProjectBuilder.INCREMENTAL_BUILD, null);
            marker.delete(); // Exercise Eclipse's actual callback; do not assume it supplies a delta.
            org.eclipse.core.resources.ResourcesPlugin.getWorkspace().build(
                org.eclipse.core.resources.IncrementalProjectBuilder.INCREMENTAL_BUILD, null);
            assertTrue(removed.await(10, TimeUnit.SECONDS));
            assertTrue(nullRemovalDelta.get(), "This Eclipse version removes resource breakpoints without a marker delta");
            assertFalse(marker.exists());
            assertEquals("0", scalar(connection, "SELECT count(*) FROM DBE_PLDEBUGGER.info_breakpoints()"));
        } finally {
            manager.removeBreakpointListener(listener);
            manager.removeBreakpointListener(target); manager.removeBreakpointManagerListener(target);
            plugin.removeDebugEventListener(target); controller.unregisterEventHandler(target);
            if (manager.isRegistered(breakpoint)) { manager.removeBreakpoint(breakpoint, true); }
            if (project.exists()) { project.delete(true, true, null); }
        }
    }

    private static final class WorkspaceBreakpoint extends DatabaseLineBreakpoint {
        void assign(org.eclipse.core.resources.IMarker marker) throws org.eclipse.core.runtime.CoreException { setMarker(marker); }
    }
}
