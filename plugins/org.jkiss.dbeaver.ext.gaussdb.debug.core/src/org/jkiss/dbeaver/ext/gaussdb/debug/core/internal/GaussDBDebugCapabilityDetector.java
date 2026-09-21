/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** Checks the exact DBE_PLDEBUGGER overloads used by this client and their EXECUTE privileges. */
final class GaussDBDebugCapabilityDetector {
    private static final Map<String, Set<String>> REQUIRED_SIGNATURES = Map.ofEntries(
        Map.entry("turn_on", Set.of("26")),                 // oid
        Map.entry("turn_off", Set.of("26")),                // oid
        Map.entry("attach", Set.of("25 23")),               // text, integer
        Map.entry("continue", Set.of("")),
        Map.entry("step", Set.of("")),
        Map.entry("next", Set.of("")),
        Map.entry("finish", Set.of("")),
        Map.entry("abort", Set.of("")),
        Map.entry("backtrace", Set.of("")),
        Map.entry("info_locals", Set.of("23")),             // integer (has a default)
        Map.entry("info_code", Set.of("26")),               // oid
        Map.entry("set_var", Set.of("25 25")),               // text, text
        // GaussDB 507 exposes oid,integer; later references document text,integer.
        Map.entry("add_breakpoint", Set.of("26 23", "25 23")),
        Map.entry("delete_breakpoint", Set.of("23")),
        Map.entry("enable_breakpoint", Set.of("23")),
        Map.entry("disable_breakpoint", Set.of("23")),
        Map.entry("info_breakpoints", Set.of(""))
    );

    private GaussDBDebugCapabilityDetector() {
    }

    static String check(JDBCExecutionContext context, DBRProgressMonitor monitor, long routineOid) throws DBGException {
        Set<String> breakpointSignatures = new HashSet<>();
        Set<String> matching = new HashSet<>();
        Set<String> executable = new HashSet<>();
        String sql = "SELECT lower(p.proname),p.proargtypes::text," +
            "pg_catalog.has_function_privilege(p.oid,'EXECUTE') " +
            "FROM pg_catalog.pg_proc p " +
            "JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace " +
            "WHERE upper(n.nspname)='DBE_PLDEBUGGER' AND lower(p.proname) IN (" + placeholders() + ")";
        try (JDBCSession session = context.openSession(
            monitor, DBCExecutionPurpose.UTIL, "Check DBE_PLDEBUGGER capabilities");
             PreparedStatement statement = session.prepareStatement(sql)) {
            int index = 1;
            for (String name : REQUIRED_SIGNATURES.keySet()) {
                statement.setString(index++, name);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String name = result.getString(1);
                    String arguments = normalizeOidVector(result.getString(2));
                    if (hasRequiredSignature(name, arguments)) {
                        matching.add(name);
                        if (result.getBoolean(3)) {
                            executable.add(name);
                            if ("add_breakpoint".equals(name)) {
                                breakpointSignatures.add(arguments);
                            }
                        }
                    }
                }
            }
            assertComplete(matching, executable);
            checkDebuggerRole(session);
            checkRoutineExecutePrivilege(session, routineOid);
            return breakpointArgumentType(breakpointSignatures);
        } catch (SQLException e) {
            throw new DBGException("Unable to validate GaussDB debugger signatures and privileges: " + e.getMessage(), e);
        }
    }

    static String breakpointArgumentType(Set<String> executableSignatures) throws DBGException {
        if (executableSignatures.contains("26 23")) {
            return "oid";
        }
        if (executableSignatures.contains("25 23")) {
            return "text";
        }
        throw new DBGException("No executable add_breakpoint overload");
    }

    static void checkDebuggerRole(JDBCSession session) throws SQLException, DBGException {
        String sql = "SELECT (r.rolsuper OR r.rolsystemadmin) " +
            "FROM pg_catalog.pg_roles r WHERE r.rolname=current_user";
        try (PreparedStatement statement = session.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (result.next() && result.getBoolean(1)) {
                return;
            }
        }
        // GaussDB filters pg_roles for ordinary users: membership may be granted
        // even though the built-in role itself is absent from their catalog view.
        try (PreparedStatement statement = session.prepareStatement(
            "SELECT pg_catalog.pg_has_role(current_user,'gs_role_pldebugger','member')");
             ResultSet result = statement.executeQuery()) {
            if (result.next() && result.getBoolean(1)) {
                return;
            }
        } catch (SQLException e) {
            if (!"42704".equals(e.getSQLState())) {
                throw e;
            }
            // Older servers without the built-in role require administrator access.
        }
        throw new DBGException(
            "The current user must be a GaussDB system administrator or a member of gs_role_pldebugger"
        );
    }

    private static void checkRoutineExecutePrivilege(JDBCSession session, long routineOid)
        throws SQLException, DBGException {
        try (PreparedStatement statement = session.prepareStatement(
            "SELECT pg_catalog.has_function_privilege(?::oid,'EXECUTE')")) {
            statement.setLong(1, routineOid);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new DBGException("The current user does not have EXECUTE privilege on the routine being debugged");
                }
            }
        }
    }

    static boolean hasRequiredSignature(String functionName, String argumentOids) {
        Set<String> signatures = REQUIRED_SIGNATURES.get(
            functionName == null ? null : functionName.toLowerCase(Locale.ENGLISH)
        );
        return signatures != null && signatures.contains(normalizeOidVector(argumentOids));
    }

    static void assertComplete(Set<String> matching, Set<String> executable) throws DBGException {
        Set<String> missing = new TreeSet<>(REQUIRED_SIGNATURES.keySet());
        missing.removeAll(matching);
        Set<String> denied = new TreeSet<>(matching);
        denied.removeAll(executable);
        if (!missing.isEmpty() || !denied.isEmpty()) {
            StringBuilder message = new StringBuilder("GaussDB PL/SQL debugger is unavailable");
            if (!missing.isEmpty()) {
                message.append("; missing or incompatible DBE_PLDEBUGGER signatures: ").append(missing);
            }
            if (!denied.isEmpty()) {
                message.append("; current user has no EXECUTE privilege on: ").append(denied);
            }
            throw new DBGException(message.toString());
        }
    }

    static Set<String> requiredFunctionNames() {
        return REQUIRED_SIGNATURES.keySet();
    }

    private static String placeholders() {
        return String.join(",", Collections.nCopies(REQUIRED_SIGNATURES.size(), "?"));
    }

    private static String normalizeOidVector(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
