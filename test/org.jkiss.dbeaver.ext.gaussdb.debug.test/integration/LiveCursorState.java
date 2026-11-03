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

import org.jkiss.dbeaver.debug.DBGVariable;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBCursorVariables;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugVariable;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Real-server cursor status contract, using the same aggregation as the debugger.
 *
 * Accepts a local 0600 properties file containing url, user, password and optionally
 * driverClass. Only creates and removes its own random schema in that database.
 * Requires the GaussDB JDBC jar and current debug.core / gaussdb.debug.core classes.
 * Does not fetch through the controller or change database compatibility/settings.
 * Modern inline cursor status is validated against 507; legacy hidden attributes
 * are covered by unit fixtures, rather than claimed as another server validation.
 */
public final class LiveCursorState {
    private final Properties properties;
    private final String schema = "dbv_cursor_" + UUID.randomUUID().toString().replace("-", "");
    private int passed;
    private int failed;

    private LiveCursorState(Properties properties) {
        this.properties = properties;
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Provide a local JDBC properties file");
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(arguments[0]))) {
            properties.load(reader);
        }
        Class.forName(properties.getProperty("driverClass", "com.huawei.gaussdb.jdbc.Driver"));
        LiveCursorState test = new LiveCursorState(properties);
        try {
            test.run();
        } catch (Exception | AssertionError exception) {
            if (test.failed == 0) {
                test.failed++;
            }
            System.out.println("FAIL unexpected cursor validation error: " + exception);
            throw exception;
        } finally {
            System.out.println("TOTAL CHECKS " + (test.passed + test.failed)
                + " PASS " + test.passed + " FAIL " + test.failed);
        }
        if (test.failed != 0) {
            throw new AssertionError("Cursor validation failed");
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(properties.getProperty("url"), properties);
    }

    private void run() throws Exception {
        try (Connection setup = open()) {
            try (Statement statement = setup.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT version(), current_setting('sql_compatibility')")) {
                rows.next();
                System.out.println("SERVER " + rows.getString(1) + " COMPATIBILITY " + rows.getString(2));
            }
            execute(setup, "CREATE SCHEMA " + schema);
            try {
                execute(setup, """
                    CREATE PROCEDURE %s.cursor_states() AS DECLARE
                     c refcursor;
                     c10 refcursor;
                     v_id integer := -1;
                     v_other integer := -1;
                     tick integer := 0;
                    BEGIN
                     tick := 1;
                     OPEN c FOR SELECT 1 AS id UNION ALL SELECT 2 ORDER BY 1;
                     tick := 2;
                     OPEN c10 FOR SELECT 101 AS id;
                     tick := 3;
                     FETCH c INTO v_id;
                     tick := 4;
                     FETCH c10 INTO v_other;
                     tick := 5;
                     FETCH c INTO v_id;
                     tick := 6;
                     FETCH c INTO v_id;
                     tick := 7;
                     CLOSE c;
                     tick := 8;
                     CLOSE c10;
                     tick := 9;
                    END;
                    """.formatted(schema));
                execute(setup, """
                    CREATE PROCEDURE %s.cursor_inner() AS DECLARE
                     c refcursor;
                     v_id integer := -1;
                     tick integer := 0;
                    BEGIN
                     OPEN c FOR SELECT 222 AS id;
                     FETCH c INTO v_id;
                     tick := 22;
                     CLOSE c;
                    END;
                    """.formatted(schema));
                execute(setup, """
                    CREATE PROCEDURE %s.cursor_outer() AS DECLARE
                     c refcursor;
                     v_id integer := -1;
                     tick integer := 0;
                    BEGIN
                     OPEN c FOR SELECT 111 AS id;
                     tick := 11;
                     %s.cursor_inner();
                     FETCH c INTO v_id;
                     tick := 12;
                     CLOSE c;
                    END;
                    """.formatted(schema, schema));
                debug("cursor_states", this::cursorStates);
                debug("cursor_outer", this::nestedFrames);
            } finally {
                execute(setup, "DROP PROCEDURE IF EXISTS " + schema + ".cursor_outer()");
                execute(setup, "DROP PROCEDURE IF EXISTS " + schema + ".cursor_inner()");
                execute(setup, "DROP PROCEDURE IF EXISTS " + schema + ".cursor_states()");
                execute(setup, "DROP SCHEMA " + schema + " RESTRICT");
                System.out.println("CLEANED owned cursor procedures and schema");
            }
        }
    }

    private void cursorStates(Connection controller) throws Exception {
        List<DBGVariable<?>> initial = variables(controller, 0);
        cursor(initial, "c", "false", "false", "false", "0", "before OPEN");
        check(find(initial, "c").getChildren().stream().noneMatch(child -> "NAME".equals(child.getName())),
            "unopened cursor does not invent a portal name");
        advanceTo(controller, "tick := 2;");
        List<DBGVariable<?>> opened = variables(controller, 0);
        cursor(opened, "c", "true", "false", "false", "0", "after OPEN");
        check(find(opened, "c").getChildren().stream().anyMatch(child -> "NAME".equals(child.getName())),
            "opened cursor exposes the actual portal name");
        cursor(opened, "c10", "false", "false", "false", "0", "similarly named unopened cursor");
        advanceTo(controller, "tick := 3;");
        for (int attempt = 0; attempt < 3; attempt++) {
            List<DBGVariable<?>> snapshot = variables(controller, 0);
            cursor(snapshot, "c", "true", "false", "false", "0", "repeated snapshot " + attempt);
            cursor(snapshot, "c10", "true", "false", "false", "0", "second cursor " + attempt);
        }
        advanceTo(controller, "tick := 4;");
        List<DBGVariable<?>> first = variables(controller, 0);
        cursor(first, "c", "true", "true", "false", "1", "after first FETCH");
        check("1".equals(find(first, "v_id").getVal()), "repeated status reads preserve first business FETCH");
        cursor(first, "c10", "true", "false", "false", "0", "other cursor remains unfetched");
        advanceTo(controller, "tick := 5;");
        List<DBGVariable<?>> secondCursor = variables(controller, 0);
        check("101".equals(find(secondCursor, "v_other").getVal()), "second cursor returns its own row");
        cursor(secondCursor, "c10", "true", "true", "false", "1", "second cursor fetched");
        advanceTo(controller, "tick := 6;");
        List<DBGVariable<?>> second = variables(controller, 0);
        cursor(second, "c", "true", "true", "false", "2", "after second FETCH");
        check("2".equals(find(second, "v_id").getVal()), "status reads preserve second business FETCH");
        advanceTo(controller, "tick := 7;");
        List<DBGVariable<?>> exhausted = variables(controller, 0);
        cursor(exhausted, "c", "true", "false", "true", "2", "after exhausted FETCH");
        cursor(exhausted, "c10", "true", "true", "false", "1", "exhausted cursor does not change c10");
        advanceTo(controller, "tick := 8;");
        List<DBGVariable<?>> closed = variables(controller, 0);
        check("false".equals(attribute(find(closed, "c"), "%ISOPEN")), "CLOSE refreshes ISOPEN to false");
        check("true".equals(attribute(find(closed, "c10"), "%ISOPEN")), "closing c leaves c10 open");
        advanceTo(controller, "tick := 9;");
        check("false".equals(attribute(find(variables(controller, 0), "c10"), "%ISOPEN")),
            "second CLOSE refreshes its cursor state");
    }

    private void nestedFrames(Connection controller) throws Exception {
        advanceTo(controller, schema + ".cursor_inner();");
        control(controller, "step");
        advanceTo(controller, "tick := 22;");
        List<DBGVariable<?>> inner = variables(controller, 0);
        List<DBGVariable<?>> outer = variables(controller, 1);
        cursor(inner, "c", "true", "true", "false", "1", "nested current frame");
        cursor(outer, "c", "true", "false", "false", "0", "nested caller frame");
        check("222".equals(find(inner, "v_id").getVal()), "nested frame scalar matches its cursor");
        check("-1".equals(find(outer, "v_id").getVal()), "caller scalar remains unchanged");
        check(!attribute(find(inner, "c"), "NAME").equals(attribute(find(outer, "c"), "NAME")),
            "same cursor name in different frames retains distinct portals");
        advanceTo(controller, "tick := 12;");
        List<DBGVariable<?>> caller = variables(controller, 0);
        cursor(caller, "c", "true", "true", "false", "1", "caller after inner returns");
        check("111".equals(find(caller, "v_id").getVal()), "caller FETCH retains its original first row");
    }

    private void cursor(
        List<DBGVariable<?>> variables, String name, String open, String found,
        String notFound, String count, String stage
    ) {
        DBGVariable<?> cursor = find(variables, name);
        check("refcursor".equals(cursor.getDataTypeName()), stage + " / " + name + " type remains refcursor");
        check(cursor.isReadOnly() && cursor.getChildren().stream().allMatch(DBGVariable::isReadOnly),
            stage + " / " + name + " parent and attributes are read-only");
        check(open.equals(attribute(cursor, "%ISOPEN")) && found.equals(attribute(cursor, "%FOUND"))
            && notFound.equals(attribute(cursor, "%NOTFOUND")) && count.equals(attribute(cursor, "%ROWCOUNT")),
            stage + " / " + name + " attributes " + cursor.getVal());
        check(cursor.getChildren().stream().allMatch(child -> child.getChildren().isEmpty()),
            stage + " / " + name + " attribute snapshots are leaf values");
    }

    private List<DBGVariable<?>> variables(Connection connection, int frame) throws SQLException {
        List<GaussDBDebugVariable> raw = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM DBE_PLDEBUGGER.info_locals(" + frame + ")")) {
            while (rows.next()) {
                raw.add(new GaussDBDebugVariable(rows.getString("varname"), rows.getString("vartype"),
                    rows.getString("value"), rows.getString("package_name"), rows.getBoolean("isconst"), frame));
            }
        }
        return GaussDBCursorVariables.aggregate(raw);
    }

    private static DBGVariable<?> find(List<DBGVariable<?>> variables, String name) {
        return variables.stream().filter(variable -> name.equals(variable.getName())).findFirst()
            .orElseThrow(() -> new AssertionError("Missing variable " + name));
    }

    private static String attribute(DBGVariable<?> cursor, String name) {
        return cursor.getChildren().stream().filter(child -> name.equals(child.getName())).findFirst()
            .map(child -> String.valueOf(child.getVal()))
            .orElseThrow(() -> new AssertionError("Missing cursor attribute " + name + " in " + cursor.getVal()));
    }

    private void advanceTo(Connection controller, String query) throws Exception {
        for (int step = 0; step < 50; step++) {
            if (query.equals(control(controller, "next").trim())) {
                return;
            }
        }
        throw new AssertionError("Did not reach statement " + query);
    }

    private void debug(String routine, DebugScenario scenario) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> job = null;
        try (Connection target = open(); Connection controller = open()) {
            long oid;
            try (PreparedStatement statement = target.prepareStatement(
                "SELECT p.oid FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
                    + "WHERE n.nspname=? AND p.proname=?")) {
                statement.setString(1, schema);
                statement.setString(2, routine);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("Missing owned routine " + routine);
                    }
                    oid = rows.getLong(1);
                }
            }
            target.setAutoCommit(false);
            String node;
            int port;
            try (Statement statement = target.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT * FROM DBE_PLDEBUGGER.turn_on(" + oid + "::oid)")) {
                rows.next();
                node = rows.getString(1);
                port = rows.getInt(2);
            }
            try (Statement call = target.createStatement()) {
                call.setQueryTimeout(25);
                job = executor.submit(() -> {
                    try {
                        call.execute("CALL " + schema + "." + routine + "()");
                    } catch (SQLException exception) {
                        throw new IllegalStateException("Target CALL failed", exception);
                    }
                });
                try {
                    attach(controller, node, port);
                    scenario.run(controller);
                    for (int step = 0; step < 10 && !job.isDone(); step++) {
                        try {
                            control(controller, "continue");
                        } catch (SQLException exception) {
                            if (!"D0005".equals(exception.getSQLState())) {
                                throw exception;
                            }
                            break;
                        }
                        try {
                            job.get(250, TimeUnit.MILLISECONDS);
                        } catch (java.util.concurrent.TimeoutException ignored) {
                            // A nested END can finish only the inner routine; continue the caller.
                        }
                    }
                    job.get(10, TimeUnit.SECONDS);
                    target.rollback();
                    execute(target, "SELECT DBE_PLDEBUGGER.turn_off(" + oid + "::oid)");
                    target.rollback();
                    check(true, routine + " completes, target joins, rollback and turn_off succeed");
                } finally {
                    if (!job.isDone()) {
                        try {
                            execute(controller, "SELECT DBE_PLDEBUGGER.abort()");
                        } catch (SQLException ignored) {
                            // Cancellation below is the fallback for a failed debug controller.
                        }
                    }
                    if (!job.isDone()) {
                        call.cancel();
                    }
                    try {
                        job.get(5, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        // Do not issue turn_off concurrently with the target statement.
                    }
                    target.rollback();
                }
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void attach(Connection controller, String node, int port) throws Exception {
        try (PreparedStatement statement = controller.prepareStatement("SELECT * FROM DBE_PLDEBUGGER.attach(?,?)")) {
            statement.setString(1, node);
            statement.setInt(2, port);
            statement.setQueryTimeout(10);
            for (int attempt = 0; ; attempt++) {
                try {
                    statement.execute();
                    return;
                } catch (SQLException exception) {
                    if (!"D0011".equals(exception.getSQLState()) || attempt >= 100) {
                        throw exception;
                    }
                }
                Thread.sleep(25);
            }
        }
    }

    private static String control(Connection controller, String command) throws SQLException {
        try (Statement statement = controller.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM DBE_PLDEBUGGER." + command + "()")) {
            rows.next();
            String query = rows.getString("query");
            System.out.println("CONTROL " + command + " line=" + rows.getInt("lineno") + " query=" + query);
            return query == null ? "" : query;
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void check(boolean condition, String description) {
        if (condition) {
            passed++;
            System.out.println("PASS " + description);
        } else {
            failed++;
            System.out.println("FAIL " + description);
            throw new AssertionError(description);
        }
    }

    @FunctionalInterface
    private interface DebugScenario {
        void run(Connection controller) throws Exception;
    }
}
