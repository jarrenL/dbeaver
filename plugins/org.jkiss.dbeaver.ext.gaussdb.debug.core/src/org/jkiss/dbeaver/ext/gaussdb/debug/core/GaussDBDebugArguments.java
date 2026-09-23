/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreProcedureParameter;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameterKind;

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

    /** CALL requires positional OUT placeholders; SELECT functions still use input arguments only. */
    @NotNull
    public static Plan buildProcedure(@NotNull List<PostgreProcedureParameter> parameters,
        @NotNull List<String> values, @NotNull List<String> modes) throws DBGException {
        List<PostgreProcedureParameter> inputs = new ArrayList<>();
        for (var parameter : parameters) {
            var kind = parameter.getParameterKind();
            if (kind == null || (kind != DBSProcedureParameterKind.IN && kind != DBSProcedureParameterKind.INOUT
                && kind != DBSProcedureParameterKind.OUT)) {
                throw new DBGException("Unsupported procedure parameter direction for " + parameter.getName());
            }
            if (kind.isInput()) {
                inputs.add(parameter);
            }
        }
        Plan bound = build(inputs, values, modes);
        List<String> arguments = new ArrayList<>();
        int inputIndex = 0;
        boolean omitted = false;
        for (var parameter : parameters) {
            if (parameter.getParameterKind() == DBSProcedureParameterKind.OUT) {
                if (omitted) {
                    throw new DBGException("Enter defaulted input parameters explicitly when followed by an OUT parameter");
                }
                arguments.add("NULL::" + parameter.getFullTypeName());
            } else {
                if (inputIndex++ >= bound.values().size()) {
                    omitted = true;
                } else {
                    arguments.add("?::" + parameter.getFullTypeName());
                }
            }
        }
        return new Plan(String.join(",", arguments), bound.values());
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
