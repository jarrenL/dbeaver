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
    private org.eclipse.debug.core.model.IWatchExpressionResult evaluate(
        String expression, org.eclipse.debug.core.model.IDebugElement context
    ) {
        var results = new java.util.ArrayList<org.eclipse.debug.core.model.IWatchExpressionResult>();
        new DatabaseWatchExpressionDelegate().evaluateExpression(expression, context, results::add);
        assertEquals(1, results.size(), "Every request must complete exactly once");
        return results.getFirst();
    }

    @Test
    void watchWithoutSelectedFrameCompletesWithActionableError() {
        var result = evaluate("x", null);
        assertTrue(result.hasErrors());
        assertNull(result.getValue());
        assertEquals("x", result.getExpressionText());
        assertTrue(result.getErrorMessages().length > 0);
    }

    @Test
    void whitespaceIsTrimmedForLookupButOriginalExpressionIsRetained() throws Exception {
        var frame = mock(DatabaseStackFrame.class);
        var variable = mock(org.eclipse.debug.core.model.IVariable.class);
        var value = mock(org.eclipse.debug.core.model.IValue.class);
        when(variable.getName()).thenReturn("中文变量");
        when(variable.getValue()).thenReturn(value);
        when(frame.getVariables()).thenReturn(new org.eclipse.debug.core.model.IVariable[]{variable});
        var result = evaluate("  中文变量  ", frame);
        assertFalse(result.hasErrors());
        assertSame(value, result.getValue());
        assertEquals("  中文变量  ", result.getExpressionText());
    }

    @Test
    void arbitraryExpressionsAreNotExecutedAsSql() throws Exception {
        var frame = mock(DatabaseStackFrame.class);
        var variable = mock(org.eclipse.debug.core.model.IVariable.class);
        when(variable.getName()).thenReturn("x");
        when(frame.getVariables()).thenReturn(new org.eclipse.debug.core.model.IVariable[]{variable});
        var result = evaluate("x + 1; DROP TABLE t", frame);
        assertTrue(result.hasErrors());
        assertNull(result.getValue());
        verify(variable, never()).getValue();
        verify(frame).getVariables();
        verifyNoMoreInteractions(frame);
    }

    @Test
    void variableNamesRemainCaseSensitive() throws Exception {
        var frame = mock(DatabaseStackFrame.class);
        var variable = mock(org.eclipse.debug.core.model.IVariable.class);
        when(variable.getName()).thenReturn("MixedCase");
        when(frame.getVariables()).thenReturn(new org.eclipse.debug.core.model.IVariable[]{variable});
        assertTrue(evaluate("mixedcase", frame).hasErrors());
        verify(variable, never()).getValue();
    }

    @Test
    void emptyExpressionAndMissingVariablesCompleteWithoutThrowing() throws Exception {
        var frame = mock(DatabaseStackFrame.class);
        when(frame.getVariables()).thenReturn(new org.eclipse.debug.core.model.IVariable[0]);
        assertTrue(evaluate(null, frame).hasErrors());
        assertTrue(evaluate(" ", frame).hasErrors());
        assertTrue(evaluate("missing", frame).hasErrors());
    }

    @Test
    void returnedErrorMessagesCannotMutateStoredResult() {
        var result = evaluate("x", null);
        String original = result.getErrorMessages()[0];
        result.getErrorMessages()[0] = "changed";
        assertEquals(original, result.getErrorMessages()[0]);
    }

    @Test
    void selectingAnotherFrameRefreshesTheWatchValue() throws Exception {
        var first = mock(DatabaseStackFrame.class);
        var second = mock(DatabaseStackFrame.class);
        var firstVariable = mock(org.eclipse.debug.core.model.IVariable.class);
        var secondVariable = mock(org.eclipse.debug.core.model.IVariable.class);
        var firstValue = mock(org.eclipse.debug.core.model.IValue.class);
        var secondValue = mock(org.eclipse.debug.core.model.IValue.class);
        when(firstVariable.getName()).thenReturn("x");
        when(secondVariable.getName()).thenReturn("x");
        when(firstVariable.getValue()).thenReturn(firstValue);
        when(secondVariable.getValue()).thenReturn(secondValue);
        when(first.getVariables()).thenReturn(new org.eclipse.debug.core.model.IVariable[]{firstVariable});
        when(second.getVariables()).thenReturn(new org.eclipse.debug.core.model.IVariable[]{secondVariable});
        assertSame(firstValue, evaluate("x", first).getValue());
        assertSame(secondValue, evaluate("x", second).getValue());
    }

    @Test
    void failedRefreshIsReportedInsteadOfReturningAStaleSuccessfulWatch() throws Exception {
        IDatabaseDebugTarget target = mock(IDatabaseDebugTarget.class);
        DBGSession session = mock(DBGSession.class);
        when(target.getSession()).thenReturn(session);
        DatabaseThread thread = mock(DatabaseThread.class);
        when(thread.getDatabaseDebugTarget()).thenReturn(target);
        DBGStackFrame serverFrame = mock(DBGStackFrame.class);
        DatabaseStackFrame frame = new DatabaseStackFrame(thread, serverFrame);
        when(session.getVariables(serverFrame)).thenThrow(new org.jkiss.dbeaver.debug.DBGException("debugger busy"));
        IWatchExpressionListener listener = mock(IWatchExpressionListener.class);
        new DatabaseWatchExpressionDelegate().evaluateExpression("v_local", frame, listener);
        verify(listener).watchEvaluationFinished(argThat(result -> result.hasErrors() && result.getException() != null));
    }

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
