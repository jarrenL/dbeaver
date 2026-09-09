/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.eclipse.debug.core.model.IWatchExpressionListener;
import org.jkiss.dbeaver.debug.DBGSession;
import org.jkiss.dbeaver.debug.DBGStackFrame;
import org.jkiss.dbeaver.debug.core.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class DatabaseWatchLifecycleTest {
    @Test
    void watchRefreshAfterSessionReleaseReturnsErrorInsteadOfNullPointer() throws Exception {
        IDatabaseDebugTarget target = mock(IDatabaseDebugTarget.class);
        DatabaseThread thread = mock(DatabaseThread.class);
        when(thread.getDatabaseDebugTarget()).thenReturn(target);
        DatabaseStackFrame frame = new DatabaseStackFrame(thread, mock(DBGStackFrame.class));
        assertEquals(0, frame.getVariables().length);
        IWatchExpressionListener listener = mock(IWatchExpressionListener.class);
        new DatabaseWatchExpressionDelegate().evaluateExpression("v_local", frame, listener);
        verify(listener).watchEvaluationFinished(argThat(result -> result.hasErrors() && result.getValue() == null));
    }

    @Test
    void terminatedTargetDoesNotReadItsOldSession() throws Exception {
        IDatabaseDebugTarget target = mock(IDatabaseDebugTarget.class);
        DBGSession session = mock(DBGSession.class);
        when(target.getSession()).thenReturn(session);
        when(target.isTerminated()).thenReturn(true);
        DatabaseThread thread = mock(DatabaseThread.class);
        when(thread.getDatabaseDebugTarget()).thenReturn(target);
        DatabaseStackFrame frame = new DatabaseStackFrame(thread, mock(DBGStackFrame.class));
        assertEquals(0, frame.getVariables().length);
        verifyNoInteractions(session);
    }
}
