/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.debug.DBGVariable;
import org.jkiss.dbeaver.debug.DBGVariableType;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/** Interprets only cursor state already supplied by info_locals; it never fetches from a portal. */
public final class GaussDBCursorVariables {
    private static final String LEGACY_PREFIX = "_gsdb_cursor_attri";

    private GaussDBCursorVariables() {
    }

    @NotNull
    public static List<DBGVariable<?>> aggregate(@NotNull List<GaussDBDebugVariable> variables) {
        Map<Scope, List<Integer>> rows = new HashMap<>();
        for (int i = 0; i < variables.size(); i++) {
            GaussDBDebugVariable variable = variables.get(i);
            rows.computeIfAbsent(scope(variable, variable.getName()), ignored -> new ArrayList<>()).add(i);
        }
        Map<Integer, GaussDBCursorVariable> cursors = new HashMap<>();
        Set<Integer> consumed = new HashSet<>();
        for (int i = 0; i < variables.size(); i++) {
            GaussDBDebugVariable cursor = variables.get(i);
            if (!GaussDBDebugVariable.isCursorType(cursor.getDataType())) {
                continue;
            }
            CursorState state = readInline(cursor.getVal());
            if (state.valid && rows.get(scope(cursor, cursor.getName())).size() == 1) {
                for (Attribute attribute : Attribute.values()) {
                    // Older name-based protocol rows must match the entire owner name. Numeric
                    // __gsdb_cursor_attri_<datum>_...__ internals cannot be mapped safely by name.
                    String hiddenName = LEGACY_PREFIX + cursor.getName() + "__" + attribute.legacyName;
                    List<Integer> matches = rows.get(scope(cursor, hiddenName));
                    if (matches == null || matches.size() != 1) {
                        continue;
                    }
                    int index = matches.getFirst();
                    GaussDBDebugVariable hidden = variables.get(index);
                    if (GaussDBDebugVariable.isCursorType(hidden.getDataType())) {
                        continue;
                    }
                    String value = attribute.parse(hidden.getVal());
                    if (value == null) {
                        continue;
                    }
                    String inlineValue = state.values.get(attribute);
                    if (inlineValue != null && !inlineValue.equals(value)) {
                        // Preserve conflicting protocol data instead of concealing it in the UI.
                        continue;
                    }
                    state.values.put(attribute, value);
                    consumed.add(index);
                }
            }
            cursors.put(i, snapshot(cursor, state));
        }
        List<DBGVariable<?>> result = new ArrayList<>(variables.size() - consumed.size());
        for (int i = 0; i < variables.size(); i++) {
            if (!consumed.contains(i)) {
                result.add(cursors.containsKey(i) ? cursors.get(i) : variables.get(i));
            }
        }
        return List.copyOf(result);
    }

    @NotNull
    private static Scope scope(@NotNull GaussDBDebugVariable variable, @Nullable String name) {
        return new Scope(name, variable.getPackageName(), variable.getLineNumber());
    }

    @NotNull
    private static CursorState readInline(@Nullable String rawValue) {
        CursorState state = new CursorState();
        if (rawValue == null || rawValue.isBlank() || "NULL".equalsIgnoreCase(rawValue.trim())) {
            return state;
        }
        String raw = rawValue.trim();
        if (!raw.startsWith("{") && !raw.endsWith("}")) {
            state.portalName = rawValue;
            return state;
        }
        if (!raw.startsWith("{") || !raw.endsWith("}")) {
            return CursorState.invalid();
        }
        Set<String> fields = new HashSet<>();
        String body = raw.substring(1, raw.length() - 1);
        if (body.isBlank()) {
            return CursorState.invalid();
        }
        for (String line : body.split("\\R", -1)) {
            int separator = line.indexOf(':');
            if (separator < 0) {
                return CursorState.invalid();
            }
            String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if (!fields.add(name)) {
                return CursorState.invalid();
            }
            if ("name".equals(name)) {
                state.portalName = value.isEmpty() || "NULL".equalsIgnoreCase(value) ? null : value;
                continue;
            }
            Attribute attribute = Attribute.forInlineName(name);
            if (attribute == null) {
                return CursorState.invalid();
            }
            String parsed = attribute.parse(value);
            if (parsed == null) {
                return CursorState.invalid();
            }
            state.values.put(attribute, parsed);
        }
        return state;
    }

