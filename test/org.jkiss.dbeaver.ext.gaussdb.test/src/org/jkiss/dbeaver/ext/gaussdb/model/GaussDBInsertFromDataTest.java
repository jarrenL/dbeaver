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

import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDResultSetDataProvider;
import org.jkiss.dbeaver.model.data.DBDValueHandlerProvider;
import org.jkiss.dbeaver.model.data.DBDValueRow;
import org.jkiss.dbeaver.model.impl.jdbc.data.handlers.JDBCStringValueHandler;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBInsertFromDataTest {
    static Stream<Arguments> values() {
        return Stream.of(
            Arguments.of(null, "NULL"),
            Arguments.of("", "''"),
            Arguments.of("NULL", "'NULL'"),
            Arguments.of("O'Reilly", "'O''Reilly'"),
            Arguments.of("中文数据", "'中文数据'"),
            Arguments.of("line1\nline2", "'line1\nline2'"),
            Arguments.of("x'); DROP TABLE t; --", "'x''); DROP TABLE t; --'")
        );
    }

    @ParameterizedTest
    @MethodSource("values")
    void generatesInsertUsingVisibleColumnOrderAndRealStringHandler(String value, String expectedLiteral) throws Exception {
        DBPDataSource source = mock(DBPDataSource.class, withSettings().extraInterfaces(DBDValueHandlerProvider.class));
        DBPDataSourceContainer container = mock(DBPDataSourceContainer.class);
        when(source.getContainer()).thenReturn(container);
        when(source.getSQLDialect()).thenReturn(new GaussDBDialect());
        when(container.getPreferenceStore()).thenReturn(mock(DBPPreferenceStore.class));
        when(((DBDValueHandlerProvider) source).getValueHandler(any(), any(), any()))
            .thenReturn(JDBCStringValueHandler.INSTANCE);
        DBSEntity table = mock(DBSEntity.class);
        when(table.getDataSource()).thenReturn(source);
        when(table.getName()).thenReturn("订单 表");
        DBDAttributeBinding first = column(source, "first col");
        DBDAttributeBinding second = column(source, "second col");
        DBDValueRow row = mock(DBDValueRow.class);
        DBDResultSetDataProvider provider = mock(DBDResultSetDataProvider.class);
        when(provider.getSingleSource()).thenReturn(table);
        doReturn(List.of(row)).when(provider).getSelectedRows();
        when(provider.getAttributes()).thenReturn(new DBDAttributeBinding[] {first, second});
        when(provider.getVisibleAttributes()).thenReturn(List.of(second, first));
        when(provider.getCellValue(first, row)).thenReturn("001");
        when(provider.getCellValue(second, row)).thenReturn(value);
        // This implementation package is intentionally not exported by its OSGi bundle.
        // Load through the owning bundle instead of widening production exports for a test.
        var implementation = Platform.getBundle("org.jkiss.dbeaver.model.sql")
            .loadClass("org.jkiss.dbeaver.model.sql.generator.resultset.SQLGeneratorInsertFromData");
        Object generator = implementation.getConstructor().newInstance();
        implementation.getMethod("setFullyQualifiedNames", boolean.class).invoke(generator, false);
        implementation.getMethod("setCompactSQL", boolean.class).invoke(generator, true);
        var generate = implementation.getDeclaredMethod("generateSQL", DBRProgressMonitor.class,
            StringBuilder.class, DBDResultSetDataProvider.class);
        generate.setAccessible(true);
        StringBuilder generated = new StringBuilder();
        generate.invoke(generator, new VoidProgressMonitor(), generated, provider);
        String sql = generated.toString();
        assertEquals("INSERT INTO \"订单 表\" (\"second col\", \"first col\") VALUES("
            + expectedLiteral + ", '001');\n", sql);
    }

    private DBDAttributeBinding column(DBPDataSource source, String name) {
        DBDAttributeBinding binding = mock(DBDAttributeBinding.class);
        when(binding.getDataSource()).thenReturn(source);
        when(binding.getName()).thenReturn(name);
        when(binding.getDataKind()).thenReturn(DBPDataKind.STRING);
        when(binding.getAttribute()).thenReturn(binding);
        when(binding.getFullyQualifiedName(any())).thenCallRealMethod();
        when(binding.getFullyQualifiedName(any(), any())).thenCallRealMethod();
        when(binding.matches(binding, true)).thenReturn(true);
        return binding;
    }

}
