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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDataSource;
import org.jkiss.dbeaver.ext.postgresql.PostgreDataSourceProvider;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPDataSourceURLProvider;
import org.jkiss.dbeaver.model.access.DBAAuthCredentials;
import org.jkiss.dbeaver.model.access.DBAAuthModel;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.connection.DBPDriverConfigurationType;
import org.jkiss.dbeaver.model.connection.DBPNativeClientLocation;
import org.jkiss.dbeaver.model.connection.LocalNativeClientLocation;
import org.jkiss.dbeaver.model.connection.NativeClientLocationUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public class GaussDBDataSourceProvider extends PostgreDataSourceProvider {
    private static final Log log = Log.getLog(GaussDBDataSourceProvider.class);
    private static volatile List<DBPNativeClientLocation> localClients;

    public GaussDBDataSourceProvider() {
        super(GaussDBDataSource.class);
    }

    @Override
    public long getFeatures() {
        return FEATURE_CATALOGS | FEATURE_SCHEMAS;
    }

    @NotNull
    @Override
    public GaussDBDataSource openDataSource(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSourceContainer container) throws DBException {
        return new GaussDBDataSource(monitor, container);
    }

    @NotNull
    @Override
    public String getConnectionURL(@NotNull DBPDriver driver, @NotNull DBPConnectionConfiguration connectionInfo) throws DBException {
        DBAAuthModel<DBAAuthCredentials> authModel = connectionInfo.getAuthModel();
        if (authModel instanceof DBPDataSourceURLProvider) {
            String connectionURL = ((DBPDataSourceURLProvider) authModel).getConnectionURL(driver, connectionInfo);
            if (CommonUtils.isNotEmpty(connectionURL)) {
                return connectionURL;
            }
        }
        if (connectionInfo.getConfigurationType() == DBPDriverConfigurationType.URL) {
            return connectionInfo.getUrl();
        }
        String prefix = GaussDBConstants.GAUSSDB_DRIVER_CLASS_NATIVE.equals(driver.getDriverClassName())
            ? GaussDBConstants.GAUSSDB_URL_PREFIX_NATIVE
            : GaussDBConstants.GAUSSDB_URL_PREFIX_PG;
        StringBuilder url = new StringBuilder(prefix);
        try {
            url.append(formatHosts(connectionInfo.getHostName(), connectionInfo.getHostPort())).append("/");
        } catch (IllegalArgumentException e) {
            throw new DBException("Invalid GaussDB host list", e);
        }
        if (!CommonUtils.isEmpty(connectionInfo.getDatabaseName())) {
            url.append(connectionInfo.getDatabaseName());
        }
        return url.toString();
    }

    @NotNull
    static String formatHosts(String hostNames, String defaultPort) {
        StringJoiner hosts = new StringJoiner(",");
        for (String hostName : CommonUtils.notEmpty(hostNames).split(",")) {
            String host = hostName.trim();
            if (host.isEmpty()) {
                continue;
            }
            if (host.indexOf('/') >= 0 || host.indexOf('?') >= 0 || host.indexOf('#') >= 0 || host.indexOf('@') >= 0) {
                throw new IllegalArgumentException("Host contains URL components: " + host);
            }
            boolean ipv6 = !host.startsWith("[") && host.indexOf(':') != host.lastIndexOf(':');
            if (ipv6) {
                host = "[" + host + "]";
            }
            if (CommonUtils.isNotEmpty(defaultPort) && !hasExplicitPort(host)) {
                host += ":" + defaultPort;
            }
            hosts.add(host);
        }
        return hosts.toString();
    }

    private static boolean hasExplicitPort(@NotNull String host) {
        if (host.startsWith("[")) {
            int closingBracket = host.indexOf(']');
            return closingBracket >= 0 && closingBracket + 1 < host.length() && host.charAt(closingBracket + 1) == ':';
        }
        int firstColon = host.indexOf(':');
        return firstColon >= 0 && firstColon == host.lastIndexOf(':');
    }

    @Override
    public boolean providesDriverClasses(@NotNull DBPDriver driver) {
        return false;
    }

    @NotNull
    @Override
    public List<DBPNativeClientLocation> findLocalClientLocations() {
        List<DBPNativeClientLocation> clients = localClients;
        if (clients == null) {
            synchronized (GaussDBDataSourceProvider.class) {
                clients = localClients;
                if (clients == null) {
                    clients = findGaussDBClients();
                    localClients = clients;
                }
            }
        }
        return new ArrayList<>(clients);
    }

    @Nullable
    @Override
    public DBPNativeClientLocation getDefaultLocalClientLocation() {
        return CommonUtils.getFirstOrNull(findLocalClientLocations());
    }

    @Override
    public String getProductName(DBPNativeClientLocation location) {
        return "GaussDB";
    }

    @Nullable
    @Override
    public String getProductVersion(DBPNativeClientLocation location) {
        File binPath = location.getPath();
        File binFolder = new File(binPath, GaussDBConstants.BIN_FOLDER);
        if (binFolder.isDirectory()) {
            binPath = binFolder;
        }
        File gsql = new File(binPath, RuntimeUtils.getNativeBinaryName("gsql"));
        try {
            Process process = new ProcessBuilder(gsql.getAbsolutePath(), "--version")
                .redirectErrorStream(true)
                .start();
            try (BufferedReader reader = process.inputReader()) {
                return reader.readLine();
            } finally {
                process.destroy();
            }
        } catch (IOException e) {
            log.debug("Error reading GaussDB local client version from " + gsql, e);
            return null;
        }
    }

    @NotNull
    private static List<DBPNativeClientLocation> findGaussDBClients() {
        if (RuntimeUtils.isWindows()) {
            // Windows installations can still be selected manually in the native client dialog.
            return List.of();
        }
        return new ArrayList<>(NativeClientLocationUtils.findLocalClientsOnUnix(
            List.of("/opt/gaussdb", "/opt/huawei", "/usr/local/gaussdb", "/Applications/GaussDB"),
            List.of("bin/gsql"),
            path -> {
                File home = path.toFile();
                return new LocalNativeClientLocation(home.getAbsolutePath(), home, "GaussDB");
            }
        ).values());
    }
}
