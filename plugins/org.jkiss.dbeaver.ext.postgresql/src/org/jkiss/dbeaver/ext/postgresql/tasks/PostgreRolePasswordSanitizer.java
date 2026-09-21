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
package org.jkiss.dbeaver.ext.postgresql.tasks;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.Writer;
import java.util.Locale;

/** Streaming SQL lexer for role-password removal in native plain-text cluster dumps. */
final class PostgreRolePasswordSanitizer {
    private PostgreRolePasswordSanitizer() {
    }

    static void sanitize(@NotNull Reader input, @NotNull Writer output) throws IOException {
        PushbackReader reader = new PushbackReader(input, 2);
        int tokenIndex = 0;
        boolean roleCommand = false;
        boolean createOrAlter = false;
        boolean passwordValue = false;
        boolean copy = false;
        boolean copyFrom = false;
        boolean copyStdin = false;
        Token token;
        while ((token = nextToken(reader)) != null) {
            String keyword = token.word ? token.text.toUpperCase(Locale.ENGLISH) : "";
            boolean consumedPassword = passwordValue && !token.trivia;
            if (consumedPassword) {
                if (";".equals(token.text)) {
                    throw new IOException("Role PASSWORD clause has no value");
                }
                output.write("DISABLE");
                passwordValue = false;
            } else {
                output.write(token.text);
            }
            if (token.trivia) {
                continue;
            }
            if (";".equals(token.text)) {
                if (copyStdin) {
                    copyData(reader, output);
                }
                tokenIndex = 0;
                roleCommand = false;
                passwordValue = false;
                copy = false;
                copyFrom = false;
                copyStdin = false;
                continue;
            }
            if (tokenIndex == 0) {
                createOrAlter = "CREATE".equals(keyword) || "ALTER".equals(keyword);
                copy = "COPY".equals(keyword);
            } else if (tokenIndex == 1) {
                roleCommand = createOrAlter && ("ROLE".equals(keyword) || "USER".equals(keyword));
            } else if (tokenIndex > 2 && ("SET".equals(keyword) || "RESET".equals(keyword) || "RENAME".equals(keyword))) {
                roleCommand = false;
            } else if (tokenIndex > 2 && roleCommand && !consumedPassword && "PASSWORD".equals(keyword)) {
                passwordValue = true;
            }
            if (copy) {
                if (copyFrom && "STDIN".equals(keyword)) {
                    copyStdin = true;
                }
                copyFrom = "FROM".equals(keyword);
            }
            tokenIndex++;
        }
        if (passwordValue) {
            throw new IOException("Unterminated role PASSWORD clause");
        }
    }

    private static void copyData(PushbackReader reader, Writer output) throws IOException {
        // Includes the remainder of the COPY command line. Preserve line endings and data bytes.
        StringBuilder line = new StringBuilder();
        int ch;
        while ((ch = reader.read()) != -1) {
            output.write(ch);
            if (ch == '\n') {
                if ("\\.".contentEquals(line) || "\\.\r".contentEquals(line)) {
                    return;
                }
                line.setLength(0);
            } else {
                line.append((char) ch);
            }
        }
        if (!"\\.".contentEquals(line) && !"\\.\r".contentEquals(line)) {
            throw new IOException("Unterminated COPY data in cluster backup");
        }
    }

    @Nullable
    private static Token nextToken(PushbackReader reader) throws IOException {
        int ch = reader.read();
        if (ch == -1) {
            return null;
        }
        StringBuilder text = new StringBuilder().append((char) ch);
        if (Character.isWhitespace(ch)) {
            while ((ch = reader.read()) != -1 && Character.isWhitespace(ch)) {
                text.append((char) ch);
            }
            unread(reader, ch);
            return new Token(text.toString(), false, true);
        }
        if (ch == '\\') {
            // gsql/psql meta commands such as \connect are not SQL statements.
            while ((ch = reader.read()) != -1) {
                text.append((char) ch);
                if (ch == '\n') {
                    break;
                }
            }
            return new Token(text.toString(), false, true);
        }
        if (ch == '-' || ch == '/') {
            int next = reader.read();
            if (ch == '-' && next == '-') {
                text.append((char) next);
                while ((ch = reader.read()) != -1) {
                    text.append((char) ch);
                    if (ch == '\n') {
                        break;
                    }
                }
                return new Token(text.toString(), false, true);
            }
            if (ch == '/' && next == '*') {
                text.append((char) next);
                int depth = 1;
                while (depth > 0 && (ch = reader.read()) != -1) {
                    text.append((char) ch);
                    if (ch == '/' || ch == '*') {
                        next = reader.read();
                        if (ch == '/' && next == '*') {
                            depth++;
                            text.append((char) next);
                        } else if (ch == '*' && next == '/') {
                            depth--;
                            text.append((char) next);
                        } else {
                            unread(reader, next);
                        }
                    }
                }
                if (depth != 0) {
                    throw new IOException("Unterminated SQL comment in cluster backup");
                }
                return new Token(text.toString(), false, true);
            }
            unread(reader, next);
        }
        if (ch == '\'' || ch == '"') {
            readQuoted(reader, text, ch, false);
            return new Token(text.toString(), false, false);
        }
        if (Character.isLetter(ch) || ch == '_') {
            while ((ch = reader.read()) != -1 && (Character.isLetterOrDigit(ch) || ch == '_' || ch == '$')) {
                text.append((char) ch);
            }
            if (ch == '\'' && "E".equalsIgnoreCase(text.toString())) {
                text.append((char) ch);
                readQuoted(reader, text, ch, true);
                return new Token(text.toString(), false, false);
            }
            unread(reader, ch);
            return new Token(text.toString(), true, false);
        }
        if (ch == '$') {
            while ((ch = reader.read()) != -1 && (Character.isLetterOrDigit(ch) || ch == '_')) {
                text.append((char) ch);
            }
            if (ch == '$') {
                text.append('$');
                String delimiter = text.toString();
                int matched = 0;
                while ((ch = reader.read()) != -1) {
                    text.append((char) ch);
                    matched = ch == delimiter.charAt(matched) ? matched + 1 : (ch == '$' ? 1 : 0);
                    if (matched == delimiter.length()) {
                        return new Token(text.toString(), false, false);
                    }
                }
                throw new IOException("Unterminated dollar-quoted SQL in cluster backup");
            }
            unread(reader, ch);
        }
        return new Token(text.toString(), false, false);
    }

    private static void readQuoted(PushbackReader reader, StringBuilder text, int quote, boolean escapes) throws IOException {
        int ch;
        while ((ch = reader.read()) != -1) {
            text.append((char) ch);
            if (escapes && ch == '\\') {
                ch = reader.read();
                if (ch == -1) {
                    break;
                }
                text.append((char) ch);
            } else if (ch == quote) {
                int next = reader.read();
                if (next != quote) {
                    unread(reader, next);
                    return;
                }
                text.append((char) next);
            }
        }
        throw new IOException("Unterminated SQL literal in cluster backup");
    }

    private static void unread(PushbackReader reader, int ch) throws IOException {
        if (ch != -1) {
            reader.unread(ch);
        }
    }

    private record Token(String text, boolean word, boolean trivia) {
    }
}
