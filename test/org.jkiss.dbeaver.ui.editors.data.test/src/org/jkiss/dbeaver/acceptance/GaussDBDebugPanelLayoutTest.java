/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.acceptance;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBDebugPanelLayoutTest {
    private static Class<?> panelType() throws Exception {
        return Platform.getBundle("org.jkiss.dbeaver.ext.gaussdb.debug.ui").loadClass(
            "org.jkiss.dbeaver.ext.gaussdb.debug.ui.internal.GaussDBDebugPanelRoutine");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void consentVisibilityUsesTitledHostWhenClientHasNoLayoutData(boolean packageMember) throws Exception {
        var type = panelType();
        var panel = type.getConstructor().newInstance();
        var client = mock(Composite.class);
        var host = mock(Composite.class);
        var parent = mock(Composite.class);
        var description = mock(Label.class);
        var consent = mock(Button.class);
        var layout = new GridData(GridData.FILL_HORIZONTAL);
        when(client.getParent()).thenReturn(host);
        when(host.getParent()).thenReturn(parent);
        when(host.getLayoutData()).thenReturn(layout);
        set(type, panel, "sessionSettingsGroup", client);
        set(type, panel, "sessionSettingsDescription", description);
        set(type, panel, "sessionSettingsConsent", consent);
        if (packageMember) {
            var routineType = Platform.getBundle("org.jkiss.dbeaver.ext.gaussdb").loadClass(
                "org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure");
            var routine = mock(routineType);
            when(routineType.getMethod("getInvocationSchema").invoke(routine)).thenReturn("package_schema");
            set(type, panel, "selectedRoutine", routine);
        }
        update(type, panel);
        assertEquals(!packageMember, layout.exclude);
        verify(host).setVisible(packageMember);
        verify(client, never()).getLayoutData();
        verify(client, never()).setVisible(anyBoolean());
        verify(consent).setSelection(false);
        verify(parent).layout(true, true);
        if (packageMember) {
            // Switching to an empty/stale selection must hide the whole titled section again.
            set(type, panel, "selectedRoutine", null);
            update(type, panel);
            assertTrue(layout.exclude);
            verify(host).setVisible(false);
        }
    }

    @Test
    void absentOrDisposedPanelDoesNotAccessControls() throws Exception {
        var type = panelType();
        var panel = type.getConstructor().newInstance();
        update(type, panel);
        var client = mock(Composite.class);
        when(client.isDisposed()).thenReturn(true);
        set(type, panel, "sessionSettingsGroup", client);
        update(type, panel);
        verify(client, never()).getParent();
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void update(Class<?> type, Object panel) throws Exception {
        var method = type.getDeclaredMethod("updateSessionSettingsControls");
        method.setAccessible(true);
        method.invoke(panel);
    }
}
