/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreProcedureParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds typed positional arguments without interpolating user input or default expressions. */
public final class GaussDBDebugArguments {
    public enum Mode { VALUE, NULL, DEFAULT }

    public record Plan(@NotNull String sql, @NotNull List<String> values) {
        public Plan {
            // A null element represents SQL NULL, not an absent argument.
            values = Collections.unmodifiableList(new ArrayList<>(values));
        }
    }

    private GaussDBDebugArguments() {
    }

    @NotNull
    public static Plan build(@NotNull List<PostgreProcedureParameter> parameters, @NotNull List<String> values, @NotNull List<String> modes)
        throws DBGException {
        if (parameters.size() != values.size() || (!modes.isEmpty() && modes.size() != parameters.size())) {
            throw new DBGException("Debug parameter count does not match the routine signature");
        }
        StringBuilder sql = new StringBuilder();
        List<String> boundValues = new ArrayList<>();
        boolean omitted = false;
        for (int i = 0; i < parameters.size(); i++) {
            PostgreProcedureParameter parameter = parameters.get(i);
            Mode mode;
            try {
                mode = modes.isEmpty() ? (values.get(i) == null ? Mode.NULL : Mode.VALUE) : Mode.valueOf(modes.get(i));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new DBGException("Unknown debug parameter mode at position " + (i + 1), e);
            }
            if (mode == Mode.DEFAULT) {
                if (parameter.getDefaultValue() == null) {
                    throw new DBGException("No default is available for parameter " + parameter.getName());
                }
                omitted = true;
                continue;
            }
            // Omit only a trailing suffix: DEFAULT is not universally valid as a
            // function argument, and named notation differs across server modes.
            if (omitted) {
                throw new DBGException("Default parameters must form a trailing suffix; enter earlier values explicitly");
            }
            if (mode == Mode.VALUE && values.get(i) == null) {
                throw new DBGException("Enter a value or select SQL NULL for parameter " + parameter.getName());
            }
            if (!boundValues.isEmpty()) {
                sql.append(',');
            }
            sql.append("?::").append(parameter.getFullTypeName());
            boundValues.add(mode == Mode.NULL ? null : values.get(i));
        }
        return new Plan(sql.toString(), boundValues);
    }
}
