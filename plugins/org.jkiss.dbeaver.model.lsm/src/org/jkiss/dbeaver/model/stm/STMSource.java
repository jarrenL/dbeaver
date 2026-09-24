/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package org.jkiss.dbeaver.model.stm;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.jkiss.code.NotNull;

import java.io.IOException;
import java.io.Reader;

/**
 * Source stream for syntax analysis
 */
public interface STMSource {

    /**
     * Get characters stream
     */
    CharStream getStream();

    /**
     * Prepare source based on text reader
     */
    @NotNull
    public static STMSource fromReader(@NotNull Reader reader) throws IOException {
        return new STMSourceImpl(reader);
    }

    /**
     * Prepare source based on text string
     */
    @SuppressWarnings("deprecation") // UTF-16 indices must match Java String and Eclipse document offsets.
    public static STMSource fromString(String string) {
        // CodePointCharStream counts supplementary characters once, shifting every later source range.
        return () -> new ANTLRInputStream(string);
    }
}
