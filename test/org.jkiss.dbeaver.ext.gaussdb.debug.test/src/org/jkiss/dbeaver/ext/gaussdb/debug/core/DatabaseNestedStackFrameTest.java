/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IVariable;
import org.jkiss.dbeaver.debug.DBGSession;
import org.jkiss.dbeaver.debug.DBGStackFrame;
import org.jkiss.dbeaver.debug.core.model.DatabaseStackFrame;
import org.jkiss.dbeaver.debug.core.model.DatabaseThread;
import org.jkiss.dbeaver.debug.core.model.IDatabaseDebugTarget;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugStackFrame;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugVariable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DatabaseNestedStackFrameTest {
    @Test
    void threePackageFramesKeepTheirOwnSourceAndVariableSnapshot() throws Exception {
        IDatabaseDebugTarget target = mock(IDatabaseDebugTarget.class);
        DBGSession session = mock(DBGSession.class);
        when(target.isSuspended()).thenReturn(true);
        when(target.getSession()).thenReturn(session);

        List<DBGStackFrame> protocolFrames = List.of(
            new GaussDBDebugStackFrame(0, "pkg_chain.leaf", 17, 8103L, "RETURN leaf_value"),
            new GaussDBDebugStackFrame(1, "pkg_chain.middle", 11, 8102L, "middle_value := leaf()"),
            new GaussDBDebugStackFrame(2, "pkg_chain.entry", 6, 8101L, "entry_value := middle()")
        );
        doReturn(protocolFrames).when(session).getStack();
        when(session.getVariables(any())).thenAnswer(invocation -> {
            DBGStackFrame frame = invocation.getArgument(0);
            return List.of(new GaussDBDebugVariable(
                "depth_value", "integer", Integer.toString((frame.getLevel() + 1) * 100),
                "pkg_chain", false, frame.getLevel()));
        });

        DatabaseThread thread = new DatabaseThread(target);
        IStackFrame[] frames = thread.getStackFrames();

        assertEquals(3, frames.length);
        assertSame(frames[0], thread.getTopStackFrame());
        long[] expectedOids = {8103L, 8102L, 8101L};
        int[] expectedLines = {17, 11, 6};
        String[] expectedValues = {"100", "200", "300"};
        for (int index = 0; index < frames.length; index++) {
            DatabaseStackFrame frame = assertInstanceOf(DatabaseStackFrame.class, frames[index]);
            assertEquals(expectedOids[index], frame.getSourceIdentifier());
            assertEquals(expectedLines[index], frame.getLineNumber());
            IVariable[] variables = frame.getVariables();
            assertEquals(1, variables.length);
            assertEquals("depth_value", variables[0].getName());
            assertEquals(expectedValues[index], variables[0].getValue().getValueString());
            assertEquals(index == 0, variables[0].supportsValueModification());
        }

        verify(session).getStack();
        for (DBGStackFrame protocolFrame : protocolFrames) {
            verify(session).getVariables(same(protocolFrame));
        }
        verifyNoMoreInteractions(session);
    }
}
