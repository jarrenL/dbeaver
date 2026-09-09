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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.meta.ForTest;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real GaussDB product information and catalog capabilities.
 *
 * GaussDB exposes a PostgreSQL-compatible {@code server_version}, so PostgreSQL
 * version gates cannot be used as product feature gates. This object is populated
 * with vendor functions, settings and catalog probes instead.
 */
public final class GaussDBServerInfo {
    private static final Log log = Log.getLog(GaussDBServerInfo.class);
    private static final Pattern PRODUCT_VERSION_PATTERN = Pattern.compile(
        "(?i)(?:gaussdb(?:\\s+kernel)?|opengauss)[^0-9]*([0-9]+(?:\\.[0-9]+){1,3})"
    );
    private static final Pattern FALLBACK_VERSION_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+){1,3})");

    private static final GaussDBServerInfo UNKNOWN = new GaussDBServerInfo(
        false,
        "",
        "",
        "",
        Deployment.UNKNOWN,
        "",
        null,
        Collections.emptyMap(),
        Collections.emptySet(),
        Collections.emptySet(),
        Collections.emptySet()
    );

    private final boolean loaded;
    private final String versionText;
    private final String productVersion;
    private final String openGaussVersion;
    private final Deployment deployment;
    private final String compatibilityValue;
    private final DBCompatibilityEnum compatibility;
    private final Map<String, String> settings;
    private final Set<String> relations;
    private final Set<String> columns;
    private final Set<String> functions;

    private GaussDBServerInfo(
        boolean loaded,
        @NotNull String versionText,
        @NotNull String productVersion,
        @NotNull String openGaussVersion,
        @NotNull Deployment deployment,
        @NotNull String compatibilityValue,
        @Nullable DBCompatibilityEnum compatibility,
        @NotNull Map<String, String> settings,
        @NotNull Set<String> relations,
        @NotNull Set<String> columns,
        @NotNull Set<String> functions
    ) {
        this.loaded = loaded;
        this.versionText = versionText;
        this.productVersion = productVersion;
        this.openGaussVersion = openGaussVersion;
        this.deployment = deployment;
        this.compatibilityValue = compatibilityValue;
        this.compatibility = compatibility;
        this.settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        this.relations = Collections.unmodifiableSet(new LinkedHashSet<>(relations));
        this.columns = Collections.unmodifiableSet(new LinkedHashSet<>(columns));
        this.functions = Collections.unmodifiableSet(new LinkedHashSet<>(functions));
    }

    @NotNull
    public static GaussDBServerInfo unknown() {
        return UNKNOWN;
    }

    @ForTest
    static GaussDBServerInfo forTest(@NotNull Deployment deployment, @NotNull Set<String> relations) {
        return new GaussDBServerInfo(true, "", "", "", deployment, "A", DBCompatibilityEnum.ORACLE,
            Collections.emptyMap(), relations, Collections.emptySet(), Collections.emptySet());
    }

    @ForTest
    @NotNull
    static GaussDBServerInfo forTest(
        @Nullable DBCompatibilityEnum compatibility,
        @NotNull Set<String> relations,
        @NotNull Set<String> functions
    ) {
        return new GaussDBServerInfo(
            true,
            "",
            "",
            "",
            Deployment.UNKNOWN,
            compatibility == null ? "" : compatibility.getcValue(),
            compatibility,
            Collections.emptyMap(),
            relations,
            Collections.emptySet(),
            functions
        );
    }

    @ForTest
    @NotNull
    static GaussDBServerInfo forTest(
        @Nullable DBCompatibilityEnum compatibility,
        @NotNull Set<String> relations,
        @NotNull Set<String> columns,
        @NotNull Set<String> functions
    ) {
        return new GaussDBServerInfo(
            true,
            "",
            "",
            "",
            Deployment.UNKNOWN,
            compatibility == null ? "" : compatibility.getcValue(),
            compatibility,
            Collections.emptyMap(),
            relations,
            columns,
            functions
        );
    }

    @NotNull
    public static GaussDBServerInfo read(@NotNull JDBCSession session) {
        Set<String> relations = readNames(
            session,
            "SELECT lower(c.relname::text) " +
                "FROM pg_catalog.pg_class c " +
                "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE n.nspname = 'pg_catalog' AND lower(c.relname::text) IN " +
                "('pg_partition','pg_stat_activity','pg_locks','pg_matviews','pg_foreign_server'," +
                "'pg_language','pg_tablespace','pg_roles','pg_collation','pgxc_class','pgxc_group'," +
                "'pg_rlspolicies','pg_attrdef','gs_package')",
            "catalog relations"
        );
        Set<String> columns = readNames(
            session,
            "SELECT lower(c.relname::text) || '.' || lower(a.attname::text) " +
                "FROM pg_catalog.pg_attribute a " +
                "JOIN pg_catalog.pg_class c ON c.oid=a.attrelid " +
                "JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace " +
                "WHERE n.nspname='pg_catalog' AND a.attnum>0 AND NOT a.attisdropped AND " +
                "((c.relname='pg_attrdef' AND a.attname='adgencol') OR " +
                "(c.relname='pg_class' AND a.attname='relrowsecurity'))",
            "catalog columns"
        );
        Set<String> functions = readNames(
            session,
            "SELECT DISTINCT lower(p.proname::text) " +
                "FROM pg_catalog.pg_proc p " +
                "JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace " +
                "WHERE n.nspname = 'pg_catalog' AND lower(p.proname::text) IN " +
                "('opengauss_version','gs_deployment','pg_get_functiondef','pg_get_tabledef')",
            "catalog functions"
        );
        Map<String, String> settings = readSettings(session);
        String versionText = readString(session, "SELECT version()", "product version");
        String openGaussVersion = functions.contains("opengauss_version")
            ? readString(session, "SELECT pg_catalog.opengauss_version()", "openGauss compatibility version")
            : "";
        String deploymentValue = functions.contains("gs_deployment")
            ? readString(session, "SELECT pg_catalog.gs_deployment()", "deployment type")
            : "";
        String compatibilityValue = settings.getOrDefault("sql_compatibility", "");

        return new GaussDBServerInfo(
            true,
            versionText,
            parseProductVersion(versionText),
            openGaussVersion,
            Deployment.fromValue(deploymentValue),
            compatibilityValue,
            DBCompatibilityEnum.fromValue(compatibilityValue),
            settings,
            relations,
            columns,
            functions
        );
    }

    @NotNull
    private static Map<String, String> readSettings(@NotNull JDBCSession session) {
        Map<String, String> settings = new LinkedHashMap<>();
        String sql = "SELECT lower(name), setting FROM pg_catalog.pg_settings " +
            "WHERE lower(name) IN ('sql_compatibility','m_format_dev_version'," +
            "'b_format_version','b_format_dev_version')";
        try (JDBCPreparedStatement statement = session.prepareStatement(sql);
             JDBCResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                settings.put(resultSet.getString(1), resultSet.getString(2));
            }
        } catch (SQLException e) {
            log.debug("Error reading GaussDB compatibility settings", e);
        }
        return settings;
    }

    @NotNull
    private static Set<String> readNames(
        @NotNull JDBCSession session,
        @NotNull String sql,
        @NotNull String description
    ) {
        try {
            return new LinkedHashSet<>(JDBCUtils.queryStrings(session, sql));
        } catch (SQLException e) {
            log.debug("Error reading GaussDB " + description, e);
            return Collections.emptySet();
        }
    }

    @NotNull
    private static String readString(
        @NotNull JDBCSession session,
        @NotNull String sql,
        @NotNull String description
    ) {
        try {
            String value = JDBCUtils.queryString(session, sql);
            return value == null ? "" : value;
        } catch (SQLException e) {
            log.debug("Error reading GaussDB " + description, e);
            return "";
        }
    }

    @NotNull
    static String parseProductVersion(@Nullable String versionText) {
        if (versionText == null || versionText.isBlank()) {
            return "";
        }
        Matcher matcher = PRODUCT_VERSION_PATTERN.matcher(versionText);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = FALLBACK_VERSION_PATTERN.matcher(versionText);
        return matcher.find() ? matcher.group(1) : "";
    }

    public boolean isLoaded() {
        return loaded;
    }

    @NotNull
    public String getVersionText() {
        return versionText;
    }

    @NotNull
    public String getProductVersion() {
        return productVersion;
    }

    @NotNull
    public String getOpenGaussVersion() {
        return openGaussVersion;
    }

    @NotNull
    public Deployment getDeployment() {
        return deployment;
    }

    @NotNull
    public String getCompatibilityValue() {
        return compatibilityValue;
    }

    @Nullable
    public DBCompatibilityEnum getCompatibility() {
        return compatibility;
    }

    public boolean isMCompatibility() {
        return compatibility == DBCompatibilityEnum.M;
    }

    @NotNull
    public String getSetting(@NotNull String name) {
        return settings.getOrDefault(name.toLowerCase(Locale.ENGLISH), "");
    }

    public boolean hasRelation(@NotNull String name) {
        return relations.contains(name.toLowerCase(Locale.ENGLISH));
    }

    public boolean hasColumn(@NotNull String relation, @NotNull String column) {
        return columns.contains((relation + "." + column).toLowerCase(Locale.ENGLISH));
    }

    public boolean hasFunction(@NotNull String name) {
        return functions.contains(name.toLowerCase(Locale.ENGLISH));
    }

    public enum Deployment {
        CENTRALIZED,
        DISTRIBUTED,
        CLOUD_NATIVE,
        UNKNOWN;

        @NotNull
        static Deployment fromValue(@Nullable String value) {
            if (value == null) {
                return UNKNOWN;
            }
            String normalized = value.trim().toLowerCase(Locale.ENGLISH);
            if (normalized.contains("distribut")) {
                return DISTRIBUTED;
            }
            if (normalized.contains("central")) {
                return CENTRALIZED;
            }
            if (normalized.contains("cloud")) {
                return CLOUD_NATIVE;
            }
            return UNKNOWN;
        }
    }
}
