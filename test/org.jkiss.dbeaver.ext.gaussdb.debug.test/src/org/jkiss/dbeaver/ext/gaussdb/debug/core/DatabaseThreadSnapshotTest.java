/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.jkiss.dbeaver.debug.DBGSession;
import org.jkiss.dbeaver.debug.DBGStackFrame;
import org.jkiss.dbeaver.debug.core.model.DatabaseThread;
import org.jkiss.dbeaver.debug.core.model.IDatabaseDebugTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseThreadSnapshotTest {
    @Test
    void rebuildingStackReplacesRatherThanAppendsFrames() throws Exception {
        DatabaseThread thread = new DatabaseThread(mock(IDatabaseDebugTarget.class));
        List<DBGStackFrame> stack = List.of(mock(DBGStackFrame.class));
        thread.rebuildStack(stack);
        thread.rebuildStack(stack);
        assertEquals(1, thread.getStackFrames().length);
    }

    @Test
    void concurrentStackAndTopFrameRequestsShareOneSnapshot() throws Exception {
        IDatabaseDebugTarget target = mock(IDatabaseDebugTarget.class);
        DBGSession session = mock(DBGSession.class);
        when(target.isSuspended()).thenReturn(true);
        when(target.getSession()).thenReturn(session);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(session.getStack()).thenAnswer(invocation -> {
            reading.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return List.of(mock(DBGStackFrame.class));
        });
        DatabaseThread thread = new DatabaseThread(target);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var frames = pool.submit(thread::getStackFrames);
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            var top = pool.submit(thread::getTopStackFrame);
            release.countDown();
            assertEquals(1, frames.get(5, TimeUnit.SECONDS).length);
            assertSame(frames.get()[0], top.get(5, TimeUnit.SECONDS));
            verify(session, times(1)).getStack();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
