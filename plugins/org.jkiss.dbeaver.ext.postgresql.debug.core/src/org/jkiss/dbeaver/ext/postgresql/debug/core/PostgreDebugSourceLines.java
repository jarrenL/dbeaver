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
package org.jkiss.dbeaver.ext.postgresql.debug.core;

import org.jkiss.code.Nullable;

/** Locates the first PL/pgSQL statement for pldbgapi's special entry breakpoint (-1). */
public final class PostgreDebugSourceLines {
    private PostgreDebugSourceLines() {
    }

    public static int firstStatementLine(@Nullable String source) {
        if (source == null) return 0;
        int line = 1;
        boolean body = false;
        boolean nestedDeclaration = false;
        for (int i = 0; i < source.length();) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                if (c == '\n') line++;
                i++;
                continue;
            }
            if (source.startsWith("--", i)) {
                while (i < source.length() && source.charAt(i) != '\n') i++;
                continue;
            }
            if (source.startsWith("/*", i)) {
                int depth = 1;
                i += 2;
                while (i < source.length() && depth > 0) {
                    if (source.startsWith("/*", i)) { depth++; i += 2; }
                    else if (source.startsWith("*/", i)) { depth--; i += 2; }
                    else { if (source.charAt(i++) == '\n') line++; }
                }
                continue;
            }
            if (source.startsWith("<<", i)) {
                int end = source.indexOf(">>", i + 2);
                if (end < 0) return 0;
                for (; i < end + 2; i++) if (source.charAt(i) == '\n') line++;
                continue;
            }
            if (c == '\'' || c == '"') {
                if (body && c == '"') return line;
                char quote = c;
                boolean escapes = quote == '\'' && i > 0
                    && (source.charAt(i - 1) == 'E' || source.charAt(i - 1) == 'e')
                    && (i < 2 || !Character.isJavaIdentifierPart(source.charAt(i - 2)));
                i++;
                while (i < source.length()) {
                    char next = source.charAt(i++);
                    if (next == '\n') line++;
                    if (next == '\\' && escapes && i < source.length()) {
                        if (source.charAt(i++) == '\n') line++;
                    } else if (next == quote) {
                        if (i < source.length() && source.charAt(i) == quote) i++;
                        else break;
                    }
                }
                continue;
            }
            if (c == '$') {
                int end = source.indexOf('$', i + 1);
                if (end >= 0 && source.substring(i + 1, end).matches("[A-Za-z_][A-Za-z_0-9]*|")) {
                    String tag = source.substring(i, end + 1);
                    int close = source.indexOf(tag, end + 1);
                    if (close < 0) return 0;
                    for (; i < close + tag.length(); i++) if (source.charAt(i) == '\n') line++;
                    continue;
                }
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) i++;
                String token = source.substring(start, i);
                if (body) {
                    if (token.equalsIgnoreCase("END")) return 0;
                    // A nested DECLARE block's executable statement is its BEGIN.
                    if (token.equalsIgnoreCase("DECLARE")) { body = false; nestedDeclaration = true; continue; }
                    return line;
                }
                if (token.equalsIgnoreCase("BEGIN")) {
                    if (nestedDeclaration) return line;
                    body = true;
                }
            } else {
                i++;
            }
        }
        return 0;
    }
}