    @NotNull
    private static GaussDBCursorVariable snapshot(@NotNull GaussDBDebugVariable cursor, @NotNull CursorState state) {
        List<DBGVariable<?>> children = new ArrayList<>();
        for (Attribute attribute : Attribute.values()) {
            String value = state.values.get(attribute);
            if (value != null) {
                children.add(new CursorAttribute(cursor, "%" + attribute.name(), attribute.dataType, value));
            }
        }
        if (state.portalName != null) {
            children.add(new CursorAttribute(cursor, "NAME", "text", state.portalName));
        }
        if (cursor.getPackageName() != null && !cursor.getPackageName().isBlank()) {
            children.add(new CursorAttribute(cursor, "PACKAGE", "text", cursor.getPackageName()));
        }
        String summary = cursor.getVal();
        if (!state.values.isEmpty()) {
            StringJoiner text = new StringJoiner(", ", "(", ")");
            for (Attribute attribute : List.of(Attribute.ROWCOUNT, Attribute.ISOPEN, Attribute.FOUND, Attribute.NOTFOUND)) {
                if (state.values.containsKey(attribute)) {
                    text.add(attribute.name() + "=" + state.values.get(attribute));
                }
            }
            summary = text.toString();
        }
        return new GaussDBCursorVariable(cursor, summary, children);
    }

    private record Scope(String name, String packageName, int frame) {
    }

    private static final class CursorState {
        private final EnumMap<Attribute, String> values = new EnumMap<>(Attribute.class);
        private boolean valid = true;
        private String portalName;

        @NotNull
        private static CursorState invalid() {
            CursorState state = new CursorState();
            state.valid = false;
            return state;
        }
    }

    private enum Attribute {
        ISOPEN("is_open", "isopen", "boolean"),
        FOUND("found", "found", "boolean"),
        NOTFOUND("not_found", "notfound", "boolean"),
        ROWCOUNT("row_count", "rowcount", "integer");

        private final String inlineName;
        private final String legacyName;
        private final String dataType;

        Attribute(@NotNull String inlineName, @NotNull String legacyName, @NotNull String dataType) {
            this.inlineName = inlineName;
            this.legacyName = legacyName;
            this.dataType = dataType;
        }

        @Nullable
        private static Attribute forInlineName(@NotNull String name) {
            for (Attribute attribute : values()) {
                if (attribute.inlineName.equals(name)) {
                    return attribute;
                }
            }
            return null;
        }

        @Nullable
        private String parse(@Nullable String rawValue) {
            if (rawValue == null || "NULL".equalsIgnoreCase(rawValue.trim())) {
                return "NULL";
            }
            String value = rawValue.trim();
            if (this == ROWCOUNT) {
                if (!value.matches("[0-9]+")) {
                    return null;
                }
                return new BigInteger(value).toString();
            }
            if ("t".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value)) {
                return "true";
            }
            if ("f".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                return "false";
            }
            return null;
        }
    }

    private static final class CursorAttribute extends GaussDBDebugVariable {
        private CursorAttribute(
            @NotNull GaussDBDebugVariable cursor,
            @NotNull String name,
            @NotNull String dataType,
            @NotNull String value
        ) {
            super(name, dataType, value, cursor.getPackageName(), true, cursor.getLineNumber());
        }

        @NotNull
        @Override
        public DBGVariableType getType() {
            return "integer".equals(getDataType()) ? DBGVariableType.NUMBER : DBGVariableType.TEXT;
        }

        @Override
        public void setValue(@Nullable String value) {
            throw new UnsupportedOperationException("Cursor state is read-only");
        }
    }
}
