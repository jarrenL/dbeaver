/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.model.sql;

import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.NextValExpression;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.jkiss.code.NotNull;
import org.jkiss.utils.CommonUtils;

/** Conservative structural gate for automatic SQL editor replay. */
public final class SQLQueryRecoveryPolicy {
    private SQLQueryRecoveryPolicy() {
    }

    public static boolean mayReplay(@NotNull SQLQuery query) {
        // Parsing can fail for vendor DML. Unknown is not evidence that replay is safe.
        if (!(query.getStatement() instanceof PlainSelect select) || query.getParseError() != null) {
            return false;
        }
        var inspection = new ReplayInspection();
        try {
            inspection.getTables((net.sf.jsqlparser.statement.Statement) select);
            return inspection.allowed;
        } catch (RuntimeException e) {
            // Unsupported AST traversal is not proof that the query is safe.
            return false;
        }
    }

    private static class ReplayInspection extends TablesNamesFinder<Void> {
        private boolean allowed = true;

        @Override
        public <S> Void visit(PlainSelect select, S context) {
            if (!CommonUtils.isEmpty(select.getIntoTables()) || !CommonUtils.isEmpty(select.getWithItemsList())
                || select.getForMode() != null || select.getForClause() != null
                || select.getLimitBy() != null || select.getPivot() != null || select.getUnPivot() != null
                || !CommonUtils.isEmpty(select.getWindowDefinitions())) {
                allowed = false;
                return null;
            }
            super.visit(select, context);
            // The upstream table-name visitor does not inspect these clauses.
            if (select.getOrderByElements() != null) {
                for (var order : select.getOrderByElements()) {
                    order.getExpression().accept(this, context);
                }
            }
            if (select.getGroupBy() != null && select.getGroupBy().getGroupByExpressionList() != null) {
                select.getGroupBy().getGroupByExpressionList().accept(this, context);
            }
            inspect(select.getQualify(), context);
            if (select.getLimit() != null) {
                inspect(select.getLimit().getRowCount(), context);
                inspect(select.getLimit().getOffset(), context);
            }
            if (select.getOffset() != null) {
                inspect(select.getOffset().getOffset(), context);
            }
            if (select.getFetch() != null) {
                inspect(select.getFetch().getExpression(), context);
            }
            if (select.getDistinct() != null && select.getDistinct().getOnSelectItems() != null) {
                for (var item : select.getDistinct().getOnSelectItems()) {
                    item.accept(this, context);
                }
            }
            return null;
        }

        private <S> void inspect(Expression expression, S context) {
            if (expression != null) {
                expression.accept(this, context);
            }
        }

        @Override
        public <S> Void visit(Function function, S context) {
            allowed = false;
            return null;
        }

        @Override
        public <S> Void visit(TableFunction function, S context) {
            allowed = false;
            return null;
        }

        @Override
        public <S> Void visit(AnalyticExpression expression, S context) {
            allowed = false;
            return null;
        }

        @Override
        public <S> Void visit(NextValExpression expression, S context) {
            allowed = false;
            return null;
        }
    }
}
