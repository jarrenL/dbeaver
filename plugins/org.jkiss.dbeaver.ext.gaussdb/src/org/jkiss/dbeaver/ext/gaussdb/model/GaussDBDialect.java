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

import org.jkiss.dbeaver.ext.gaussdb.GaussDBConstants;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDialect;
import org.jkiss.utils.ArrayUtils;

/**
 * GaussDB SQL dialect.
 * Extends PostgreSQL dialect with GaussDB-specific keywords, types, and syntax.
 */
public class GaussDBDialect extends PostgreDialect {

    // GaussDB-specific DDL keywords
    private static final String[] GAUSSDB_DDL_KEYWORDS = {
        "PACKAGE", "BODY", "DBCOMPATIBILITY", "DISTRIBUTE", "DISTRIBUTION",
        "SLICE", "SHRINK", "BARRIER", "DECFLOAT", "VECTOR", "HLL"
    };

    // GaussDB-specific execution keywords
    private static final String[] GAUSSDB_EXEC_KEYWORDS = {
        "CALL"
    };

    // GaussDB-specific data types
    private static final String[] GAUSSDB_DATA_TYPES = {
        "VECTOR", "HLL", "DECFLOAT", "BYTEA", "RAW", "NVARCHAR2",
        "CHARACTER", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITHOUT TIME ZONE",
        "SMALLDATETIME", "CLOB", "NCLOB", "BFILE"
    };

    // GaussDB-specific functions
    private static final String[] GAUSSDB_FUNCTIONS = {
        "gs_dump", "gs_restore", "gs_encrypt", "gs_decrypt",
        "gs_hash", "gs_password_hash", "gs_session_replay",
        "hll_hash_any", "hll_union", "hll_cardinality",
        "vector_norm", "vector_distance", "vector_l2_distance",
        "vector_cosine_distance", "vector_inner_product"
    };

    @Override
    public boolean isDelimiterAfterBlock() {
        return true;
    }

    @Override
    public void addExtraKeywords(String... keywords) {
        super.addExtraKeywords(keywords);
    }

    /**
     * Initialize dialect with GaussDB-specific keywords.
     * Called by the server extension's configureDialect().
     */
    public void initGaussDBKeywords() {
        // Add DDL keywords
        for (String keyword : GAUSSDB_DDL_KEYWORDS) {
            super.addExtraKeywords(keyword);
        }
    }

    /**
     * Returns the list of GaussDB-specific data types.
     */
    public String[] getGaussDBDataTypes() {
        return ArrayUtils.concatArrays(
            super.getDataTypes(),
            GAUSSDB_DATA_TYPES
        );
    }

    /**
     * Returns GaussDB-specific function names.
     */
    public String[] getGaussDBFunctions() {
        return GAUSSDB_FUNCTIONS;
    }

    /**
     * GaussDB uses $$ for dollar-quoting (same as PG),
     * but in Oracle compatibility mode, single quotes and AS/IS are also common.
     */
    @Override
    public boolean supportsDollarQuotedStrings() {
        return true;
    }
}
