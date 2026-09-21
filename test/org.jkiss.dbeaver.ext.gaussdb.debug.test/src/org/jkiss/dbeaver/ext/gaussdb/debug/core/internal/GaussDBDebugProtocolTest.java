/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core.internal;

import org.eclipse.core.resources.IMarker;
import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugConstants;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GaussDBDebugProtocolTest {
    @Test
    void markerAdapterChecksActualLanguageIncludingMMode() throws Exception {
        var routine = org.mockito.Mockito.mock(org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure.class,
            org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(routine.getDataSource().getContainer().getDriver().getProviderId()).thenReturn("gaussdb");
        org.mockito.Mockito.when(routine.isPersisted()).thenReturn(true);
        org.mockito.Mockito.when(routine.getObjectId()).thenReturn(42L);
        org.mockito.Mockito.when(routine.getDatabase().getName()).thenReturn("db");
        var adapter = new GaussDBBreakpointAdapterFactory();
        assertNull(adapter.getAdapter(routine, org.jkiss.dbeaver.debug.DBGBreakpointDescriptor.class));
        org.mockito.Mockito.when(routine.getDatabase().getCompatibility())
            .thenReturn(org.jkiss.dbeaver.ext.gaussdb.model.DBCompatibilityEnum.M);
        assertNull(adapter.getAdapter(routine, org.jkiss.dbeaver.debug.DBGBreakpointDescriptor.class));
        var language = org.mockito.Mockito.mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreLanguage.class);
        org.mockito.Mockito.when(routine.getLanguage(org.mockito.ArgumentMatchers.any())).thenReturn(language);
        org.mockito.Mockito.when(language.getName()).thenReturn("sql");
        assertNull(adapter.getAdapter(routine, org.jkiss.dbeaver.debug.DBGBreakpointDescriptor.class));
        org.mockito.Mockito.when(language.getName()).thenReturn("plpgsql");
        assertNotNull(adapter.getAdapter(routine, org.jkiss.dbeaver.debug.DBGBreakpointDescriptor.class));
        org.mockito.Mockito.when(routine.getDatabase().getCompatibility())
            .thenReturn(org.jkiss.dbeaver.ext.gaussdb.model.DBCompatibilityEnum.POSTGRES);
        assertNotNull(adapter.getAdapter(routine, org.jkiss.dbeaver.debug.DBGBreakpointDescriptor.class));
        org.mockito.Mockito.when(routine.isPersisted()).thenReturn(false);
        assertNull(adapter.getAdapter(routine, org.jkiss.dbeaver.debug.DBGBreakpointDescriptor.class));
    }

    @Test
    void breakpointRoutingRequiresExactDatabaseIdentity() {
        var controller = new GaussDBDebugController(null, Map.of(GaussDBDebugConstants.ATTR_DATABASE_NAME, "MixedDb"));
        var matching = new GaussDBDebugBreakpointDescriptor(42, 4, "MixedDb").toMap();
        assertNotNull(controller.describeBreakpoint(matching));
        assertEquals("MixedDb", GaussDBDebugBreakpointDescriptor.fromMap(matching).toMap()
            .get(GaussDBDebugConstants.ATTR_DATABASE_NAME));
        assertNull(controller.describeBreakpoint(new GaussDBDebugBreakpointDescriptor(42, 4, "other").toMap()));
        assertNull(controller.describeBreakpoint(new GaussDBDebugBreakpointDescriptor(42, 4, "mixeddb").toMap()));
        assertNull(controller.describeBreakpoint(new GaussDBDebugBreakpointDescriptor(42, 4).toMap()));
    }

    @Test
    void matchesOnlyTheExactDebuggerSignaturesUsedByTheClient() {
        assertTrue(GaussDBDebugCapabilityDetector.hasRequiredSignature("turn_on", "26"));
        assertTrue(GaussDBDebugCapabilityDetector.hasRequiredSignature("attach", " 25   23 "));
        assertTrue(GaussDBDebugCapabilityDetector.hasRequiredSignature("continue", ""));
        assertTrue(GaussDBDebugCapabilityDetector.hasRequiredSignature("set_var", "25 25"));
        assertTrue(GaussDBDebugCapabilityDetector.hasRequiredSignature("add_breakpoint", "26 23"));
        assertTrue(GaussDBDebugCapabilityDetector.hasRequiredSignature("add_breakpoint", "25 23"));
        assertFalse(GaussDBDebugCapabilityDetector.hasRequiredSignature("turn_on", "25"));
        assertFalse(GaussDBDebugCapabilityDetector.hasRequiredSignature("attach", "25"));
        assertFalse(GaussDBDebugCapabilityDetector.hasRequiredSignature("unknown", ""));
    }

    @Test
    void distinguishesMissingSignaturesFromMissingExecutePrivileges() {
        Set<String> required = GaussDBDebugCapabilityDetector.requiredFunctionNames();
        assertDoesNotThrow(() -> GaussDBDebugCapabilityDetector.assertComplete(required, required));

        DBGException denied = assertThrows(
            DBGException.class,
            () -> GaussDBDebugCapabilityDetector.assertComplete(required, Set.of())
        );
        assertTrue(denied.getMessage().contains("no EXECUTE privilege"));

        DBGException missing = assertThrows(
            DBGException.class,
            () -> GaussDBDebugCapabilityDetector.assertComplete(Set.of(), Set.of())
        );
        assertTrue(missing.getMessage().contains("missing or incompatible"));
    }

    @Test
    void recognizesOnlyServerExecutionFinishedMarker() {
        assertTrue(GaussDBDebugSession.isExecutionFinished("[EXECUTION FINISHED]"));
        assertTrue(GaussDBDebugSession.isExecutionFinished("  [EXECUTION FINISHED]  "));
        assertFalse(GaussDBDebugSession.isExecutionFinished("insert into t values (1)"));
        assertFalse(GaussDBDebugSession.isExecutionFinished(null));
    }

    @Test
    void preservesBreakpointIdentityAndEnablement() {
        GaussDBDebugBreakpointDescriptor descriptor = (GaussDBDebugBreakpointDescriptor)
            GaussDBDebugBreakpointDescriptor.fromMap(Map.of(
                GaussDBDebugConstants.ATTR_ROUTINE_OID, "16389",
                IMarker.LINE_NUMBER, 4,
                "enabled", false));

        assertEquals(16389, descriptor.getRoutineOid());
        assertEquals(4, descriptor.getLineNumber());
        assertFalse(descriptor.isEnabled());
        assertEquals("16389", descriptor.toMap().get(GaussDBDebugConstants.ATTR_ROUTINE_OID));
    }

    @Test
    void matchesDuplicateBreakpointByRoutineAndLineRegardlessOfServerNumber() {
        GaussDBDebugBreakpointDescriptor registered = new GaussDBDebugBreakpointDescriptor(16389, 4);
        registered.setServerId(0);

        assertSame(
            registered,
            GaussDBDebugSession.findMatchingBreakpoint(
                List.of(registered),
                new GaussDBDebugBreakpointDescriptor(16389, 4)
            )
        );
        assertNull(GaussDBDebugSession.findMatchingBreakpoint(
            List.of(registered),
            new GaussDBDebugBreakpointDescriptor(16389, 5)
        ));
        assertEquals(0, registered.getServerId());
    }

    @Test
    void keepsNewBreakpointsUnregisteredUntilServerConfirms() {
        // GaussDB 507 returns -1 (not NULL) with a warning when add_breakpoint hits
        // an existing line. Fresh descriptors must stay negative so that command
        // execution rejects them instead of corrupting server breakpoint state.
        GaussDBDebugBreakpointDescriptor fresh = new GaussDBDebugBreakpointDescriptor(16389, 4);

        assertTrue(fresh.getServerId() < 0);
        assertFalse(fresh.getServerId() >= 0);
    }

    @Test
    void constantsAreReadOnlyButNormalVariablesCanBeChanged() {
        GaussDBDebugVariable constant = new GaussDBDebugVariable("c", "int4", "1", null, true, 0);
        GaussDBDebugVariable variable = new GaussDBDebugVariable("x", "int4", "1", null, false, 0);

        assertTrue(constant.isReadOnly());
        assertFalse(variable.isReadOnly());
        variable.setValue("2");
        assertEquals("2", variable.getVal());
    }
}
