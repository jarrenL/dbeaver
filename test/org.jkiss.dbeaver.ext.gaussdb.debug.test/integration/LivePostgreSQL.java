/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
import java.sql.*;
import java.util.concurrent.*;

/** Standalone pldbgapi regression; use only a disposable PostgreSQL database. */
public class LivePostgreSQL {
    private static int assertions;

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
        assertions++;
    }

    private static Object scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(25);
            try (ResultSet result = statement.executeQuery(sql)) {
                return result.next() ? result.getObject(1) : null;
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(25);
            statement.execute(sql);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Pass JDBC URL for a disposable, trusted local PostgreSQL database");
        }
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Connection controller = DriverManager.getConnection(args[0], "postgres", "");
             Connection target = DriverManager.getConnection(args[0], "postgres", "")) {
            execute(controller, "CREATE EXTENSION IF NOT EXISTS pldbgapi");
            execute(controller, "CREATE SCHEMA review_debug");
            execute(controller, """
                CREATE FUNCTION review_debug.child(p integer) RETURNS integer LANGUAGE plpgsql AS $$
                BEGIN
                  RETURN p + 2;
                END
                $$;
                CREATE FUNCTION review_debug.parent(p integer) RETURNS integer LANGUAGE plpgsql AS $$
                DECLARE v integer := p;
                BEGIN
                  v := v + 1;
                  v := review_debug.child(v);
                  RETURN v;
                END
                $$;
                """);
            int oid = ((Number) scalar(controller, "SELECT 'review_debug.parent(integer)'::regprocedure::oid")).intValue();
            int listener = ((Number) scalar(controller, "SELECT pldbg_create_listener()")).intValue();
            int pid = ((Number) scalar(target, "SELECT pg_backend_pid()")).intValue();
            check(Boolean.TRUE.equals(scalar(controller, "SELECT pldbg_set_global_breakpoint(" + listener + "," + oid + ",-1," + pid + ")")), "global breakpoint");
            Future<Object> result = worker.submit(() -> scalar(target, "SELECT review_debug.parent(6)"));
            scalar(controller, "SELECT pldbg_wait_for_target(" + listener + ")");
            check(scalar(controller, "SELECT pldbg_get_source(" + listener + "," + oid + ")").toString().contains("review_debug.child"), "source");
            check(((Number) scalar(controller, "SELECT count(*) FROM pldbg_get_stack(" + listener + ")")).intValue() == 1, "initial stack");
            int line = ((Number) scalar(controller, "SELECT linenumber FROM pldbg_get_variables(" + listener + ") WHERE name='v'")).intValue();
            check(Boolean.TRUE.equals(scalar(controller, "SELECT pldbg_deposit_value(" + listener + ",'v'," + line + ",'20')")), "variable modification");
            check("20".equals(scalar(controller, "SELECT value FROM pldbg_get_variables(" + listener + ") WHERE name='v'")), "variable readback");
            check(Boolean.TRUE.equals(scalar(controller, "SELECT pldbg_set_breakpoint(" + listener + "," + oid + ",6)")), "add breakpoint");
            check(Boolean.TRUE.equals(scalar(controller, "SELECT pldbg_drop_breakpoint(" + listener + "," + oid + ",6)")), "disable breakpoint");
            check(Boolean.TRUE.equals(scalar(controller, "SELECT pldbg_set_breakpoint(" + listener + "," + oid + ",6)")), "re-enable breakpoint");
            scalar(controller, "SELECT pldbg_step_over(" + listener + ")");
            scalar(controller, "SELECT pldbg_step_into(" + listener + ")");
            check(((Number) scalar(controller, "SELECT count(*) FROM pldbg_get_stack(" + listener + ")")).intValue() == 2, "nested stack after step into");
            scalar(controller, "SELECT pldbg_continue(" + listener + ")");
            check(((Number) scalar(controller, "SELECT count(*) FROM pldbg_get_stack(" + listener + ")")).intValue() == 1, "return to caller breakpoint");
            try {
                scalar(controller, "SELECT pldbg_continue(" + listener + ")");
            } catch (SQLException e) {
                // This pldbgapi revision signals target disconnect at normal completion as an error.
                // Require both its exact diagnostic and successful target execution below.
                if (!e.getMessage().contains("select() failed waiting for target")) {
                    throw e;
                }
                System.out.println("Completion diagnostic: " + e.getSQLState() + " " + e.getMessage());
            }
            check(((Number) result.get(30, TimeUnit.SECONDS)).intValue() == 23, "modified variable affects actual return value");
            execute(controller, "DROP SCHEMA review_debug CASCADE");
            System.out.println("PostgreSQL pldbgapi: " + assertions + " assertions passed; JDBC " + controller.getMetaData().getDriverVersion());
        } finally {
            worker.shutdownNow();
        }
    }
}
