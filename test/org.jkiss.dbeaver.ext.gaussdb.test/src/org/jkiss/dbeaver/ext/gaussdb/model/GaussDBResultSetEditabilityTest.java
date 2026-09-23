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
import org.jkiss.dbeaver.model.data.DBDAttributeBindingMeta;
import org.jkiss.dbeaver.model.data.DBDRowIdentifier;
import org.jkiss.dbeaver.model.exec.DBCAttributeMetaData;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionSource;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBExecUtils;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSDataManipulator;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBResultSetEditabilityTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SELECT amount + 1 AS amount FROM accounts | 0 | 1 | amount | false",
        "SELECT amount FROM accounts | 0 | 1 | amount | true",
        "SELECT amount + 1 AS amount, * FROM accounts | 0 | 3 | amount | false",
        "SELECT *, amount + 1 AS amount FROM accounts | 2 | 3 | amount | false",
        "SELECT *, amount FROM accounts | 2 | 3 | amount | true",
        "SELECT * FROM accounts | 1 | 2 | amount | true",
        "SELECT amount + 1 AS amount, accounts.* FROM accounts | 0 | 3 | amount | false",
        "SELECT accounts.*, amount + 1 AS amount FROM accounts | 2 | 3 | amount | false",
        "SELECT amount AS total FROM accounts | 0 | 1 | total | true",
        "SELECT amount AS total, * FROM accounts | 0 | 3 | total | true",
        "SELECT *, amount AS total FROM accounts | 2 | 3 | total | true",
        "SELECT accounts.*, amount AS total FROM accounts | 2 | 3 | total | true",
        "SELECT amount + 1 AS amount, *, accounts.* FROM accounts | 0 | 5 | amount | false",
        "SELECT *, accounts.*, amount + 1 AS amount FROM accounts | 4 | 5 | amount | false",
        "SELECT *, accounts.*, amount AS total FROM accounts | 4 | 5 | total | true",
        "SELECT *, amount + 1 AS amount, amount AS total FROM accounts | 3 | 4 | total | true"
    })
    void wildcardDoesNotTurnExpressionAliasIntoPhysicalColumn(
        String sql, int ordinal, int count, String columnLabel, boolean physical
    ) throws Exception {
        DBCSession session = mock(DBCSession.class);
        DBPDataSource source = mock(DBPDataSource.class, withSettings().extraInterfaces(DBSObjectContainer.class));
        DBSObjectContainer catalog = (DBSObjectContainer) source;
        DBPDataSourceContainer container = mock(DBPDataSourceContainer.class);
        DBCExecutionContext context = mock(DBCExecutionContext.class);
        DBSEntity table = mock(DBSEntity.class);
        when(catalog.getDataSource()).thenReturn(source);
        when(catalog.getChild(any(), eq("accounts"))).thenReturn(table);
        when(context.getDataSource()).thenReturn(source);
        when(session.getExecutionContext()).thenReturn(context);
        when(session.getProgressMonitor()).thenReturn(new VoidProgressMonitor());
        when(session.getDataSource()).thenReturn(source);
        when(source.getContainer()).thenReturn(container);
        when(source.getSQLDialect()).thenReturn(new GaussDBDialect());
        when(container.getPreferenceStore()).thenReturn(mock(DBPPreferenceStore.class));
        when(source.getInfo()).thenReturn(mock(DBPDataSourceInfo.class));
        when(container.isExtraMetadataReadEnabled()).thenReturn(true);
        DBCResultSet resultSet = mock(DBCResultSet.class);
        DBCStatement statement = mock(DBCStatement.class);
        DBCExecutionSource execution = mock(DBCExecutionSource.class);
        when(resultSet.getSourceStatement()).thenReturn(statement);
        when(statement.getStatementSource()).thenReturn(execution);
        when(execution.getSourceDescriptor()).thenReturn(new SQLQuery(null, sql));
        DBCAttributeMetaData metadata = mock(DBCAttributeMetaData.class);
        when(metadata.getName()).thenReturn(columnLabel);
        when(metadata.getLabel()).thenReturn(columnLabel);
        when(metadata.getOrdinalPosition()).thenReturn(ordinal);
        DBDAttributeBindingMeta binding = mock(DBDAttributeBindingMeta.class, CALLS_REAL_METHODS);
        doReturn(metadata).when(binding).getMetaAttribute();
        doReturn(columnLabel).when(binding).getName();
        DBDAttributeBinding[] bindings = new DBDAttributeBinding[count];
        for (int i = 0; i < count; i++) {
            bindings[i] = i == ordinal ? binding : mock(DBDAttributeBinding.class);
        }
        DBExecUtils.bindAttributes(session, null, resultSet, bindings, null);
        verify(table, physical ? times(1) : never()).getAttribute(any(), eq("amount"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT 1 AS amount",
        "SELECT 1 + 2 AS amount",
        "SELECT '*' AS amount",
        "SELECT CASE WHEN true THEN 1 ELSE 2 END AS amount",
        "WITH q AS (SELECT 1 AS amount) SELECT amount FROM q",
        "WITH q AS (SELECT 1 AS amount) SELECT v.amount FROM q v",
        "SELECT v.amount FROM (SELECT 1 AS amount) v",
        "SELECT v.amount FROM (SELECT 1 AS amount UNION ALL SELECT 2) v"
    })
    void bindingWithoutPhysicalOriginCannotInventWritableColumn(String sql) throws Exception {
        DBCSession session = mock(DBCSession.class);
        DBPDataSource source = mock(DBPDataSource.class);
        DBPDataSourceContainer container = mock(DBPDataSourceContainer.class);
        DBPDataSourceInfo info = mock(DBPDataSourceInfo.class);
        when(session.getProgressMonitor()).thenReturn(new VoidProgressMonitor());
        when(session.getDataSource()).thenReturn(source);
        when(source.getContainer()).thenReturn(container);
        when(source.getInfo()).thenReturn(info);
        when(container.isExtraMetadataReadEnabled()).thenReturn(true);

        DBCResultSet resultSet = mock(DBCResultSet.class);
        DBCStatement statement = mock(DBCStatement.class);
        DBCExecutionSource execution = mock(DBCExecutionSource.class);
        when(resultSet.getSourceStatement()).thenReturn(statement);
        when(statement.getStatementSource()).thenReturn(execution);
        when(execution.getSourceDescriptor()).thenReturn(new SQLQuery(null, sql));

        DBCAttributeMetaData metadata = mock(DBCAttributeMetaData.class);
        when(metadata.getName()).thenReturn("amount");
        when(metadata.getLabel()).thenReturn("amount");
        // Real binding state and setters; only driver-supplied metadata is replaced.
        DBDAttributeBindingMeta binding = mock(DBDAttributeBindingMeta.class, CALLS_REAL_METHODS);
        doReturn(metadata).when(binding).getMetaAttribute();
        doReturn("amount").when(binding).getName();
        DBExecUtils.bindAttributes(session, null, resultSet, new DBDAttributeBinding[] {binding}, null);

        assertNull(binding.getEntityAttribute());
        assertNull(binding.getRowIdentifier());
        assertNotNull(binding.getRowIdentifierStatus());
        assertTrue(DBExecUtils.isAttributeReadOnly(binding, true));
        assertEquals(binding.getRowIdentifierStatus(), DBExecUtils.getAttributeReadOnlyStatus(binding, true));
        verify(binding, never()).setEntityAttribute(any(), anyBoolean());
        verify(binding, never()).setRowIdentifier(any());
    }

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
