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

import org.jkiss.dbeaver.ext.postgresql.PostgreConstants;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.impl.net.SSLHandlerTrustStoreImpl;
import org.jkiss.dbeaver.model.net.DBWHandlerConfiguration;
import org.jkiss.dbeaver.model.net.DBWHandlerDescriptor;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreSSLPropertiesTest extends DBeaverUnitTest {
    private Map<String, String> properties(Map<String, Object> settings, boolean enabled) throws Exception {
        var container = configureTestContainer("postgresql");
        var descriptor = mock(DBWHandlerDescriptor.class);
        when(descriptor.getId()).thenReturn(PostgreConstants.HANDLER_SSL);
        var handler = new DBWHandlerConfiguration(descriptor, container);
        handler.setEnabled(enabled);
        handler.setProperties(settings);
        var configuration = container.getActualConnectionConfiguration();
        configuration.updateHandler(handler);
        assertSame(handler, configuration.getHandler(PostgreConstants.HANDLER_SSL));
        var source = new PostgreDataSource(container, "SSL configuration test", "postgres");
        return source.getInternalConnectionProperties(monitor, container.getDriver(),
            mock(JDBCExecutionContext.class), "SSL property test", configuration);
    }

    @Test
    void legacyCertificatePathsAndStrictModeReachJdbcUnchanged() throws Exception {
        var result = properties(Map.of(
            PostgreConstants.PROP_SSL_ROOT_CERT, "/test certs/CA 中文.pem",
            PostgreConstants.PROP_SSL_CLIENT_CERT, "/test certs/client.pem",
            PostgreConstants.PROP_SSL_CLIENT_KEY, "/test certs/client.pk8",
            PostgreConstants.PROP_SSL_MODE, "verify-full"), true);
        assertEquals("true", result.get("ssl"));
        assertEquals("verify-full", result.get("sslmode"));
        assertEquals("/test certs/CA 中文.pem", result.get("sslrootcert"));
        assertEquals("/test certs/client.pem", result.get("sslcert"));
        assertEquals("/test certs/client.pk8", result.get("sslkey"));
        assertNotNull(result.get("sslpasswordcallback"));
    }

    @Test
    void modernCertificatePropertiesTakePrecedenceOverLegacyPaths() throws Exception {
        var result = properties(Map.of(
            SSLHandlerTrustStoreImpl.PROP_SSL_METHOD, "file",
            SSLHandlerTrustStoreImpl.PROP_SSL_CA_CERT, "/new/ca.pem",
            SSLHandlerTrustStoreImpl.PROP_SSL_CLIENT_CERT, "/new/client.pem",
            SSLHandlerTrustStoreImpl.PROP_SSL_CLIENT_KEY, "/new/client.pk8",
            PostgreConstants.PROP_SSL_ROOT_CERT, "/old/ca.pem",
            PostgreConstants.PROP_SSL_CLIENT_CERT, "/old/client.pem",
            PostgreConstants.PROP_SSL_CLIENT_KEY, "/old/client.pk8",
            PostgreConstants.PROP_SSL_MODE, "verify-ca",
            PostgreConstants.PROP_SSL_FACTORY, "example.TestSSLFactory"), true);
        assertEquals("/new/ca.pem", result.get("sslrootcert"));
        assertEquals("/new/client.pem", result.get("sslcert"));
        assertEquals("/new/client.pk8", result.get("sslkey"));
        assertEquals("verify-ca", result.get("sslmode"));
        assertEquals("example.TestSSLFactory", result.get("sslfactory"));
        assertFalse(result.containsValue("/old/ca.pem"));
    }

    @Test
    void emptyOptionalPropertiesDoNotOverrideJdbcDefaultsWithEmptyValues() throws Exception {
        var result = properties(Map.of(
            PostgreConstants.PROP_SSL_ROOT_CERT, "",
            PostgreConstants.PROP_SSL_CLIENT_CERT, "",
            PostgreConstants.PROP_SSL_CLIENT_KEY, "",
            PostgreConstants.PROP_SSL_MODE, "",
            PostgreConstants.PROP_SSL_FACTORY, ""), true);
        assertEquals("true", result.get("ssl"));
        for (String key : List.of("sslrootcert", "sslcert", "sslkey", "sslmode", "sslfactory")) {
            assertFalse(result.containsKey(key), key);
        }
    }

    @Test
    void disabledHandlerDoesNotLeakSavedCertificatePathsOrFactoryIntoJdbc() throws Exception {
        var result = properties(Map.of(
            PostgreConstants.PROP_SSL_ROOT_CERT, "/saved/ca.pem",
            PostgreConstants.PROP_SSL_CLIENT_CERT, "/saved/client.pem",
            PostgreConstants.PROP_SSL_CLIENT_KEY, "/saved/client.pk8",
            PostgreConstants.PROP_SSL_FACTORY, "example.TestSSLFactory"), false);
        for (String key : List.of("sslrootcert", "sslcert", "sslkey", "sslfactory", "sslpasswordcallback")) {
            assertFalse(result.containsKey(key), key);
        }
    }
}
