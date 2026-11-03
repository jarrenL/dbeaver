/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.jkiss.dbeaver.debug.core.model;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.*;

/** Evaluates variable names and exposed cursor attributes in the selected stack-frame snapshot. */
public class DatabaseWatchExpressionDelegate implements IWatchExpressionDelegate {
    @Override
    public void evaluateExpression(
        String expression,
        IDebugElement context,
        IWatchExpressionListener listener
    ) {
        String variableName = expression == null ? "" : expression.trim();
        if (!(context instanceof DatabaseStackFrame frame)) {
            listener.watchEvaluationFinished(DatabaseWatchExpressionResult.error(
                expression, "Select a suspended database stack frame to evaluate this variable"));
            return;
        }
        try {
            IVariable[] variables = frame.getVariables();
            for (IVariable variable : variables) {
                if (variableName.equals(variable.getName())) {
                    listener.watchEvaluationFinished(DatabaseWatchExpressionResult.success(expression, variable.getValue()));
                    return;
                }
            }
            int attributeStart = variableName.lastIndexOf('%');
            if (attributeStart > 0 && isCursorAttribute(variableName.substring(attributeStart))) {
                String cursorName = variableName.substring(0, attributeStart);
                String attributeName = variableName.substring(attributeStart);
                IVariable cursor = null;
                for (IVariable variable : variables) {
                    if (cursorName.equals(variable.getName())) {
                        if (cursor != null) {
                            listener.watchEvaluationFinished(DatabaseWatchExpressionResult.error(
                                expression, "Cursor '" + cursorName + "' is ambiguous in this stack frame"));
                            return;
                        }
                        cursor = variable;
                    }
                }
                if (cursor != null && cursor.getValue().hasVariables()) {
                    for (IVariable child : cursor.getValue().getVariables()) {
                        if (attributeName.equalsIgnoreCase(child.getName())) {
                            listener.watchEvaluationFinished(
                                DatabaseWatchExpressionResult.success(expression, child.getValue()));
                            return;
                        }
                    }
                }
            }
            listener.watchEvaluationFinished(DatabaseWatchExpressionResult.error(
                expression, "Variable '" + variableName + "' is not available in this stack frame"));
        } catch (DebugException e) {
            listener.watchEvaluationFinished(DatabaseWatchExpressionResult.error(expression, e));
        }
    }

    private static boolean isCursorAttribute(String name) {
        return "%ISOPEN".equalsIgnoreCase(name) || "%FOUND".equalsIgnoreCase(name)
            || "%NOTFOUND".equalsIgnoreCase(name) || "%ROWCOUNT".equalsIgnoreCase(name);
    }

    private record DatabaseWatchExpressionResult(
        String expressionText,
        IValue value,
        String[] errorMessages,
        DebugException exception
    ) implements IWatchExpressionResult {
        static DatabaseWatchExpressionResult success(String expression, IValue value) {
            return new DatabaseWatchExpressionResult(expression, value, new String[0], null);
        }

        static DatabaseWatchExpressionResult error(String expression, String message) {
            return new DatabaseWatchExpressionResult(expression, null, new String[]{message}, null);
        }

        static DatabaseWatchExpressionResult error(String expression, DebugException exception) {
            return new DatabaseWatchExpressionResult(
                expression, null, new String[]{exception.getMessage()}, exception);
        }

        @Override
        public IValue getValue() {
            return value;
        }

        @Override
        public boolean hasErrors() {
            return errorMessages.length > 0;
        }

        @Override
        public String[] getErrorMessages() {
            return errorMessages.clone();
        }

        @Override
        public String getExpressionText() {
            return expressionText;
        }

        @Override
        public DebugException getException() {
            return exception;
        }
    }
}
