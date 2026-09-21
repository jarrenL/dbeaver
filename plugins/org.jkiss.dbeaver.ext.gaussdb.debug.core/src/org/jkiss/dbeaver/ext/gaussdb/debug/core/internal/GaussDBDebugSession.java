/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.debug.*;
import org.jkiss.dbeaver.debug.core.DebugUtils;
import org.jkiss.dbeaver.debug.jdbc.DBGJDBCSession;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugConstants;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugArguments;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugCore;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreProcedureParameter;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureType;
import org.jkiss.utils.CommonUtils;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** GaussDB DBE_PLDEBUGGER protocol session. The target invocation and debugger controller use separate connections. */
public class GaussDBDebugSession extends DBGJDBCSession {
    private static final Log log = Log.getLog(GaussDBDebugSession.class);
    private static final String API = "DBE_PLDEBUGGER."; //$NON-NLS-1$
    private static final String FINISHED = "[EXECUTION FINISHED]"; //$NON-NLS-1$
    private static final int TRANSACTION_NETWORK_TIMEOUT_MS = 10000;

    private final Map<String, Object> configuration;
    private final JDBCExecutionContext controllerConnection;
    private final JDBCExecutionContext targetConnection;
    private final GaussDBProcedure routine;
    private final List<GaussDBDebugBreakpointDescriptor> breakpoints = new CopyOnWriteArrayList<>();
    private final ReentrantLock controllerLock = new ReentrantLock();
    private final Object transactionLock = new Object();
    private volatile CountDownLatch targetFinished = new CountDownLatch(0);
    private volatile boolean targetSucceeded;
    private volatile boolean controlFinished;
    private boolean completionPublished;
    private boolean transactionOutcomeUnknown;

    private volatile Job targetJob;
    private volatile Job commandJob;
    private volatile boolean attached;
    private volatile boolean done;
    private volatile boolean closing;
    private volatile boolean transactionCompletionPending;
    private GaussDBDebugSessionInfo sessionInfo;
    private String breakpointArgumentType = "oid";

    GaussDBDebugSession(DBRProgressMonitor monitor, GaussDBDebugController controller, Map<String, Object> configuration)
        throws DBGException {
        super(controller);
        this.configuration = configuration;
        try {
            routine = GaussDBDebugCore.resolveRoutine(monitor, controller.getDataSourceContainer(), configuration);
            controllerConnection = (JDBCExecutionContext) routine.getDatabase().openIsolatedContext(
                monitor, "GaussDB debugger controller", null);
            targetConnection = (JDBCExecutionContext) routine.getDatabase().openIsolatedContext(
                monitor, "GaussDB debugger target", null);
            controllerConnection.setAutoCommit(monitor, true);
            targetConnection.setAutoCommit(monitor, false);
        } catch (DBException e) {
            throw new DBGException("Unable to create GaussDB debug connections", e);
        }
    }

    @org.jkiss.dbeaver.model.meta.ForTest
    GaussDBDebugSession(
        GaussDBDebugController controller,
        JDBCExecutionContext controllerConnection,
        JDBCExecutionContext targetConnection,
        GaussDBProcedure routine
    ) {
        super(controller);
        this.configuration = Map.of();
        this.controllerConnection = controllerConnection;
        this.targetConnection = targetConnection;
        this.routine = routine;
    }

    public void attach(DBRProgressMonitor monitor) throws DBGException {
        try {
            String eligibilityError = GaussDBDebugCore.getRoutineEligibilityError(monitor, routine);
            if (eligibilityError != null) {
                throw new DBGException(eligibilityError);
            }
        } catch (DBException e) {
            throw new DBGException("Unable to validate the GaussDB routine for debugging", e);
        }
        checkCapabilities(monitor);
        long processId = queryLong(targetConnection, monitor, "SELECT pg_backend_pid()", "Read target process");
        String node;
        int port;
        try (JDBCSession session = targetConnection.openSession(monitor, DBCExecutionPurpose.UTIL, "Enable PL/SQL debugger");
             PreparedStatement statement = session.prepareStatement("SELECT * FROM " + API + "turn_on(?::oid)")) {
            statement.setLong(1, routine.getObjectId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DBGException("DBE_PLDEBUGGER.turn_on did not return a debug endpoint");
                }
                node = result.getString(1);
                port = result.getInt(2);
            }
        } catch (SQLException e) {
            throw sqlError("Unable to enable GaussDB PL/SQL debugger", e);
        }

