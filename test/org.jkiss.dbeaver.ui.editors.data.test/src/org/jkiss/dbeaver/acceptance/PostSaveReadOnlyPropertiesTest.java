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
package org.jkiss.dbeaver.acceptance;

import org.jkiss.dbeaver.model.DBPStatefulObject;
import org.jkiss.dbeaver.model.preferences.DBPPropertyDescriptor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.editors.entity.properties.ObjectPropertiesEditor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostSaveReadOnlyPropertiesTest {
    private static final String FORM = "org.jkiss.dbeaver.ui.editors.entity.properties.TabbedFolderPageForm";

    @Test
    void successfulSaveRefreshesOnlyStatefulPropertyDisplay() throws Exception {
        for (boolean stateful : List.of(false, true)) {
            var editor = mock(ObjectPropertiesEditor.class, CALLS_REAL_METHODS);
            var object = stateful ? mock(DBSObject.class, withSettings().extraInterfaces(DBPStatefulObject.class))
                : mock(DBSObject.class);
            doReturn(object).when(editor).getDatabaseObject();
            var panel = mock(Class.forName(FORM));
            set(editor, "nestedSaveable", List.of());
            set(editor, "propertiesPanel", panel);
            editor.runPostSaveCommands(Map.of());
            var calls = mockingDetails(panel).getInvocations();
            assertEquals(stateful ? 1 : 0, calls.size());
            if (stateful) {
                assertEquals("refreshReadOnlyProperties", calls.iterator().next().getMethod().getName());
            }
            verifyNoInteractions(object);
        }
    }

    @Test
    void absentPanelDoesNotReadObjectOrStartRefresh() throws Exception {
        var editor = mock(ObjectPropertiesEditor.class, CALLS_REAL_METHODS);
        set(editor, "nestedSaveable", List.of());
        editor.runPostSaveCommands(Map.of());
        verify(editor, never()).getDatabaseObject();
    }

    @Test
    void readOnlySelectionPreservesOrderAndExcludesEditableValues() throws Exception {
        Object object = new Object();
        var editable = mock(DBPPropertyDescriptor.class);
        var spec = mock(DBPPropertyDescriptor.class);
        var body = mock(DBPPropertyDescriptor.class);
        when(editable.isEditable(object)).thenReturn(true);
        var method = Class.forName(FORM).getDeclaredMethod("readOnlyProperties", List.class, Object.class);
        method.setAccessible(true);
        var input = List.of(spec, editable, body);
        assertEquals(List.of(spec, body), method.invoke(null, input, object));
        assertEquals(List.of(spec, editable, body), input);
        assertEquals(List.of(), method.invoke(null, List.of(editable), object));
        assertEquals(List.of(), method.invoke(null, List.of(), object));
    }

    @Test
    void uninitializedFormDoesNotScheduleLoading() throws Exception {
        var type = Class.forName(FORM);
        var form = mock(type, CALLS_REAL_METHODS);
        var method = type.getDeclaredMethod("refreshReadOnlyProperties");
        method.setAccessible(true);
        assertDoesNotThrow(() -> method.invoke(form));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = ObjectPropertiesEditor.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
