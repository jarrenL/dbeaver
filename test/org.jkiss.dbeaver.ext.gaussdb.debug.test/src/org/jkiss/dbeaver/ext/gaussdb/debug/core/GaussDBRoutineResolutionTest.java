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
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.eclipse.core.runtime.Platform;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.debug.DBGLaunchGuard;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDataSource;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDatabase;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBSchema;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBRoutineResolutionTest {
    @Test
    void initializationInProgressDoesNotTryToConnectOrReadPartialMetadata() throws Exception {
        var fixture = new Fixture();
        when(fixture.container.isConnecting()).thenReturn(true);

        DBException error = assertThrows(DBException.class, () -> fixture.resolve());

        assertTrue(error.getMessage().contains("in progress"));
        assertTrue(error.getMessage().contains("Wait"));
        verify(fixture.container, never()).connect(any(), anyBoolean(), anyBoolean());
        verify(fixture.container, never()).getDataSource();
    }

    @Test
    void failedConnectDoesNotDereferenceMissingDataSource() throws Exception {
        var fixture = new Fixture();
        when(fixture.container.connect(fixture.monitor, true, true)).thenReturn(false);

        DBException error = assertThrows(DBException.class, () -> fixture.resolve());

        assertTrue(error.getMessage().contains("not ready"));
        verify(fixture.container, never()).getDataSource();
    }

    @Test
    void connectedContainerWithNullDataSourceReportsReadinessInsteadOfNullPointer() {
        var fixture = new Fixture();
        when(fixture.container.isConnected()).thenReturn(true);

        DBException error = assertThrows(DBException.class, () -> fixture.resolve());

        assertTrue(error.getMessage().contains("connection initialization"));
    }

    @Test
    void connectedNonGaussDataSourceReportsSelectedConnectionInsteadOfClassCast() {
        var fixture = new Fixture();
        var otherSource = mock(DBPDataSource.class);
        when(fixture.container.isConnected()).thenReturn(true);
        when(fixture.container.getDataSource()).thenReturn(otherSource);

        DBException error = assertThrows(DBException.class, () -> fixture.resolve());

        assertTrue(error.getMessage().contains("not a GaussDB data source"));
        assertTrue(error.getMessage().contains("GaussDB race test"));
        verifyNoInteractions(otherSource);
    }

    @Test
    void connectFalseMayProceedWhenAnotherJobAlreadyCompletedTheConnection() throws Exception {
        var fixture = new Fixture();
        var source = mock(GaussDBDataSource.class);
        var database = mock(GaussDBDatabase.class);
        var schema = mock(GaussDBSchema.class, RETURNS_DEEP_STUBS);
        var routine = mock(GaussDBProcedure.class);
        when(fixture.container.isConnected()).thenReturn(false, true);
        when(fixture.container.connect(fixture.monitor, true, true)).thenReturn(false);
        when(fixture.container.getDataSource()).thenReturn(source);
        when(source.getDatabase("test")).thenReturn(database);
        when(database.getSchema(fixture.monitor, "test_schema")).thenReturn(schema);
        when(routine.getObjectId()).thenReturn(42L);
        when(schema.getGaussDBProceduresCache().getAllObjects(fixture.monitor, schema)).thenReturn(List.of(routine));

        assertSame(routine, fixture.resolve());

        verify(fixture.container).connect(fixture.monitor, true, true);
    }

    @Test
    void canceledResolutionDoesNotStartAConnection() throws Exception {
        var fixture = new Fixture();
        when(fixture.monitor.isCanceled()).thenReturn(true);

        DBException error = assertThrows(DBException.class, () -> fixture.resolve());

        assertTrue(error.getMessage().contains("canceled"));
        verifyNoInteractions(fixture.container);
    }

    @Test
    void canceledConnectionFailureMakesLaunchGuardReturnWithoutAnErrorDialog() throws Exception {
        var fixture = new Fixture();
        var canceled = new AtomicBoolean();
        when(fixture.monitor.isCanceled()).thenAnswer(invocation -> canceled.get());
        when(fixture.container.connect(fixture.monitor, true, true)).thenAnswer(invocation -> {
            canceled.set(true);
            throw new DBException("Connection dialog was canceled");
        });

        assertFalse(launchGuard().prepareLaunch(fixture.container, new HashMap<>(fixture.configuration), fixture.monitor));

        verify(fixture.container, never()).getDataSource();
    }

    @Test
    void nonCanceledConnectionFailureKeepsTheUnderlyingDiagnostic() throws Exception {
        var fixture = new Fixture();
        var failure = new DBException("Authentication failed for the selected connection");
        when(fixture.container.connect(fixture.monitor, true, true)).thenThrow(failure);

        DBGException error = assertThrows(DBGException.class, () -> {
            launchGuard().prepareLaunch(fixture.container, new HashMap<>(fixture.configuration), fixture.monitor);
        });

        assertSame(failure, error.getCause());
        verify(fixture.container, never()).getDataSource();
    }

    private static DBGLaunchGuard launchGuard() throws Exception {
        var bundle = Platform.getBundle("org.jkiss.dbeaver.ext.gaussdb.debug.ui");
        assertNotNull(bundle, "The GaussDB debug UI bundle must be available to exercise the real launch guard");
        return (DBGLaunchGuard) bundle.loadClass("org.jkiss.dbeaver.ext.gaussdb.debug.ui.internal.GaussDBDebugLaunchGuard")
            .getConstructor().newInstance();
    }

    private static final class Fixture {
        final DBRProgressMonitor monitor = mock(DBRProgressMonitor.class);
        final DBPDataSourceContainer container = mock(DBPDataSourceContainer.class);
        final Map<String, Object> configuration = Map.of(
            GaussDBDebugConstants.ATTR_DATABASE_NAME, "test",
            GaussDBDebugConstants.ATTR_SCHEMA_NAME, "test_schema",
            GaussDBDebugConstants.ATTR_ROUTINE_OID, "42"
        );

        Fixture() {
            when(container.getName()).thenReturn("GaussDB race test");
        }

        GaussDBProcedure resolve() throws DBException {
            return GaussDBDebugCore.resolveRoutine(monitor, container, configuration);
        }
    }
}
