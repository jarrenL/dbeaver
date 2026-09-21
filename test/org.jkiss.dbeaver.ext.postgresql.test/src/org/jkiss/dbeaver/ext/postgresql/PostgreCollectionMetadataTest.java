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
package org.jkiss.dbeaver.ext.postgresql;

import org.jkiss.dbeaver.ext.postgresql.model.*;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostgreCollectionMetadataTest {
    @Test
    void readsGaussCollectionMetadataWithoutChangingElementMapping() throws Exception {
        var source = mock(PostgreDataSource.class);
        var server = mock(PostgreServerExtension.class);
        var database = mock(PostgreDatabase.class);
        var schema = mock(PostgreSchema.class);
        var session = mock(JDBCSession.class);
        var row = mock(JDBCResultSet.class);
        var monitor = new VoidProgressMonitor();
        when(session.getProgressMonitor()).thenReturn(monitor);
        when(session.getDataSource()).thenReturn(source);
        when(source.isServerVersionAtLeast(8, 4)).thenReturn(true);
        when(source.getServerType()).thenReturn(server);
        when(database.getDataSource()).thenReturn(source);
        when(schema.getDataSource()).thenReturn(source);
        when(database.getSchema(monitor, 11L)).thenReturn(schema);
        when(row.getLong("typnamespace")).thenReturn(11L);
        when(row.getLong("oid")).thenReturn(20000L);
        when(row.getString("typname")).thenReturn("number_table");
        when(row.getString("typtype")).thenReturn("o");
        when(row.getString("typcategory")).thenReturn("F");
        when(row.getInt("typlen")).thenReturn(-1);
        when(row.getLong("typelem")).thenReturn(1700L);
        when(server.resolveDataTypeValueType(anyString(), anyLong(), any(), anyInt(), anyInt()))
            .thenAnswer(invocation -> invocation.getArgument(4));

        var type = PostgreDataType.readDataType(session, database, row, false);
        assertNotNull(type);
        assertEquals(PostgreTypeType.o, type.getTypeType());
        assertEquals(PostgreTypeCategory.F, type.getTypeCategory());
        assertEquals(Types.ARRAY, type.getTypeID());

        when(row.getLong("typelem")).thenReturn(0L);
        assertEquals(Types.OTHER, PostgreDataType.readDataType(session, database, row, false).getTypeID());
        when(row.getString("typtype")).thenReturn("b");
        when(row.getString("typcategory")).thenReturn("S");
        assertEquals(Types.VARCHAR, PostgreDataType.readDataType(session, database, row, false).getTypeID());

        // Catalog codes observed on GaussDB 507. Recognition must not silently
        // introduce signed-integer mappings for unsigned or internal types.
        String[][] internalTypes = {
            {"anyset", "s", "H"}, {"rowid", "b", "L"},
            {"uint1", "b", "M"}, {"uint2", "b", "M"},
            {"uint4", "b", "M"}, {"uint8", "b", "M"},
            {"undefined", "u", "W"}
        };
        for (String[] internalType : internalTypes) {
            when(row.getString("typname")).thenReturn(internalType[0]);
            when(row.getString("typtype")).thenReturn(internalType[1]);
            when(row.getString("typcategory")).thenReturn(internalType[2]);
            var internal = PostgreDataType.readDataType(session, database, row, false);
            assertNotNull(internal);
            assertEquals(PostgreTypeType.valueOf(internalType[1]), internal.getTypeType());
            assertEquals(PostgreTypeCategory.valueOf(internalType[2]), internal.getTypeCategory());
            assertEquals(Types.OTHER, internal.getTypeID());
        }
    }
}
