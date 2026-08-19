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

    // ---- Compatibility modes ----
    // GaussDB centralized (集中式) uses single-letter values: A/B/C/PG
    // GaussDB distributed (分布式) uses abbreviated names: ORA/MYSQL/TD/PG
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

    /** M compatibility mode (older GaussDB versions used "M" for MySQL) */
    public static final String GAUSSDB_M_COMPATIBLE_MODE = "M";

    // ---- Connection defaults ----
    public static final String GAUSSDB_DEFAULT_HOST = "localhost";
    public static final String GAUSSDB_DEFAULT_PORT = "8000";
    public static final String GAUSSDB_DEFAULT_DATABASE = "postgres";

    // ---- Driver ----
    public static final String GAUSSDB_DRIVER_CLASS_PG = "org.postgresql.Driver";
    public static final String GAUSSDB_DRIVER_CLASS_NATIVE = "com.huawei.gauss.jdbc.ZenithDriver";
    public static final String GAUSSDB_URL_PREFIX_PG = "jdbc:postgresql://";
    public static final String GAUSSDB_URL_PREFIX_NATIVE = "jdbc:gaussdb://";

    // ---- System objects ----
    public static final String GAUSSDB_SYSTEM_SCHEMA_DBE_PERF = "dbe_perf";
    public static final String GAUSSDB_SYSTEM_SCHEMA_DBE_PLDEVELOPER = "dbe_pldeveloper";
    public static final String GAUSSDB_SYSTEM_SCHEMA_MLS = "mls";

    // ---- GaussDB-specific SQL keywords ----
    public static final String[] GAUSSDB_EXTRA_KEYWORDS = {
        "PACKAGE", "BODY", "DBCOMPATIBILITY", "VECTOR", "HLL", "HASH",
        "DISTRIBUTE", "REPLICATION", "SLICE", "DECFLOAT", "GS_PACKAGE",
        "DBE_PLDEVELOPER", "GS_SOURCE", "SHRINK", "BARRIER"
    };
}
