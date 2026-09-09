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
package org.jkiss.dbeaver.ext.gaussdb.edit;

import org.jkiss.dbeaver.ext.gaussdb.GaussDBConstants;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDataSource;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDatabase;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBSchema;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreServerExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

public class GaussDBProcedureManagerTest {

    private GaussDBSchema schema;
    private GaussDBDatabase database;

    @BeforeEach
    public void setUp() {
        schema = Mockito.mock(GaussDBSchema.class);
        database = Mockito.mock(GaussDBDatabase.class);
        GaussDBDataSource dataSource = Mockito.mock(GaussDBDataSource.class);
        PostgreServerExtension server = Mockito.mock(PostgreServerExtension.class);

        Mockito.when(schema.getDatabase()).thenReturn(database);
        Mockito.when(schema.getDataSource()).thenReturn(dataSource);
        Mockito.when(dataSource.getServerType()).thenReturn(server);
        Mockito.when(server.supportsFunctionCreate()).thenReturn(true);
        Mockito.when(server.supportsStoredProcedures()).thenReturn(true);
    }

    @Test
    public void disablesProcedureCreationInMCompatibilityMode() {
        Mockito.when(database.getDatabaseCompatibleMode()).thenReturn(GaussDBConstants.GAUSSDB_M_COMPATIBLE_MODE);
        Mockito.when(database.isStoredProcedureSupported()).thenReturn(false);

        Assertions.assertFalse(new GaussDBProcedureManager().canCreateObject(schema));
    }

    @Test
    public void enablesProcedureCreationInSupportedCompatibilityModes() {
        GaussDBProcedureManager manager = new GaussDBProcedureManager();
        for (String compatibilityMode : List.of("A", "ORA", "B", "MYSQL", "C", "TD", "PG")) {
            Mockito.when(database.getDatabaseCompatibleMode()).thenReturn(compatibilityMode);
            Mockito.when(database.isStoredProcedureSupported()).thenReturn(true);
            Assertions.assertTrue(manager.canCreateObject(schema), compatibilityMode);
        }
    }

    @Test
    public void keepsFunctionCreationEnabledInMCompatibilityMode() {
        Mockito.when(database.getDatabaseCompatibleMode()).thenReturn(GaussDBConstants.GAUSSDB_M_COMPATIBLE_MODE);

        Assertions.assertTrue(new GaussDBFunctionManager().canCreateObject(schema));
    }
}
