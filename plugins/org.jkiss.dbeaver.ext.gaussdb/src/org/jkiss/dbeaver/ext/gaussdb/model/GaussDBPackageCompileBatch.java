/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.dbeaver.DBException;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.exec.compile.DBCCompileError;
import org.jkiss.dbeaver.model.exec.compile.DBCCompileLog;
import org.jkiss.dbeaver.model.exec.compile.DBCCompileLogBase;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.util.ArrayList;
import java.util.List;

/** Retains partial diagnostics when a batch is canceled or interrupted by a server error. */
public final class GaussDBPackageCompileBatch {
    private GaussDBPackageCompileBatch() {
    }

    public record Diagnostic(@NotNull GaussDBPackage object, @NotNull DBCCompileError error) {
    }

    public record Result(@NotNull List<Diagnostic> diagnostics, int completed, int total,
                         @Nullable GaussDBPackage stoppedAt, @Nullable DBException failure, boolean canceled) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean interrupted() {
            return canceled || failure != null;
        }
    }

    @FunctionalInterface
    interface Compiler {
        void compile(@NotNull DBRProgressMonitor monitor, @NotNull DBCCompileLog log, @NotNull GaussDBPackage object,
                     @NotNull GaussDBPackageCompileTarget target) throws DBException;
    }

    @NotNull
    public static Result compile(@NotNull DBRProgressMonitor monitor, @NotNull List<GaussDBPackage> packages,
                                 @NotNull GaussDBPackageCompileTarget target) {
        return compile(monitor, packages, target, GaussDBPackageCompiler::compile);
    }

    @NotNull
    static Result compile(@NotNull DBRProgressMonitor monitor, @NotNull List<GaussDBPackage> packages,
                          @NotNull GaussDBPackageCompileTarget target, @NotNull Compiler compiler) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        int completed = 0;
        for (GaussDBPackage object : packages) {
            if (monitor.isCanceled()) {
                return new Result(diagnostics, completed, packages.size(), object, null, true);
            }
            DBCCompileLog log = new DBCCompileLogBase();
            try {
                monitor.subTask(object.getName());
                compiler.compile(monitor, log, object, target);
            } catch (DBException e) {
                for (DBCCompileError error : log.getErrorStack()) {
                    diagnostics.add(new Diagnostic(object, error));
                }
                return new Result(diagnostics, completed, packages.size(), object, e, monitor.isCanceled());
            }
            for (DBCCompileError error : log.getErrorStack()) {
                diagnostics.add(new Diagnostic(object, error));
            }
            if (monitor.isCanceled()) {
                return new Result(diagnostics, completed, packages.size(), object, null, true);
            }
            completed++;
            monitor.worked(1);
        }
        return new Result(diagnostics, completed, packages.size(), null, null, monitor.isCanceled());
    }
}
