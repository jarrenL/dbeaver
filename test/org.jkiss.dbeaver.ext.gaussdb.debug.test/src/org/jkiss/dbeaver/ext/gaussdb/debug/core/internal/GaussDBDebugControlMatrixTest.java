/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.dbeaver.debug.DBGEvent;
import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GaussDBDebugControlMatrixTest {
    private GaussDBDebugController controller;
    private JDBCExecutionContext context;
    private JDBCSession connection;
    private JDBCStatement statement;
    private GaussDBDebugSession session;

    @BeforeEach
    void setUp() throws Exception {
        controller = mock(GaussDBDebugController.class);
        context = mock(JDBCExecutionContext.class);
        connection = mock(JDBCSession.class);
        statement = mock(JDBCStatement.class);
        when(context.openSession(any(), any(), anyString())).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        session = new GaussDBDebugSession(controller, context, context, mock(GaussDBProcedure.class));
    }

    static Stream<Arguments> controls() {
        return Stream.of(
            Arguments.of("continue", "continue", DBGEvent.CLIENT_REQUEST),
            Arguments.of("stepInto", "step", DBGEvent.STEP_INTO),
            Arguments.of("stepOver", "next", DBGEvent.STEP_OVER),
            Arguments.of("stepReturn", "finish", DBGEvent.STEP_RETURN)
        );
    }

    @ParameterizedTest(name = "{0} calls DBE_PLDEBUGGER.{1} and publishes detail {2}")
    @MethodSource("controls")
    void everyControlUsesTheExpectedProtocolFunctionAndEvents(String action, String function, int detail) throws Exception {
        setState("attached", true);
        JDBCResultSet result = mock(JDBCResultSet.class);
        when(result.next()).thenReturn(true);
        when(result.getString("query")).thenReturn("source line");
        when(statement.executeQuery("SELECT * FROM DBE_PLDEBUGGER." + function + "()"))
            .thenReturn(result);

        List<DBGEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch suspended = new CountDownLatch(1);
        doAnswer(call -> {
            DBGEvent event = call.getArgument(0);
            events.add(event);
            if (event.getKind() == DBGEvent.SUSPEND) {
                suspended.countDown();
            }
            return null;
        }).when(controller).fireEvent(any());

        invoke(action);

        assertTrue(suspended.await(5, TimeUnit.SECONDS), "control command did not suspend again");
        assertEquals(2, events.size());
        assertEquals(DBGEvent.RESUME, events.get(0).getKind());
        assertEquals(detail, events.get(0).getDetails());
        assertEquals(DBGEvent.SUSPEND, events.get(1).getKind());
        assertEquals(detail, events.get(1).getDetails());
        verify(statement).executeQuery("SELECT * FROM DBE_PLDEBUGGER." + function + "()");
    }

    @Test
    void failedControlReturnsToSuspendedStateAndCanBeRetried() throws Exception {
        setState("attached", true);
        String sql = "SELECT * FROM DBE_PLDEBUGGER.next()";
        JDBCResultSet result = mock(JDBCResultSet.class);
        when(result.next()).thenReturn(true);
        when(result.getString("query")).thenReturn("recovered line");
        when(statement.executeQuery(sql))
            .thenThrow(new SQLException("temporary network failure", "08006"))
            .thenReturn(result);

        List<Integer> suspendDetails = new CopyOnWriteArrayList<>();
        CountDownLatch firstSuspend = new CountDownLatch(1);
        CountDownLatch secondSuspend = new CountDownLatch(2);
        doAnswer(call -> {
            DBGEvent event = call.getArgument(0);
            if (event.getKind() == DBGEvent.SUSPEND) {
                suspendDetails.add(event.getDetails());
                firstSuspend.countDown();
                secondSuspend.countDown();
            }
            return null;
        }).when(controller).fireEvent(any());

        session.execStepOver();
        assertTrue(firstSuspend.await(5, TimeUnit.SECONDS));
        assertFalse(session.isWaiting());

        session.execStepOver();
        assertTrue(secondSuspend.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(DBGEvent.CLIENT_REQUEST, DBGEvent.STEP_OVER), suspendDetails);
        verify(statement, times(2)).executeQuery(sql);
    }

    @ParameterizedTest(name = "{0} rejects an unattached session")
    @MethodSource("actionNames")
    void everyControlRejectsAnUnattachedSession(String action) {
        DBGException error = assertThrows(DBGException.class, () -> invoke(action));
        assertTrue(error.getMessage().contains("not suspended"));
        verifyNoInteractions(statement);
    }

    @ParameterizedTest(name = "{0} rejects a completed session")
    @MethodSource("actionNames")
    void everyControlRejectsACompletedSession(String action) throws Exception {
        setState("attached", true);
        setState("done", true);
        DBGException error = assertThrows(DBGException.class, () -> invoke(action));
        assertTrue(error.getMessage().contains("not suspended"));
        verifyNoInteractions(statement);
    }

    static Stream<String> actionNames() {
        return controls().map(arguments -> (String) arguments.get()[0]);
    }

    private void invoke(String action) throws DBGException {
        switch (action) {
            case "continue" -> session.execContinue();
            case "stepInto" -> session.execStepInto();
            case "stepOver" -> session.execStepOver();
            case "stepReturn" -> session.execStepReturn();
            default -> fail("Unknown debug action: " + action);
        }
    }

    private void setState(String name, boolean value) throws ReflectiveOperationException {
        Field field = GaussDBDebugSession.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(session, value);
    }
}