        sessionInfo = new GaussDBDebugSessionInfo(processId, node, port);
        runTarget(monitor);
        try (JDBCSession session = controllerConnection.openSession(monitor, DBCExecutionPurpose.UTIL, "Attach PL/SQL debugger");
             PreparedStatement statement = session.prepareStatement("SELECT * FROM " + API + "attach(?, ?)") ) {
            statement.setString(1, node);
            statement.setInt(2, port);
            statement.setQueryTimeout(10);
            attachWithRetry(statement, monitor, () -> done);
        } catch (SQLException e) {
            throw sqlError("Unable to attach to GaussDB PL/SQL debugger", e);
        }
        attached = true;
        // Do not let event listeners query the controller before attach's resources close.
        fireEvent(new DBGEvent(this, DBGEvent.SUSPEND, DBGEvent.BREAKPOINT));
    }

    static void attachWithRetry(
        PreparedStatement statement,
        DBRProgressMonitor monitor,
        java.util.function.BooleanSupplier targetDone
    ) throws SQLException, DBGException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (true) {
            if (monitor.isCanceled() || targetDone.getAsBoolean()) {
                throw new DBGException("GaussDB debug target stopped before attachment");
            }
            try {
                statement.execute();
                return;
            } catch (SQLException e) {
                // GaussDB 507 reports D0011 while CALL has not yet entered its debug wait.
                // Other failures (permissions, connection loss, incompatible API) are not retried.
                if (!"D0011".equals(e.getSQLState()) || System.nanoTime() >= deadline) {
                    throw e;
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DBGException("Interrupted while attaching the GaussDB debugger", e);
            }
        }
    }

    private void checkCapabilities(DBRProgressMonitor monitor) throws DBGException {
        breakpointArgumentType = GaussDBDebugCapabilityDetector.check(controllerConnection, monitor, routine.getObjectId());
    }

    private void runTarget(DBRProgressMonitor monitor) throws DBGException {
        List<PostgreProcedureParameter> parameters = routine.getInputParameters();
        Object rawModes = configuration.get(GaussDBDebugConstants.ATTR_ROUTINE_PARAMETER_MODES);
        List<String> modes = rawModes instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        GaussDBDebugArguments.Plan arguments = GaussDBDebugArguments.build(parameters, parameterValues(), modes);
        List<String> values = arguments.values();
        if (values.size() < parameters.size()) {
            validateDefaultInvocation(monitor);
        }
        String sql = (routine.getProcedureType() == DBSProcedureType.PROCEDURE ? "CALL " : "SELECT ") +
            routine.getFullyQualifiedName(DBPEvaluationContext.DML) + '(' + arguments.sql() + ')';

        targetFinished = new CountDownLatch(1);
        targetSucceeded = false;
        targetJob = new AbstractJob("GaussDB PL/SQL target " + routine.getName()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try (JDBCSession session = targetConnection.openSession(monitor, DBCExecutionPurpose.USER, "Run debug target");
                     PreparedStatement statement = session.prepareStatement(sql.toString())) {
                    for (int i = 0; i < values.size(); i++) {
                        String value = values.get(i);
                        if (value == null) {
                            statement.setNull(i + 1, Types.NULL);
                        } else {
                            statement.setString(i + 1, value);
                        }
                    }
                    statement.execute();
                } catch (Exception e) {
                    targetFinished.countDown();
                    return handleTargetFailure(e);
                }
                targetSucceeded = true;
                done = true;
                targetFinished.countDown();
                publishCompletionIfReady();
                return Status.OK_STATUS;
            }
        };
        targetJob.schedule();
    }

    void validateDefaultInvocation(DBRProgressMonitor monitor) throws DBGException {
        if (monitor.isCanceled()) {
            throw new DBGException("Default argument validation canceled");
        }
        // Omitted arguments may select a different overload than the OID armed by turn_on.
        // Reject ambiguous names conservatively instead of running an unintended routine.
        String sql = "SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_proc target " +
            "ON p.proname=target.proname AND p.pronamespace=target.pronamespace " +
            "WHERE target.oid=? AND p.oid<>target.oid LIMIT 1";
        try (JDBCSession session = targetConnection.openSession(monitor, DBCExecutionPurpose.UTIL, "Validate default arguments");
             PreparedStatement statement = session.prepareStatement(sql)) {
            statement.setQueryTimeout(10);
            statement.setLong(1, routine.getObjectId());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new DBGException("Default arguments are not enabled for overloaded routine names; enter all arguments explicitly");
                }
            }
            if (monitor.isCanceled()) {
                throw new DBGException("Default argument validation canceled");
            }
        } catch (SQLException e) {
            throw sqlError("Unable to validate default arguments", e);
        }
    }

    IStatus handleTargetFailure(Exception error) {
        done = true;
        if (closing || Thread.currentThread().isInterrupted()) {
            // DBE_PLDEBUGGER.abort deliberately makes the target CALL fail.
            // The user-requested termination has already notified the debug model.
            return Status.CANCEL_STATUS;
        }
        log.error("GaussDB debug target failed", error);
        fireEvent(new DBGEvent(this, DBGEvent.TERMINATE, DBGEvent.CLIENT_REQUEST));
        return DebugUtils.newErrorStatus("GaussDB debug target failed", error);
    }

    private List<String> parameterValues() {
        Object raw = configuration.get(GaussDBDebugConstants.ATTR_ROUTINE_PARAMETERS);
        if (!(raw instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream().map(value -> value == null ? null : String.valueOf(value)).toList();
    }

    @Override
    public void execContinue() throws DBGException {
        executeControl("continue", "Continue", DBGEvent.CLIENT_REQUEST);
    }

    @Override
    public void execStepInto() throws DBGException {
        executeControl("step", "Step into", DBGEvent.STEP_INTO);
    }

    @Override
    public void execStepOver() throws DBGException {
        executeControl("next", "Step over", DBGEvent.STEP_OVER);
    }

    @Override
    public void execStepReturn() throws DBGException {
        executeControl("finish", "Step return", DBGEvent.STEP_RETURN);
    }

    private void acquireController() throws DBGException {
        if (!controllerLock.tryLock()) {
            throw new DBGException("The debugger is busy; wait for the current operation to finish");
        }
        if (closing || commandJob != null) {
            controllerLock.unlock();
            throw new DBGException("The debugger is running or closing; wait for a suspended frame");
        }
    }

    private void acquireController(DBRProgressMonitor monitor) throws DBGException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (!controllerLock.tryLock(50, TimeUnit.MILLISECONDS)) {
                if (closing || commandJob != null || monitor.isCanceled() || System.nanoTime() >= deadline) {
                    throw new DBGException("The debugger is busy; breakpoint update was not executed");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DBGException("Interrupted while updating a breakpoint", e);
        }
        if (closing || commandJob != null || monitor.isCanceled()) {
            controllerLock.unlock();
            throw new DBGException("The debugger is running or closing; breakpoint update was not executed");
        }
    }

    private void executeControl(String function, String name, int detail) throws DBGException {
        acquireController();
        try {
            if (!attached || done) {
                throw new DBGException("GaussDB debug session is not suspended");
            }
            commandJob = new AbstractJob("GaussDB debugger: " + name) {
                @NotNull
                @Override
                protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                    controllerLock.lock();
                    try {
                        if (closing || monitor.isCanceled()) {
                            return Status.CANCEL_STATUS;
                        }
                        String response = executeControlCommand(controllerConnection, monitor, function, name);
                        if (closing) {
                            return Status.CANCEL_STATUS;
                        }
                        // Event listeners synchronously read the new paused frame. Publish
                        // idle BEFORE notifying them, while retaining the reentrant lock.
                        commandJob = null;
                        if (terminateOnExecutionError(response)) {
                            return DebugUtils.newErrorStatus("GaussDB routine execution failed; the debug session was terminated");
                        }
                        if (isExecutionFinished(response)) {
                            controlFinished = true;
                            done = true;
                            // The target JDBC response can arrive later. Whichever side
                            // finishes last publishes completion, without a timing window.
                            publishCompletionIfReady();
                        } else {
                            fireEvent(new DBGEvent(GaussDBDebugSession.this, DBGEvent.SUSPEND, detail));
                        }
                        return Status.OK_STATUS;
                    } catch (DBGException e) {
                        commandJob = null;
                        if (closing) {
                            return Status.CANCEL_STATUS;
                        }
                        fireEvent(new DBGEvent(GaussDBDebugSession.this, DBGEvent.SUSPEND, DBGEvent.CLIENT_REQUEST));
                        return DebugUtils.newErrorStatus("GaussDB debugger command failed", e);
                    } finally {
                        if (commandJob == this) {
                            commandJob = null;
                        }
                        controllerLock.unlock();
                    }
                }
            };
            fireEvent(new DBGEvent(this, DBGEvent.RESUME, detail));
            commandJob.schedule();
        } finally {
            controllerLock.unlock();
        }
    }

    void publishCompletionIfReady() {
        controllerLock.lock();
        try {
            if (!closing && controlFinished && targetSucceeded && !completionPublished) {
                completionPublished = true;
                transactionCompletionPending = true;
                fireEvent(new DBGEvent(this, DBGEvent.SUSPEND, DBGEvent.STEP_END));
            }
        } finally {
            controllerLock.unlock();
        }
    }

    private void awaitTarget(DBRProgressMonitor monitor) throws DBGException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (!targetFinished.await(50, TimeUnit.MILLISECONDS)) {
                if (monitor.isCanceled() || System.nanoTime() >= deadline) {
                    throw new DBGException("Target execution has not finished; transaction completion was not attempted");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DBGException("Interrupted while waiting for target execution", e);
        }
    }

    boolean terminateOnExecutionError(String response) {
        if (response == null || !response.contains("[EXECUTION HAS ERROR OCCURRED!]")) {
            return false;
        }
        // GaussDB 507 rejects even abort() in its runtime-error wait. Termination must
        // close the target connection, releasing the wait and rolling back its transaction.
        done = true;
        transactionCompletionPending = false;
        fireEvent(new DBGEvent(this, DBGEvent.TERMINATE, DBGEvent.CLIENT_REQUEST));
        return true;
    }

    @Override
    public List<DBGStackFrame> getStack() throws DBGException {
        acquireController();
        try {
            List<DBGStackFrame> frames = new ArrayList<>();
            try (JDBCSession session = controllerConnection.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Read call stack");
                 Statement statement = session.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM " + API + "backtrace()")) {
                while (result.next()) {
                    frames.add(new GaussDBDebugStackFrame(
                        result.getInt("frameno"), result.getString("funcname"), result.getInt("lineno"),
                        result.getLong("funcoid"), result.getString("query")));
                }
            } catch (SQLException e) {
                throw sqlError("Unable to read GaussDB call stack", e);
            }
            return frames;
        } finally {
            controllerLock.unlock();
        }
    }

    @Override
    public List<DBGVariable<?>> getVariables(DBGStackFrame stack) throws DBGException {
        acquireController();
        try {
            int frame = stack == null ? 0 : stack.getLevel();
            List<DBGVariable<?>> variables = new ArrayList<>();
            try (JDBCSession session = controllerConnection.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Read local variables");
                 Statement statement = session.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM " + API + "info_locals(" + frame + ")")) {
                while (result.next()) {
                    variables.add(new GaussDBDebugVariable(
                        result.getString("varname"), result.getString("vartype"), result.getString("value"),
                        result.getString("package_name"), result.getBoolean("isconst"), frame));
                }
            } catch (SQLException e) {
                throw sqlError("Unable to read GaussDB local variables", e);
            }
            return variables;
        } finally {
            controllerLock.unlock();
        }
    }

    @Override
    public void setVariableVal(DBGVariable<?> variable, Object value) throws DBGException {
        acquireController();
        try {
            if (!(variable instanceof GaussDBDebugVariable gaussVariable) || gaussVariable.isReadOnly()) {
                throw new DBGException("This variable cannot be modified");
            }
            try (JDBCSession session = controllerConnection.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Set local variable");
                 PreparedStatement statement = session.prepareStatement("SELECT " + API + "set_var(?, ?)") ) {
                statement.setString(1, gaussVariable.getName());
                statement.setString(2, value == null ? null : String.valueOf(value));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        throw new DBGException("GaussDB rejected the value for variable " + gaussVariable.getName());
                    }
                }
                // Read back the server representation: set_var accepts PL/SQL expressions.
                for (DBGVariable<?> refreshed : getVariables(new GaussDBDebugStackFrame(
                    gaussVariable.getLineNumber(), "", 0, routine.getObjectId(), ""))) {
                    if (refreshed instanceof GaussDBDebugVariable candidate &&
                        candidate.getName().equals(gaussVariable.getName()) &&
                        java.util.Objects.equals(candidate.getPackageName(), gaussVariable.getPackageName())) {
                        gaussVariable.setValue(candidate.getVal());
                        return;
                    }
                }
                throw new DBGException("Unable to reload variable " + gaussVariable.getName());
            } catch (SQLException e) {
                throw sqlError("Unable to modify GaussDB local variable", e);
            }
        } finally {
            controllerLock.unlock();
        }
    }

    @Override
    public String getSource(DBGStackFrame stack) throws DBGException {
        acquireController();
        try {
            long oid = stack == null ? routine.getObjectId() : CommonUtils.toLong(stack.getSourceIdentifier());
            StringBuilder source = new StringBuilder();
            try (JDBCSession session = controllerConnection.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Read debug source");
                 PreparedStatement statement = session.prepareStatement("SELECT * FROM " + API + "info_code(?::oid)")) {
                statement.setLong(1, oid);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        source.append(result.getString("query")).append('\n');
                    }
                }
            } catch (SQLException e) {
                throw sqlError("Unable to read GaussDB routine source", e);
            }
            return source.toString();
        } finally {
            controllerLock.unlock();
        }
    }

    @Override
    public void addBreakpoint(DBRProgressMonitor monitor, DBGBreakpointDescriptor descriptor) throws DBGException {
        acquireController(monitor);
        try {
            GaussDBDebugBreakpointDescriptor breakpoint = requireBreakpoint(descriptor);
            validateBreakpointLine(monitor, breakpoint);
            GaussDBDebugBreakpointDescriptor existing = findMatchingBreakpoint(breakpoints, breakpoint);
            if (existing != null) {
                executeBreakpointCommand(monitor, "delete_breakpoint", existing);
                breakpoints.remove(existing);
            }
            try (JDBCSession session = controllerConnection.openSession(monitor, DBCExecutionPurpose.UTIL, "Add breakpoint");
                 PreparedStatement statement = session.prepareStatement("SELECT " + API + "add_breakpoint(?::" + breakpointArgumentType + ", ?::integer)")) {
                statement.setString(1, String.valueOf(breakpoint.getRoutineOid()));
                statement.setInt(2, Math.toIntExact(breakpoint.getLineNumber()));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new DBGException("DBE_PLDEBUGGER.add_breakpoint did not return a breakpoint number");
                    }
                    int serverId = result.getInt(1);
                    if (result.wasNull()) {
                        throw new DBGException("DBE_PLDEBUGGER.add_breakpoint returned a null breakpoint number");
                    }
                    if (serverId < 0) {
                        // The server returns -1 with a warning when the line already has a
                        // breakpoint (verified on GaussDB 507). The duplicate is deleted above,
                        // so a negative number here means the session state is out of sync.
                        throw new DBGException(
                            "DBE_PLDEBUGGER.add_breakpoint returned an invalid breakpoint number " + serverId +
                                " (line " + breakpoint.getLineNumber() + " already has a breakpoint)");
                    }
                    breakpoint.setServerId(serverId);
                }
                breakpoints.add(breakpoint);
                if (!breakpoint.isEnabled()) {
                    executeBreakpointCommand(monitor, "disable_breakpoint", breakpoint);
                }
            } catch (SQLException e) {
                throw sqlError("Unable to add GaussDB breakpoint", e);
            }
        } finally {
            controllerLock.unlock();
        }
    }

    private void validateBreakpointLine(DBRProgressMonitor monitor, GaussDBDebugBreakpointDescriptor breakpoint)
        throws DBGException {
        String sql = "SELECT canbreak FROM " + API + "info_code(?::oid) WHERE lineno=?";
        try (JDBCSession session = controllerConnection.openSession(monitor, DBCExecutionPurpose.UTIL, "Validate breakpoint line");
             PreparedStatement statement = session.prepareStatement(sql)) {
            statement.setLong(1, breakpoint.getRoutineOid());
            statement.setInt(2, Math.toIntExact(breakpoint.getLineNumber()));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new DBGException("Line " + breakpoint.getLineNumber() + " is not an executable breakpoint line");
                }
            }
        } catch (SQLException e) {
            throw sqlError("Unable to validate GaussDB breakpoint line", e);
        }
    }

    @Override
    public void removeBreakpoint(DBRProgressMonitor monitor, DBGBreakpointDescriptor descriptor) throws DBGException {
        acquireController(monitor);
        try {
            GaussDBDebugBreakpointDescriptor breakpoint = findMatchingBreakpoint(breakpoints, requireBreakpoint(descriptor));
            if (breakpoint == null) {
                return;
            }
            executeBreakpointCommand(monitor, "delete_breakpoint", breakpoint);
            breakpoints.remove(breakpoint);
        } finally {
            controllerLock.unlock();
        }
    }

    @Override
    public void enableBreakpoint(DBRProgressMonitor monitor, DBGBreakpointDescriptor descriptor) throws DBGException {
        acquireController(monitor);
        try {
            GaussDBDebugBreakpointDescriptor requested = requireBreakpoint(descriptor);
            GaussDBDebugBreakpointDescriptor breakpoint = findMatchingBreakpoint(breakpoints, requested);
            if (breakpoint == null) {
                requested.setEnabled(true);
                addBreakpoint(monitor, requested);
                return;
            }
            executeBreakpointCommand(monitor, "enable_breakpoint", breakpoint);
            breakpoint.setEnabled(true);
        } finally {
            controllerLock.unlock();
        }
    }

    @Override
    public void disableBreakpoint(DBRProgressMonitor monitor, DBGBreakpointDescriptor descriptor) throws DBGException {
        acquireController(monitor);
        try {
            GaussDBDebugBreakpointDescriptor breakpoint = findMatchingBreakpoint(breakpoints, requireBreakpoint(descriptor));
            if (breakpoint == null) {
                return; // Disabled initial breakpoints have not been registered yet.
            }
            executeBreakpointCommand(monitor, "disable_breakpoint", breakpoint);
            breakpoint.setEnabled(false);
        } finally {
            controllerLock.unlock();
        }
    }

    private void executeBreakpointCommand(DBRProgressMonitor monitor, String command, GaussDBDebugBreakpointDescriptor breakpoint)
        throws DBGException {
        if (breakpoint.getServerId() < 0) {
            throw new DBGException("Breakpoint has no server identifier");
        }
        try (JDBCSession session = controllerConnection.openSession(monitor, DBCExecutionPurpose.UTIL, "Update breakpoint");
             PreparedStatement statement = session.prepareStatement("SELECT " + API + command + "(?)")) {
            statement.setInt(1, breakpoint.getServerId());
            statement.execute();
        } catch (SQLException e) {
            throw sqlError("Unable to update GaussDB breakpoint", e);
        }
    }

    private static GaussDBDebugBreakpointDescriptor requireBreakpoint(DBGBreakpointDescriptor descriptor) throws DBGException {
        if (descriptor instanceof GaussDBDebugBreakpointDescriptor breakpoint) {
            return breakpoint;
        }
        throw new DBGException("Unsupported GaussDB breakpoint descriptor");
    }

    static GaussDBDebugBreakpointDescriptor findMatchingBreakpoint(
        Collection<GaussDBDebugBreakpointDescriptor> registered,
        GaussDBDebugBreakpointDescriptor requested
    ) {
        return registered.stream()
            .filter(existing -> existing.getRoutineOid() == requested.getRoutineOid() &&
                existing.getLineNumber() == requested.getLineNumber())
            .findFirst()
            .orElse(null);
    }

    @Override
    public List<? extends DBGBreakpointDescriptor> getBreakpoints() {
        return List.copyOf(breakpoints);
    }

    @Override
    public void completeTransaction(DBRProgressMonitor monitor, DBGTransactionAction action) throws DBGException {
        synchronized (transactionLock) {
            if (!transactionCompletionPending) {
                return;
            }
            if (transactionOutcomeUnknown && action == DBGTransactionAction.COMMIT) {
                throw new DBGException("The previous transaction outcome is unknown; COMMIT must not be retried automatically");
            }
            awaitTarget(monitor);
            if (!targetSucceeded) {
                throw new DBGException("The target invocation did not complete successfully");
            }
            if (monitor.isCanceled()) {
                throw new DBGException("Transaction completion canceled before execution");
            }
            try (JDBCSession session = targetConnection.openSession(monitor, DBCExecutionPurpose.UTIL, "Complete debug transaction")) {
                // Statement timeout does not bound Connection.commit(), and normal debugger
                // reads intentionally have no deadline. Bound only transaction completion.
                int previousTimeout = session.getNetworkTimeout();
                session.setNetworkTimeout(Runnable::run, previousTimeout > 0
                    ? Math.min(previousTimeout, TRANSACTION_NETWORK_TIMEOUT_MS) : TRANSACTION_NETWORK_TIMEOUT_MS);
                try {
                    if (action == DBGTransactionAction.COMMIT) {
                        session.commit();
                    } else {
                        session.rollback();
                    }
                    transactionCompletionPending = false;
                } finally {
                    try {
                        session.setNetworkTimeout(Runnable::run, previousTimeout);
                    } catch (SQLException restoreError) {
                        // A timeout normally closes the socket. Do not mask the outcome
                        // or mislabel a confirmed commit because restoration failed.
                        log.debug("Cannot restore debug connection network timeout", restoreError);
                    }
                }
            } catch (SQLException e) {
                transactionOutcomeUnknown = true;
                throw sqlError("Transaction outcome was not confirmed; verify it using another connection", e);
            }
        }
    }

    @Override
    public boolean isTransactionCompletionPending() {
        return transactionCompletionPending;
    }

    @Override
    protected void doDetach(DBRProgressMonitor monitor) throws DBGException {
        closing = true;
        if (!done) {
            try (JDBCSession session = controllerConnection.openSession(monitor, DBCExecutionPurpose.UTIL, "Abort debug target");
                 Statement statement = session.createStatement()) {
                session.setNetworkTimeout(Runnable::run, TRANSACTION_NETWORK_TIMEOUT_MS);
                statement.setQueryTimeout(5);
                statement.execute("SELECT " + API + "abort()");
            } catch (SQLException e) {
                log.debug("Unable to abort GaussDB debug target", e);
            }
        }
        attached = false;
    }

    @Override
    public void closeSession(DBRProgressMonitor monitor) throws DBGException {
        closing = true;
        Job runningCommand = commandJob;
        if (runningCommand != null) {
            runningCommand.cancel();
        }
        if (targetJob != null && !done) {
            targetJob.cancel();
        }
        // Cancellation is asynchronous. Only issue abort on an idle control connection;
        // otherwise close it to unblock the pending command instead of queueing more SQL.
        boolean idleController = controllerLock.tryLock();
        try {
            if (idleController && attached) {
                doDetach(monitor);
            }
            synchronized (transactionLock) {
                try {
                    if (!transactionOutcomeUnknown && transactionCompletionPending && targetFinished.getCount() == 0) {
                        completeTransaction(monitor, DBGTransactionAction.ROLLBACK);
                    }
                    if (!transactionOutcomeUnknown && targetFinished.getCount() == 0) {
                        try {
                            turnOff(monitor);
                        } catch (DBGException e) {
                            log.debug("Unable to turn off GaussDB debugger", e);
                        }
                    }
                } finally {
                    // Never close the target connection concurrently with COMMIT/ROLLBACK.
                    targetConnection.close();
                }
            }
        } finally {
            attached = false;
            controllerConnection.close();
            if (idleController) {
                controllerLock.unlock();
            }
        }
    }

    @Override
    protected String composeAbortCommand() {
        return "SELECT " + API + "abort()";
    }

    @Override
    protected String composeAddBreakpointCommand(DBGBreakpointDescriptor descriptor) {
        return "";
    }

    @Override
    protected String composeRemoveBreakpointCommand(DBGBreakpointDescriptor descriptor) {
        return "";
    }

    @Override
    public void resume() throws DBGException {
        execContinue();
    }

    @Override
    public void suspend() throws DBGException {
        throw new DBGException("DBE_PLDEBUGGER does not support asynchronous pause");
    }

    @Override
    public boolean canStepInto() {
        return attached && !done && !closing && commandJob == null;
    }

    @Override
    public boolean canStepOver() {
        return canStepInto();
    }

    @Override
    public boolean canStepReturn() {
        return canStepInto();
    }

    @Override
    public JDBCExecutionContext getControllerConnection() {
        return controllerConnection;
    }

    @Override
    public boolean isAttached() {
        return attached;
    }

    @Override
    public boolean isWaiting() {
        return commandJob != null;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public DBGSessionInfo getSessionInfo() {
        return sessionInfo;
    }

    @Override
    public Object getSessionId() {
        return sessionInfo == null ? null : sessionInfo.getID();
    }

    static long queryLong(JDBCExecutionContext context, DBRProgressMonitor monitor, String sql, String task)
        throws DBGException {
        try (JDBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, task);
             Statement statement = session.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (result.next()) {
                return result.getLong(1);
            }
            throw new DBGException(task + " returned no result");
        } catch (SQLException e) {
            throw sqlError(task, e);
        }
    }

    private static String queryString(JDBCExecutionContext context, DBRProgressMonitor monitor, String sql, String task)
        throws DBGException {
        try (JDBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, task);
             Statement statement = session.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        } catch (SQLException e) {
            throw sqlError(task, e);
        }
    }

    private static String executeControlCommand(
        JDBCExecutionContext context,
        DBRProgressMonitor monitor,
        String function,
        String task
    ) throws DBGException {
        String sql = "SELECT * FROM " + API + function + "()";
        try (JDBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, task);
             Statement statement = session.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString("query") : null;
        } catch (SQLException e) {
            throw sqlError(task, e);
        }
    }

    static boolean isExecutionFinished(String response) {
        return response != null && response.contains(FINISHED);
    }

    private void turnOff(DBRProgressMonitor monitor) throws DBGException {
        try (JDBCSession session = targetConnection.openSession(monitor, DBCExecutionPurpose.UTIL, "Disable PL/SQL debugger");
             PreparedStatement statement = session.prepareStatement("SELECT " + API + "turn_off(?::oid)")) {
            if (closing) {
                session.setNetworkTimeout(Runnable::run, TRANSACTION_NETWORK_TIMEOUT_MS);
            }
            statement.setLong(1, routine.getObjectId());
            statement.execute();
        } catch (SQLException e) {
            throw sqlError("Unable to disable GaussDB PL/SQL debugger", e);
        }
    }

    private static DBGException sqlError(String message, SQLException error) {
        return new DBGException(message + ": " + error.getMessage(), error);
    }
}
