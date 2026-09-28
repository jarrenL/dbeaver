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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSourceURLProvider;
import org.jkiss.dbeaver.model.access.DBAAuthModel;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.connection.DBPDriverConfigurationType;
import org.mockito.Mockito;

public class GaussDBDataSourceProviderTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void structuredUrlUsesSelectedDriverAndPreservesMixedEndpoints(boolean nativeDriver) throws Exception {
        var driver = Mockito.mock(DBPDriver.class);
        var config = Mockito.mock(DBPConnectionConfiguration.class);
        Mockito.when(driver.getDriverClassName()).thenReturn(nativeDriver
            ? GaussDBConstants.GAUSSDB_DRIVER_CLASS_NATIVE : "org.postgresql.Driver");
        Mockito.when(config.getHostName()).thenReturn("cn1:8001,2001:db8::1");
        Mockito.when(config.getHostPort()).thenReturn("8000");
        Mockito.when(config.getDatabaseName()).thenReturn("appdb");
        Assertions.assertEquals((nativeDriver ? GaussDBConstants.GAUSSDB_URL_PREFIX_NATIVE
            : GaussDBConstants.GAUSSDB_URL_PREFIX_PG) + "cn1:8001,[2001:db8::1]:8000/appdb",
            new GaussDBDataSourceProvider().getConnectionURL(driver, config));
        Mockito.verify(config, Mockito.never()).setUrl(Mockito.anyString());
    }

    @Test
    void explicitUrlIsNotRebuiltFromStaleStructuredFields() throws Exception {
        var driver = Mockito.mock(DBPDriver.class);
        var config = Mockito.mock(DBPConnectionConfiguration.class);
        String url = "jdbc:gaussdb://cn1:8000,cn2:8001/appdb?sslmode=verify-full";
        Mockito.when(config.getConfigurationType()).thenReturn(DBPDriverConfigurationType.URL);
        Mockito.when(config.getUrl()).thenReturn(url);
        Assertions.assertEquals(url, new GaussDBDataSourceProvider().getConnectionURL(driver, config));
        Mockito.verifyNoInteractions(driver);
        Mockito.verify(config, Mockito.never()).getHostName();
    }

    @Test
    void authUrlProviderTakesPrecedenceAndItsFailureDoesNotSilentlyFallback() throws Exception {
        var driver = Mockito.mock(DBPDriver.class);
        var config = Mockito.mock(DBPConnectionConfiguration.class);
        var auth = Mockito.mock(DBAAuthModel.class, Mockito.withSettings().extraInterfaces(DBPDataSourceURLProvider.class));
        Mockito.doReturn(auth).when(config).getAuthModel();
        var urlProvider = (DBPDataSourceURLProvider) auth;
        Mockito.when(urlProvider.getConnectionURL(driver, config)).thenReturn("jdbc:gaussdb://auth-cn:8000/appdb");
        var provider = new GaussDBDataSourceProvider();
        Assertions.assertEquals("jdbc:gaussdb://auth-cn:8000/appdb", provider.getConnectionURL(driver, config));
        DBException failure = new DBException("authentication URL unavailable");
        Mockito.when(urlProvider.getConnectionURL(driver, config)).thenThrow(failure);
        Assertions.assertSame(failure, Assertions.assertThrows(DBException.class,
            () -> provider.getConnectionURL(driver, config)));
        Mockito.verify(config, Mockito.never()).getUrl();
        Mockito.verify(config, Mockito.never()).getHostName();
        Mockito.verifyNoInteractions(driver);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    void absentAuthUrlFallsBackToExplicitUrl(String authUrl) throws Exception {
        var driver = Mockito.mock(DBPDriver.class);
        var config = Mockito.mock(DBPConnectionConfiguration.class);
        var auth = Mockito.mock(DBAAuthModel.class, Mockito.withSettings().extraInterfaces(DBPDataSourceURLProvider.class));
        Mockito.doReturn(auth).when(config).getAuthModel();
        Mockito.when(((DBPDataSourceURLProvider) auth).getConnectionURL(driver, config)).thenReturn(authUrl);
        Mockito.when(config.getConfigurationType()).thenReturn(DBPDriverConfigurationType.URL);
        Mockito.when(config.getUrl()).thenReturn("jdbc:postgresql://cn1:8000/appdb");
        Assertions.assertEquals("jdbc:postgresql://cn1:8000/appdb",
            new GaussDBDataSourceProvider().getConnectionURL(driver, config));
        Mockito.verifyNoInteractions(driver);
    }

    @Test
    void invalidStructuredHostIsWrappedWithoutMutatingConfiguration() {
        var driver = Mockito.mock(DBPDriver.class);
        var config = Mockito.mock(DBPConnectionConfiguration.class);
        Mockito.when(config.getHostName()).thenReturn("cn1?sslmode=disable");
        var error = Assertions.assertThrows(DBException.class,
            () -> new GaussDBDataSourceProvider().getConnectionURL(driver, config));
        Assertions.assertInstanceOf(IllegalArgumentException.class, error.getCause());
        Mockito.verify(config, Mockito.never()).setUrl(Mockito.anyString());
    }

    @Test
    public void emptyHostEntriesAreIgnoredWithoutInventingAnEndpoint() {
        Assertions.assertEquals("", GaussDBDataSourceProvider.formatHosts(null, "8000"));
        Assertions.assertEquals("", GaussDBDataSourceProvider.formatHosts(" , , ", "8000"));
        Assertions.assertEquals("cn1:8000,cn2:8000",
            GaussDBDataSourceProvider.formatHosts(" ,cn1, ,cn2, ", "8000"));
    }

    @Test
    public void missingDefaultPortDoesNotAppendLiteralNullOrColon() {
        Assertions.assertEquals("cn1,cn2:8002", GaussDBDataSourceProvider.formatHosts("cn1,cn2:8002", null));
        Assertions.assertEquals("[::1]", GaussDBDataSourceProvider.formatHosts("::1", ""));
    }

    @Test
    public void bracketedIpv6GetsExactlyOneDefaultPort() {
        Assertions.assertEquals("[::1]:8000,[2001:db8::1]:8001",
            GaussDBDataSourceProvider.formatHosts("[::1],[2001:db8::1]:8001", "8000"));
    }

    @Test
    public void manualHostsCannotInjectQueryFragmentOrUserInfo() {
        for (String host : java.util.List.of("cn1?ssl=false", "cn1#fragment", "user@cn1", "cn1/database")) {
            Assertions.assertThrows(IllegalArgumentException.class,
                () -> GaussDBDataSourceProvider.formatHosts(host, "8000"), host);
        }
    }

    @Test
    public void formatsMultiHostAddressWithSharedPort() {
        Assertions.assertEquals(
            "cn1.example:8000,cn2.example:8000",
            GaussDBDataSourceProvider.formatHosts("cn1.example, cn2.example", "8000")
        );
    }

    @Test
    public void preservesPerHostPortsAndFormatsIpv6() {
        Assertions.assertEquals(
            "cn1.example:8001,[2001:db8::1]:8000,[2001:db8::2]:8002",
            GaussDBDataSourceProvider.formatHosts(
                "cn1.example:8001,2001:db8::1,[2001:db8::2]:8002",
                "8000"
            )
        );
    }

    @Test
    public void rejectsUrlComponentsInManualHostList() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> GaussDBDataSourceProvider.formatHosts("user@cn1.example/database", "8000")
        );
    }
}
