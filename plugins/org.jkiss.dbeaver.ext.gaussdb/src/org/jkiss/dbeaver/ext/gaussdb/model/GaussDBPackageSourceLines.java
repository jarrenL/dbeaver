/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.code.Nullable;

/** GS_ERRORS counts the package content; GS_SOURCE includes its CREATE header. */
final class GaussDBPackageSourceLines {
    private GaussDBPackageSourceLines() {
    }

    static int toEditorLine(@Nullable String definition, int contentLine) {
        if (definition == null || contentLine < 1) {
            return contentLine;
        }
        int lineOffset = 0;
        boolean createSeen = false;
        boolean packageSeen = false;
        for (int i = 0; i < definition.length();) {
            char ch = definition.charAt(i);
            if (ch == '\n') { lineOffset++; i++; continue; }
            if (ch == '"' || ch == '\'') {
                char quote = ch;
                i++;
                while (i < definition.length()) {
                    ch = definition.charAt(i++);
                    if (ch == '\n') { lineOffset++; }
                    if (ch == quote) {
                        if (i < definition.length() && definition.charAt(i) == quote) { i++; }
                        else { break; }
                    }
                }
                continue;
            }
            if (definition.startsWith("--", i)) {
                while (i < definition.length() && definition.charAt(i) != '\n') { i++; }
                continue;
            }
            if (definition.startsWith("/*", i)) {
                int depth = 1;
                i += 2;
                while (i < definition.length() && depth > 0) {
                    if (definition.startsWith("/*", i)) { depth++; i += 2; }
                    else if (definition.startsWith("*/", i)) { depth--; i += 2; }
                    else if (definition.charAt(i++) == '\n') { lineOffset++; }
                }
                continue;
            }
            if (Character.isLetter(ch) || ch == '_') {
                int start = i++;
                while (i < definition.length() && (Character.isLetterOrDigit(definition.charAt(i))
                    || definition.charAt(i) == '_' || definition.charAt(i) == '$' || definition.charAt(i) == '#')) { i++; }
                String token = definition.substring(start, i);
                if (!createSeen) {
                    if (!token.equalsIgnoreCase("CREATE")) { return contentLine; }
                    createSeen = true;
                }
                if (packageSeen && (token.equalsIgnoreCase("AS") || token.equalsIgnoreCase("IS"))) {
                    // The first newline after AS separates the header from content.
                    // Further blank lines belong to the compiler's content coordinates.
                    while (i < definition.length() && (definition.charAt(i) == ' ' || definition.charAt(i) == '\t'
                        || definition.charAt(i) == '\r')) { i++; }
                    if (i < definition.length() && definition.charAt(i) == '\n') { lineOffset++; }
                    return contentLine + lineOffset;
                }
                packageSeen |= token.equalsIgnoreCase("PACKAGE");
                continue;
            }
            i++;
        }
        return contentLine;
    }
}
