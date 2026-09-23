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
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBInsertFromDataTest {
    private DBDAttributeBinding first;
    private DBDAttributeBinding second;
    private DBDValueRow row;
    private DBDResultSetDataProvider provider;
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

    @BeforeEach
    void prepareProvider() throws Exception {
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
        first = column(source, "first col");
        second = column(source, "second col");
        row = mock(DBDValueRow.class);
        provider = mock(DBDResultSetDataProvider.class);
        when(provider.getSingleSource()).thenReturn(table);
        doReturn(List.of(row)).when(provider).getSelectedRows();
        when(provider.getAttributes()).thenReturn(new DBDAttributeBinding[] {first, second});
        when(provider.getVisibleAttributes()).thenReturn(List.of(second, first));
        when(provider.getCellValue(first, row)).thenReturn("001");
    }

    @ParameterizedTest
    @MethodSource("values")
    void generatesInsertUsingVisibleColumnOrderAndRealStringHandler(String value, String expectedLiteral) throws Exception {
        when(provider.getCellValue(second, row)).thenReturn(value);
        assertEquals("INSERT INTO \"订单 表\" (\"second col\", \"first col\") VALUES("
            + expectedLiteral + ", '001');\n", generate(false, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty-selection", "two-rows", "exclude-generated", "include-generated", "visible-only", "multiline",
        "hidden-column", "pseudo-column", "missing-binding"})
    void preservesSelectedRowsAndColumnOptions(String scenario) throws Exception {
        when(provider.getCellValue(second, row)).thenReturn("a");
        String expected = "INSERT INTO \"订单 表\" (\"second col\", \"first col\") VALUES('a', '001');\n";
        switch (scenario) {
            case "empty-selection" -> {
                doReturn(List.of()).when(provider).getSelectedRows();
                expected = "";
            }
            case "two-rows" -> {
                DBDValueRow another = mock(DBDValueRow.class);
                doReturn(List.of(another, row)).when(provider).getSelectedRows();
                when(provider.getCellValue(first, another)).thenReturn("002");
                when(provider.getCellValue(second, another)).thenReturn(null);
                expected = "INSERT INTO \"订单 表\" (\"second col\", \"first col\") VALUES(NULL, '002');\n" + expected;
            }
            case "exclude-generated", "include-generated" -> {
                when(first.isAutoGenerated()).thenReturn(true);
                if (scenario.equals("exclude-generated")) {
                    expected = "INSERT INTO \"订单 表\" (\"second col\") VALUES('a');\n";
                }
            }
            case "visible-only" -> {
                when(provider.getVisibleAttributes()).thenReturn(List.of(first));
                expected = "INSERT INTO \"订单 表\" (\"first col\") VALUES('001');\n";
            }
            case "multiline" -> expected = "INSERT INTO \"订单 表\"\n(\"second col\", \"first col\")\nVALUES('a', '001');\n";
            case "hidden-column", "pseudo-column" -> {
                if (scenario.equals("hidden-column")) {
                    when(((org.jkiss.dbeaver.model.DBPHiddenObject) first).isHidden()).thenReturn(true);
                } else {
                    when(first.isPseudoAttribute()).thenReturn(true);
                }
                expected = "INSERT INTO \"订单 表\" (\"second col\") VALUES('a');\n";
            }
            case "missing-binding" -> {
                when(provider.getAttributes()).thenReturn(new DBDAttributeBinding[] {first});
                expected = "INSERT INTO \"订单 表\" (\"second col\", \"first col\") VALUES('', '001');\n";
            }
            default -> fail("Unexpected scenario");
        }
        assertEquals(expected, generate(scenario.equals("exclude-generated"), !scenario.equals("multiline")));
        if (scenario.equals("hidden-column") || scenario.equals("pseudo-column")) {
            verify(provider, never()).getCellValue(first, row);
        }
        if (scenario.equals("missing-binding")) {
            verify(provider, never()).getCellValue(second, row);
        }
    }

    private String generate(boolean excludeGenerated, boolean compact) throws Exception {
        // This implementation package is intentionally not exported by its OSGi bundle.
        // Load through the owning bundle instead of widening production exports for a test.
        var implementation = Platform.getBundle("org.jkiss.dbeaver.model.sql")
            .loadClass("org.jkiss.dbeaver.model.sql.generator.resultset.SQLGeneratorInsertFromData");
        Object generator = implementation.getConstructor().newInstance();
        implementation.getMethod("setFullyQualifiedNames", boolean.class).invoke(generator, false);
        implementation.getMethod("setCompactSQL", boolean.class).invoke(generator, compact);
        implementation.getMethod("setExcludeAutoGeneratedColumn", boolean.class).invoke(generator, excludeGenerated);
        var generate = implementation.getDeclaredMethod("generateSQL", DBRProgressMonitor.class,
            StringBuilder.class, DBDResultSetDataProvider.class);
        generate.setAccessible(true);
        StringBuilder generated = new StringBuilder();
        generate.invoke(generator, new VoidProgressMonitor(), generated, provider);
        return generated.toString();
    }

    private DBDAttributeBinding column(DBPDataSource source, String name) {
        DBDAttributeBinding binding = mock(DBDAttributeBinding.class,
            withSettings().extraInterfaces(org.jkiss.dbeaver.model.DBPHiddenObject.class));
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
