/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.debug;

import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;

/** Completion callbacks must only run after the database operation returns successfully. */
public final class DBGTransactionCompletion {
    @FunctionalInterface
    public interface SuccessAction {
        void run() throws Exception;
    }

    private DBGTransactionCompletion() {
    }

    public static void complete(@NotNull DBRProgressMonitor monitor, @NotNull DBGSession session, @NotNull DBGTransactionAction action,
                                @NotNull SuccessAction onSuccess) throws Exception {
        if (monitor.isCanceled()) {
            throw new DBGException("Transaction completion canceled before execution");
        }
        session.completeTransaction(monitor, action);
        onSuccess.run();
    }
}
