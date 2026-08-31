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

package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.sql.SQLState;

import java.util.Set;

/**
 * Classifies errors from optional GaussDB catalogs and compatibility metadata.
 */
final class GaussDBMetadataErrorHandler {

    private static final Set<String> OPTIONAL_METADATA_SQL_STATES = Set.of(
        "42501", // insufficient_privilege
        "42P01", // undefined_table
        "42703", // undefined_column
        "42883", // undefined_function
        "3F000"  // invalid_schema_name
    );

    private GaussDBMetadataErrorHandler() {
    }

    static boolean isOptionalMetadataError(Throwable error) {
        return getOptionalMetadataSqlState(error) != null;
    }

    @Nullable
    static String getOptionalMetadataSqlState(Throwable error) {
        String sqlState = SQLState.getStateFromException(error);
        return sqlState != null && OPTIONAL_METADATA_SQL_STATES.contains(sqlState) ? sqlState : null;
    }
}
