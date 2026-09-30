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
import org.jkiss.dbeaver.model.preferences.DBPPropertySource;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.runtime.DBRRunnableParametrized;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.ui.LoadingJob;
import org.jkiss.dbeaver.ui.controls.CustomFormEditor;
import org.jkiss.dbeaver.ui.controls.ObjectEditorPageControl;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.ui.editors.entity.properties.ObjectPropertiesEditor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;

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

    @Test
    void disposedOwnerDoesNotReadEditorsOrInput() throws Exception {
        var source = mock(DBPPropertySource.class);
        var owner = mock(ObjectEditorPageControl.class);
        var editors = mock(CustomFormEditor.class);
        var input = mock(IDatabaseEditorInput.class);
        doReturn(true).when(owner).isDisposed();
        invokeForm(source, owner, editors, input);
        verify(owner).isDisposed();
        verifyNoMoreInteractions(owner);
        verifyNoInteractions(source, editors, input);
    }

    @Test
    void absentEditorsDoNotReloadPropertySource() throws Exception {
        var source = mock(DBPPropertySource.class);
        var owner = mock(ObjectEditorPageControl.class);
        var editors = mock(CustomFormEditor.class);
        var input = mock(IDatabaseEditorInput.class);
        invokeForm(source, owner, editors, input);
        verify(owner).isDisposed();
        verify(editors).hasEditors();
        verifyNoMoreInteractions(owner, editors);
        verifyNoInteractions(source, input);
    }

    @Test
    void entirelyEditableSelectionDoesNotStartValueLoad() throws Exception {
        var source = mock(DBPPropertySource.class);
        var owner = mock(ObjectEditorPageControl.class);
        var editors = mock(CustomFormEditor.class);
        var input = mock(IDatabaseEditorInput.class);
        var fresh = mock(DBPPropertySource.class);
        var property = mock(DBPPropertyDescriptor.class);
        var object = new Object();
        doReturn(true).when(editors).hasEditors();
        doReturn(fresh).when(input).getPropertySource();
        doReturn(object).when(fresh).getEditableValue();
        doReturn(List.of(property)).when(editors).filterProperties(any());
        doReturn(true).when(property).isEditable(object);
        invokeForm(source, owner, editors, input);
        verify(input).getPropertySource();
        verify(fresh).getProperties();
        verify(fresh).getEditableValue();
        verify(editors).hasEditors();
        verify(editors).filterProperties(any());
        verify(property).isEditable(object);
        verify(owner).isDisposed();
        verifyNoMoreInteractions(input, fresh, editors, property, owner);
        verifyNoInteractions(source);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void asynchronousReadRechecksEditabilityAndDisposalBeforeApplyingValues() throws Exception {
        for (String change : List.of("unchanged", "editable", "disposed")) {
            var source = mock(DBPPropertySource.class);
            var owner = mock(ObjectEditorPageControl.class);
            var editors = mock(CustomFormEditor.class);
            var input = mock(IDatabaseEditorInput.class);
            var object = mock(DBSObject.class);
            var property = mock(DBPPropertyDescriptor.class);
            doReturn(true).when(editors).hasEditors();
            doReturn(source).when(input).getPropertySource();
            doReturn(object).when(input).getDatabaseObject();
            doReturn(object).when(source).getEditableValue();
            doReturn("status").when(property).getId();
            doReturn("Normal").when(source).getPropertyValue(any(), eq("status"));
            doReturn(List.of(property)).when(editors).filterProperties(any());
            invokeForm(source, owner, editors, input);
            ArgumentCaptor<LoadingJob<Map<DBPPropertyDescriptor, Object>>> job =
                ArgumentCaptor.forClass((Class) LoadingJob.class);
            ArgumentCaptor<DBRRunnableParametrized<Map<DBPPropertyDescriptor, Object>>> callback =
                ArgumentCaptor.forClass((Class) DBRRunnableParametrized.class);
            verify(owner).runService(job.capture());
            verify(owner).createDefaultLoadVisualizer(callback.capture());
            var values = job.getValue().getLoadingService().evaluate(new VoidProgressMonitor());
            assertEquals(Map.of(property, "Normal"), values);
            if (change.equals("editable")) {
                doReturn(true).when(property).isEditable(object);
            } else if (change.equals("disposed")) {
                doReturn(true).when(owner).isDisposed();
            }
            callback.getValue().run(values);
            if (change.equals("disposed")) {
                verify(editors, never()).loadEditorValues(any());
            } else {
                verify(editors).loadEditorValues(change.equals("editable") ? Map.of() : Map.of(property, "Normal"));
            }
        }
    }

    private static void invokeForm(DBPPropertySource source, ObjectEditorPageControl owner,
        CustomFormEditor editors, IDatabaseEditorInput input) throws Exception {
        var type = Class.forName(FORM);
        var form = mock(type, CALLS_REAL_METHODS);
        for (var entry : Map.of("curPropertySource", source, "ownerControl", owner,
            "formEditor", editors, "input", input).entrySet()) {
            var field = type.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(form, entry.getValue());
        }
        var method = type.getDeclaredMethod("refreshReadOnlyProperties");
        method.setAccessible(true);
        method.invoke(form);
    }
}
