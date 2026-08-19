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

/**
 * GaussDB database compatibility modes.
 *
 * GaussDB supports multiple compatibility modes stored in pg_database.datcompatibility:
 * - Centralized (集中式): values are "A" (Oracle), "B" (MySQL), "C" (Teradata), "PG" (PostgreSQL)
 * - Distributed (分布式): values are "ORA" (Oracle), "MYSQL" (MySQL), "TD" (Teradata), "PG" (PostgreSQL)
 *
 * The enum maps both representations to a canonical text name for display.
 */
public enum DBCompatibilityEnum {

    ORACLE("Oracle", "A", "ORA"),
    MYSQL("MySQL", "B", "MYSQL"),
    TERADATA("Teradata", "C", "TD"),
    POSTGRES("PostgreSQL", "PG", "PG");

    private final String text;
    private final String cValue;  // Centralized value
    private final String dValue;  // Distributed value

    DBCompatibilityEnum(String text, String cValue, String dValue) {
        this.text = text;
        this.cValue = cValue;
        this.dValue = dValue;
    }

    public String getText() {
        return text;
    }

    public String getcValue() {
        return cValue;
    }

    public String getdValue() {
        return dValue;
    }

    /**
     * Gets DBCompatibilityEnum by text.
     *
     * @param text the text (e.g. "Oracle", "MySQL", "Teradata", "PostgreSQL")
     * @return the DBCompatibilityEnum, or null if not found
     */
    public static DBCompatibilityEnum of(String text) {
        if (text == null) {
            return null;
        }
        for (DBCompatibilityEnum e : values()) {
            if (e.getText().equals(text)) {
                return e;
            }
        }
        return null;
    }

    /**
     * Query DBCompatibilityEnum text by compatibility value.
     * Accepts both centralized (A/B/C/PG) and distributed (ORA/MYSQL/TD/PG) values.
     *
     * @param value the compatibility value from pg_database.datcompatibility
     * @return the canonical text (e.g. "Oracle"), or empty string if not recognized
     */
    public static String queryTextByValue(String value) {
        if (value == null) {
            return "";
        }
        for (DBCompatibilityEnum e : values()) {
            if (e.cValue.equalsIgnoreCase(value) || e.dValue.equalsIgnoreCase(value)) {
                return e.text;
            }
        }
        // Handle legacy "M" mode (older GaussDB used "M" for MySQL)
        if ("M".equalsIgnoreCase(value)) {
            return MYSQL.text;
        }
        return "";
    }

    /**
     * Get the distributed value for a given compatibility text.
     * Used when generating CREATE DATABASE DDL with DBCOMPATIBILITY clause.
     *
     * @param text the canonical text (e.g. "Oracle")
     * @return the distributed value (e.g. "ORA"), or "PG" as default
     */
    public static String getDValueByText(String text) {
        DBCompatibilityEnum e = of(text);
        return e != null ? e.dValue : PG.dValue;
    }
}
