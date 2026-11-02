/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.eclipse.core.resources.IMarker;
import org.eclipse.debug.core.model.IBreakpoint;
import org.jkiss.dbeaver.debug.DBGBreakpointDescriptor;
import org.jkiss.dbeaver.debug.core.breakpoints.DatabaseBreakpoint;
import org.jkiss.dbeaver.debug.core.breakpoints.DatabaseLineBreakpoint;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugBreakpointDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseBreakpointIdentityTest {
    @Test
    void sameObjectAfterNavigatorPathChangeMatchesInsteadOfCreatingSecondMarker() throws Exception {
        var stored = marker("connection-a", "old/navigation/path", "odb", 42, 6);
        assertEquals(0, java.util.Arrays.stream(new IBreakpoint[]{stored})
            .filter(bp -> "new/navigation/path".equals(uncheckedPath((DatabaseLineBreakpoint) bp))).count(),
            "The old path-only algorithm misses this restored marker");
        assertArrayEquals(new IBreakpoint[]{stored}, find(new IBreakpoint[]{stored}, "connection-a", "new/navigation/path", "odb", 42, 6));
    }

    @Test
    void selectsAllRestoredDuplicatesIncludingDisabledMarkersForOneToggle() throws Exception {
        var first = marker("connection-a", "path", "odb", 42, 6);
        var duplicate = marker("connection-a", "path", "odb", 42, 6);
        when(duplicate.isEnabled()).thenReturn(false);
        var otherLine = marker("connection-a", "path", "odb", 42, 8);
        assertArrayEquals(new IBreakpoint[]{first, duplicate},
            find(new IBreakpoint[]{first, duplicate, otherLine}, "connection-a", "path", "odb", 42, 6));
    }

    @Test
    void sameOidAndLineOnAnotherConnectionIsNotRemoved() throws Exception {
        var other = marker("connection-b", "same-path", "odb", 42, 6);
        assertEquals(0, find(new IBreakpoint[]{other}, "connection-a", "same-path", "odb", 42, 6).length);
    }

    @Test
    void sameOidInAnotherDatabaseDoesNotMatchByIdentity() throws Exception {
        var other = marker("connection-a", "other-db/path", "other_db", 42, 6);
        assertEquals(0, find(new IBreakpoint[]{other}, "connection-a", "odb/path", "odb", 42, 6).length);
    }

    @Test
    void savedDatabaseMismatchRejectsEvenAnIdenticalLegacyPath() throws Exception {
        var other = marker("connection-a", "same-path", "another_db", 42, 6);
        assertEquals(0, find(new IBreakpoint[]{other}, "connection-a", "same-path", "odb", 42, 6).length);
    }

    @Test
    void sameDisplayNameWithAnotherOidDoesNotMatchByIdentity() throws Exception {
        var other = marker("connection-a", "another-overload/path", "odb", 99, 6);
        assertEquals(0, find(new IBreakpoint[]{other}, "connection-a", "new/path", "odb", 42, 6).length);
    }

    @Test
    void sameObjectAtAnotherLineRemainsIndependent() throws Exception {
        var other = marker("connection-a", "old/path", "odb", 42, 8);
        assertEquals(0, find(new IBreakpoint[]{other}, "connection-a", "new/path", "odb", 42, 6).length);
    }

    @Test
    void markerAtSamePathCanStillBeRemovedAfterRoutineRecreation() throws Exception {
        var old = marker("connection-a", "path", "odb", 40, 6);
        assertArrayEquals(new IBreakpoint[]{old}, find(new IBreakpoint[]{old}, "connection-a", "path", "odb", 42, 6));
    }

    @Test
    void unknownDatabaseOrInvalidOidNeverProvidesStableIdentity() {
        var values = new GaussDBDebugBreakpointDescriptor(42, 6, "odb").toMap();
        assertFalse(new GaussDBDebugBreakpointDescriptor(42, 6).matchesObject("current/path", "saved/path", values));
        assertFalse(new GaussDBDebugBreakpointDescriptor(42, 6, "").matchesObject("current/path", "saved/path", values));
        assertFalse(new GaussDBDebugBreakpointDescriptor(0, 6, "odb")
            .matchesObject("current/path", "saved/path", new GaussDBDebugBreakpointDescriptor(0, 6, "odb").toMap()));
    }

    @Test
    void missingLegacyIdentityUsesOnlyExactNavigatorPath() throws Exception {
        var old = marker("connection-a", "old/path", null, 42, 6);
        assertEquals(0, find(new IBreakpoint[]{old}, "connection-a", "new/path", "odb", 42, 6).length);
        assertEquals(1, find(new IBreakpoint[]{old}, "connection-a", "old/path", "odb", 42, 6).length);
    }

    @Test
    void otherProvidersRetainPathBasedMatching() throws Exception {
        DBGBreakpointDescriptor otherProvider = Map::of;
        var old = marker("connection-a", "old/path", "odb", 42, 6);
        assertEquals(0, DatabaseBreakpoint.findMatchingBreakpoints(new IBreakpoint[]{old}, "connection-a", "new/path", 6, otherProvider).length);
        assertEquals(1, DatabaseBreakpoint.findMatchingBreakpoints(new IBreakpoint[]{old}, "connection-a", "old/path", 6, otherProvider).length);
    }

    @Test
    void existingMarkerCanBeDeletedEvenIfObjectNoLongerSupportsNewBreakpoints() throws Exception {
        var old = marker("connection-a", "path", "odb", 42, 6);
        assertEquals(1, DatabaseBreakpoint.findMatchingBreakpoints(new IBreakpoint[]{old}, "connection-a", "path", 6, null).length);
    }

    @Test
    void differentConnectionsHaveDistinctLabelsEvenWithIdenticalRoutineAndLine() throws Exception {
        var first = marker("connection-a", "path", "odb", 42, 6);
        var other = marker("connection-b", "path", "odb", 42, 6);
        assertEquals("[connection-a] public.same_name - [line:6]", first.getDisplayLabel());
        assertNotEquals(first.getDisplayLabel(), other.getDisplayLabel());
    }

    @Test
    void labelWithoutSavedConnectionRetainsLegacyText() throws Exception {
        var old = marker(null, "path", "odb", 42, 6);
        assertEquals("public.same_name - [line:6]", old.getDisplayLabel());
    }

    private static IBreakpoint[] find(IBreakpoint[] stored, String connection, String path, String database, long oid, int line)
        throws Exception {
        return DatabaseBreakpoint.findMatchingBreakpoints(stored, connection, path, line,
            new GaussDBDebugBreakpointDescriptor(oid, -1, database));
    }

    private static DatabaseLineBreakpoint marker(String connection, String path, String database, long oid, int line)
        throws Exception {
        var breakpoint = mock(DatabaseLineBreakpoint.class);
        var marker = mock(IMarker.class);
        when(breakpoint.getDatasourceId()).thenReturn(connection);
        when(breakpoint.getNodePath()).thenReturn(path);
        when(breakpoint.getLineNumber()).thenReturn(line);
        when(breakpoint.getObjectName()).thenReturn("public.same_name");
        when(breakpoint.getMarker()).thenReturn(marker);
        when(breakpoint.getDisplayLabel()).thenCallRealMethod();
        when(marker.exists()).thenReturn(true);
        when(marker.getAttributes()).thenReturn(new GaussDBDebugBreakpointDescriptor(oid, line, database).toMap());
        return breakpoint;
    }

    private static String uncheckedPath(DatabaseLineBreakpoint breakpoint) {
        try {
            return breakpoint.getNodePath();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
