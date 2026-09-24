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
package org.jkiss.dbeaver.ext.postgresql.model;

import org.jkiss.dbeaver.DBException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;

class PostgreTablespaceCacheFailureTest {
    @ParameterizedTest
    @ValueSource(strings = {"08003", "08006", "57P01", "57014", "42501", "42P01", "XX000"})
    void catalogFailureMustPropagateInsteadOfPublishingAnEmptySuccessfulCache(String state) {
        var cache = new CacheProbe();
        var failure = new SQLException("Synthetic metadata failure", state);
        assertFalse(cache.ignore(failure));
        assertFalse(cache.ignore(new DBException("Wrapped metadata failure", failure)));
    }

    private static class CacheProbe extends PostgreDatabase.TablespaceCache {
        boolean ignore(Exception failure) {
            return handleCacheReadError(failure);
        }
    }
}
