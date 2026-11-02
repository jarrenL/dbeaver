/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.junit.jupiter.api.Test;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBExecutionContextTest {
    @ParameterizedTest
    @ValueSource(strings = {"gaussdb-jdbc", "gaussdb-jdbc-m", "gaussdb-jdbc-native"})
    void registeredDriversSerializeStatementsOnEachConnection(String driverId) {
        for (var provider : Platform.getExtensionRegistry().getConfigurationElementsFor("org.jkiss.dbeaver.dataSourceProvider")) {
            if ("gaussdb".equals(provider.getAttribute("id"))) {
                for (var drivers : provider.getChildren("drivers")) {
                    for (var driver : drivers.getChildren("driver")) {
                        if (driverId.equals(driver.getAttribute("id"))) {
                            assertEquals("false", driver.getAttribute("threadSafe"));
                            return;
                        }
                    }
                }
            }
        }
        fail("GaussDB driver registration missing: " + driverId);
    }

    @Test
    void nestedMetadataLookupIsReentrantButOtherThreadsWaitUntilStatementCloses() throws Exception {
        GaussDBExecutionContext context = serializedContext();
        DBCStatement outer = mock(DBCStatement.class);
        DBCStatement nested = mock(DBCStatement.class);
        DBCStatement concurrent = mock(DBCStatement.class);
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        context.lockStatementExecution(outer);
        try {
            context.lockStatementExecution(nested);
            context.unlockStatementExecution(nested);
            Future<Boolean> acquired = worker.submit(() -> {
                started.countDown();
                context.lockStatementExecution(concurrent);
                try {
                    return true;
                } finally {
                    context.unlockStatementExecution(concurrent);
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> acquired.get(100, TimeUnit.MILLISECONDS));
            context.unlockStatementExecution(outer);
            assertTrue(acquired.get(2, TimeUnit.SECONDS));
        } finally {
            context.unlockStatementExecution(outer);
            worker.shutdownNow();
        }
    }

    @Test
    void separateConnectionsKeepIndependentExecutionLocks() throws Exception {
        GaussDBExecutionContext first = serializedContext();
        GaussDBExecutionContext second = serializedContext();
        DBCStatement firstStatement = mock(DBCStatement.class);
        DBCStatement secondStatement = mock(DBCStatement.class);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        first.lockStatementExecution(firstStatement);
        try {
            assertTrue(worker.submit(() -> {
                second.lockStatementExecution(secondStatement);
                try {
                    return true;
                } finally {
                    second.unlockStatementExecution(secondStatement);
                }
            }).get(2, TimeUnit.SECONDS));
        } finally {
            first.unlockStatementExecution(firstStatement);
            worker.shutdownNow();
        }
    }

    private static GaussDBExecutionContext serializedContext() {
        GaussDBDatabase database = mock(GaussDBDatabase.class);
        GaussDBDataSource source = mock(GaussDBDataSource.class, RETURNS_DEEP_STUBS);
        when(database.getDataSource()).thenReturn(source);
        when(source.getContainer().getDriver().isThreadSafeDriver()).thenReturn(false);
        return new GaussDBExecutionContext(database, "Metadata test");
    }

    @Test
    void defaultsUseSessionIdentityFunctionSupportedInMMode() {
        GaussDBExecutionContext context = mock(GaussDBExecutionContext.class, CALLS_REAL_METHODS);
        assertEquals("SELECT current_schema(),pg_catalog.session_user()", context.getContextDefaultsQuery());
    }

    @Test
    void dataSourceCreatesGaussDBContextForEachConnectionPurpose() {
        GaussDBDataSource source = mock(GaussDBDataSource.class, CALLS_REAL_METHODS);
        GaussDBDatabase database = mock(GaussDBDatabase.class);
        DBPDataSourceContainer container = mock(DBPDataSourceContainer.class, RETURNS_DEEP_STUBS);
        when(database.getDataSource()).thenReturn(source);
        when(source.getContainer()).thenReturn(container);
        doReturn("GaussDB context test").when(source).getName();
        when(container.getDriver().isThreadSafeDriver()).thenReturn(true);
        for (String purpose : new String[]{"Main", "Metadata"}) {
            GaussDBExecutionContext context = source.createExecutionContext(database, purpose);
            assertSame(database, context.getDefaultCatalog());
            assertSame(source, context.getDataSource());
            assertEquals(purpose, context.getContextName());
            assertEquals("SELECT current_schema(),pg_catalog.session_user()", context.getContextDefaultsQuery());
        }
    }
}
