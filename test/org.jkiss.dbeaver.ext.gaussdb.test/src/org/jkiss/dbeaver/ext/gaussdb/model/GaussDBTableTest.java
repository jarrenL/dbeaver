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

import org.jkiss.dbeaver.ext.postgresql.model.PostgreServerExtension;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class GaussDBTableTest {

    @Test
    public void recognizesGaussDBPartitionedTableMetadata() throws Exception {
        GaussDBSchema schema = Mockito.mock(GaussDBSchema.class);
        GaussDBDataSource dataSource = Mockito.mock(GaussDBDataSource.class);
        PostgreServerExtension serverExtension = Mockito.mock(PostgreServerExtension.class);
        JDBCResultSet resultSet = Mockito.mock(JDBCResultSet.class);
        Mockito.when(schema.getDataSource()).thenReturn(dataSource);
        Mockito.when(dataSource.getServerType()).thenReturn(serverExtension);
        Mockito.when(resultSet.getString("relname")).thenReturn("orders");
        Mockito.when(resultSet.getString("gauss_parttype")).thenReturn("p");
        Mockito.when(resultSet.getString("gauss_partstrategy")).thenReturn("r");
        Mockito.when(resultSet.getString("gauss_partkey")).thenReturn("1 2");

        GaussDBTable table = new GaussDBTable(schema, resultSet);

        Assertions.assertTrue(table.hasPartitions());
        Assertions.assertEquals("RANGE (attribute 1 2)", table.getPartitionKey());
    }

    @Test
    public void keepsNormalTableOutOfPartitionModel() throws Exception {
        GaussDBSchema schema = Mockito.mock(GaussDBSchema.class);
        GaussDBDataSource dataSource = Mockito.mock(GaussDBDataSource.class);
        PostgreServerExtension serverExtension = Mockito.mock(PostgreServerExtension.class);
        JDBCResultSet resultSet = Mockito.mock(JDBCResultSet.class);
        Mockito.when(schema.getDataSource()).thenReturn(dataSource);
        Mockito.when(dataSource.getServerType()).thenReturn(serverExtension);
        Mockito.when(resultSet.getString("relname")).thenReturn("customers");
        Mockito.when(resultSet.getString("gauss_parttype")).thenReturn("n");

        GaussDBTable table = new GaussDBTable(schema, resultSet);

        Assertions.assertFalse(table.hasPartitions());
        Assertions.assertNull(table.getPartitionKey());
    }

    @Test
    public void partitionLoaderUsesGaussDBCatalog() {
        String sql = GaussDBTablePartition.LOAD_PARTITIONS_SQL;

        Assertions.assertTrue(sql.contains("pg_catalog.pg_partition"));
        Assertions.assertTrue(sql.contains("p.parentid=?"));
        Assertions.assertFalse(sql.contains("pg_inherits"));
        Assertions.assertFalse(sql.contains("relispartition"));
    }
}
