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
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPDataSourceInfo;
import org.jkiss.dbeaver.model.DBPDataSourcePermission;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDRowIdentifier;
import org.jkiss.dbeaver.model.exec.DBCAttributeMetaData;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBExecUtils;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSDataManipulator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBResultSetEditabilityTest {
    @ParameterizedTest
    @ValueSource(strings = {"missing-metadata", "driver-readonly", "missing-identifier",
        "custom-identifier-reason", "non-manipulator", "update-unsupported", "incomplete-key", "writable"})
    void attributeGateAndExplanationAgree(String scenario) {
        DBDAttributeBinding binding = mock(DBDAttributeBinding.class);
        DBCAttributeMetaData metadata = mock(DBCAttributeMetaData.class);
        DBDRowIdentifier identifier = mock(DBDRowIdentifier.class);
        DBSEntity entity = mock(DBSEntity.class, withSettings().extraInterfaces(DBSDataManipulator.class));
        DBSDataManipulator manipulator = (DBSDataManipulator) entity;
        when(binding.getMetaAttribute()).thenReturn(metadata);
        when(binding.getRowIdentifier()).thenReturn(identifier);
        when(identifier.getEntity()).thenReturn(entity);
        when(manipulator.isFeatureSupported(DBSDataManipulator.FEATURE_DATA_UPDATE)).thenReturn(true);
        switch (scenario) {
            case "missing-metadata" -> when(binding.getMetaAttribute()).thenReturn(null);
            case "driver-readonly" -> when(metadata.isReadOnly()).thenReturn(true);
            case "missing-identifier" -> when(binding.getRowIdentifier()).thenReturn(null);
            case "custom-identifier-reason" -> {
                when(binding.getRowIdentifier()).thenReturn(null);
                when(binding.getRowIdentifierStatus()).thenReturn("No physical source column");
            }
            case "non-manipulator" -> when(identifier.getEntity()).thenReturn(mock(DBSEntity.class));
            case "update-unsupported" -> when(manipulator.isFeatureSupported(DBSDataManipulator.FEATURE_DATA_UPDATE)).thenReturn(false);
            case "incomplete-key" -> when(identifier.isIncomplete()).thenReturn(true);
            default -> { }
        }
        boolean readOnly = !scenario.equals("writable");
        assertEquals(readOnly, DBExecUtils.isAttributeReadOnly(binding, true));
        String reason = DBExecUtils.getAttributeReadOnlyStatus(binding, true);
        if (readOnly) {
            assertNotNull(reason);
            assertFalse(reason.isBlank());
        } else {
            assertNull(reason);
        }
        if (scenario.equals("custom-identifier-reason")) {
            assertEquals("No physical source column", reason);
        }
        if (scenario.equals("incomplete-key")) {
            assertFalse(DBExecUtils.isAttributeReadOnly(binding, false));
            assertNull(DBExecUtils.getAttributeReadOnlyStatus(binding, false));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"disconnected", "restricted", "readonly-data", "writable"})
    void resultSetGateAndExplanationAgree(String scenario) {
        DBCExecutionContext context = mock(DBCExecutionContext.class);
        DBPDataSource source = mock(DBPDataSource.class);
        DBPDataSourceContainer container = mock(DBPDataSourceContainer.class);
        DBPDataSourceInfo info = mock(DBPDataSourceInfo.class);
        when(context.getDataSource()).thenReturn(source);
        when(source.getContainer()).thenReturn(container);
        when(container.getDataSource()).thenReturn(source);
        when(source.getInfo()).thenReturn(info);
        when(context.isConnected()).thenReturn(!scenario.equals("disconnected"));
        when(container.isConnected()).thenReturn(!scenario.equals("disconnected"));
        when(container.hasModifyPermission(DBPDataSourcePermission.PERMISSION_EDIT_DATA))
            .thenReturn(!scenario.equals("restricted"));
        when(info.isReadOnlyData()).thenReturn(scenario.equals("readonly-data"));
        assertEquals(!scenario.equals("writable"), DBExecUtils.isResultSetReadOnly(context));
        assertEquals(scenario.equals("writable"), DBExecUtils.getResultSetReadOnlyStatus(container) == null);
    }

    @Test
    void absentContextOrAttributeCannotBeEdited() {
        assertTrue(DBExecUtils.isAttributeReadOnly(null));
        assertTrue(DBExecUtils.isResultSetReadOnly(null));
        assertNotNull(DBExecUtils.getResultSetReadOnlyStatus(null));
    }
}
