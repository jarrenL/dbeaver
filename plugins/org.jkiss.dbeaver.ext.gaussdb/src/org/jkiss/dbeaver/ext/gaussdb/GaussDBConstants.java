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
package org.jkiss.dbeaver.ext.gaussdb;

public class GaussDBConstants {

    public static final String CMD_COMPILE_PACKAGE = "org.jkiss.dbeaver.ext.gaussdb.package.compile";
    public static final String CMD_COMPILE_PACKAGE_SPECIFICATION =
        "org.jkiss.dbeaver.ext.gaussdb.package.compileSpecification";
    public static final String CMD_COMPILE_PACKAGE_BODY = "org.jkiss.dbeaver.ext.gaussdb.package.compileBody";

    // ---- Compatibility modes ----
    // GaussDB centralized (集中式) uses single-letter values: A/B/C/PG/M
    // GaussDB distributed (分布式) uses abbreviated names: ORA/MYSQL/TD/PG/M
    // The datcompatibility column in pg_database stores these values.

    /** Oracle compatibility mode — centralized value "A", distributed value "ORA" */
    public static final String GAUSSDB_ORACLE_COMPATIBLE_MODE = "ORA";
    /** Oracle compatibility mode — centralized value */
    public static final String GAUSSDB_ORACLE_COMPATIBLE_MODE_C = "A";
    /** MySQL compatibility mode — centralized value "B", distributed value "MYSQL" */
    public static final String GAUSSDB_MYSQL_COMPATIBLE_MODE = "MYSQL";
    /** MySQL compatibility mode — centralized value */
    public static final String GAUSSDB_MYSQL_COMPATIBLE_MODE_C = "B";
    /** Teradata compatibility mode — centralized value "C", distributed value "TD" */
    public static final String GAUSSDB_TERADATA_COMPATIBLE_MODE = "TD";
    /** Teradata compatibility mode — centralized value */
    public static final String GAUSSDB_TERADATA_COMPATIBLE_MODE_C = "C";
    /** PostgreSQL compatibility mode — value "PG" */
    public static final String GAUSSDB_PG_COMPATIBLE_MODE = "PG";

    /** Independent M compatibility mode */
    public static final String GAUSSDB_M_COMPATIBLE_MODE = "M";

    // ---- Connection defaults ----
    public static final String GAUSSDB_DEFAULT_HOST = "localhost";
    public static final String GAUSSDB_DEFAULT_PORT = "8000";
    public static final String GAUSSDB_DEFAULT_DATABASE = "postgres";

    // ---- Driver ----
    public static final String GAUSSDB_DRIVER_CLASS_PG = "org.postgresql.Driver";
    public static final String GAUSSDB_DRIVER_CLASS_NATIVE = "com.huawei.gaussdb.jdbc.Driver";
    public static final String GAUSSDB_URL_PREFIX_PG = "jdbc:postgresql://";
    public static final String GAUSSDB_URL_PREFIX_NATIVE = "jdbc:gaussdb://";
    public static final String BIN_FOLDER = "bin";

    // ---- System objects ----
    public static final String GAUSSDB_SYSTEM_SCHEMA_DBE_PERF = "dbe_perf";
    public static final String GAUSSDB_SYSTEM_SCHEMA_DBE_PLDEVELOPER = "dbe_pldeveloper";
    public static final String GAUSSDB_SYSTEM_SCHEMA_MLS = "mls";

    // ---- GaussDB-specific SQL keywords ----
    public static final String[] GAUSSDB_EXTRA_KEYWORDS = {
        "PACKAGE", "BODY", "DBCOMPATIBILITY", "DISTRIBUTE", "DISTRIBUTED",
        "DISTRIBUTION", "REPLICATION", "SLICE", "SHRINK", "BARRIER", "IGNORE", "REPLACE"
    };

    // ---- GaussDB-specific SQL data types ----
    public static final String[] GAUSSDB_DATA_TYPES = {
        "HLL", "HLL_HASHVAL", "FLOATVECTOR", "BOOLVECTOR", "RAW", "VARCHAR2",
        "NVARCHAR2", "CLOB", "BLOB", "SMALLDATETIME", "TINYINT", "MEDIUMINT",
        "LARGESERIAL", "INT1", "UINT1", "UINT2", "UINT4", "UINT8", "INT16",
        "NUMBER", "YEAR", "DATETIME", "BINARY", "VARBINARY", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT",
        "NCLOB", "ENUM", "SET"
    };

    // ---- GaussDB-specific SQL functions ----
    public static final String[] GAUSSDB_FUNCTIONS = {
        "gs_encrypt_aes128", "gs_decrypt_aes128", "gs_encrypt", "gs_decrypt",
        "gs_encrypt_bytea", "gs_decrypt_bytea", "hll_hash_any", "hll_union",
        "hll_cardinality", "vector_dims", "vector_l2_squared_distance",
        "vector_negative_inner_product", "vector_norm", "vector_spherical_distance", "vector_to_array"
    };
}
