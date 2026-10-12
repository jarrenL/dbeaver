/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBDebugControllerTest {
    @Test
    void failedAttachAlwaysClosesThePartiallyStartedSessionAndPreservesCleanupFailure() throws Exception {
        GaussDBDebugSession session = mock(GaussDBDebugSession.class);
        DBRProgressMonitor monitor = mock(DBRProgressMonitor.class);
        DBGException attachFailure = new DBGException("attach failed");
        DBGException cleanupFailure = new DBGException("cleanup failed");
        doThrow(attachFailure).when(session).attach(monitor);
        doThrow(cleanupFailure).when(session).closeSession(monitor);
        GaussDBDebugController controller = new StubController(session);

        DBGException actual = assertThrows(DBGException.class,
            () -> controller.createSession(monitor, Map.of()));

        assertSame(attachFailure, actual);
        assertArrayEquals(new Throwable[]{cleanupFailure}, actual.getSuppressed());
        verify(session).closeSession(monitor);
    }

    private static final class StubController extends GaussDBDebugController {
        private final GaussDBDebugSession session;

        private StubController(GaussDBDebugSession session) {
            super(mock(DBPDataSourceContainer.class), Map.of());
            this.session = session;
        }

        @Override
        GaussDBDebugSession newSession(DBRProgressMonitor monitor, Map<String, Object> configuration) {
            return session;
        }
    }
}
