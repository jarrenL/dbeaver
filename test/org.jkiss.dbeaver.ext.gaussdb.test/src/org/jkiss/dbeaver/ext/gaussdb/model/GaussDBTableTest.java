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

    private GaussDBTable table(String partType, String strategy, String key) throws Exception {
        var schema = Mockito.mock(GaussDBSchema.class);
        var dataSource = Mockito.mock(GaussDBDataSource.class);
        var server = Mockito.mock(PostgreServerExtension.class);
        var result = Mockito.mock(JDBCResultSet.class);
        Mockito.when(schema.getDataSource()).thenReturn(dataSource);
        Mockito.when(dataSource.getServerType()).thenReturn(server);
        Mockito.when(result.getString("relname")).thenReturn("historical_table");
        Mockito.when(result.getString("gauss_parttype")).thenReturn(partType);
        Mockito.when(result.getString("gauss_partstrategy")).thenReturn(strategy);
        Mockito.when(result.getString("gauss_partkey")).thenReturn(key);
        return new GaussDBTable(schema, result);
    }

    @Test
    public void listPartitionKeyUsesCatalogAttributeOrder() throws Exception {
        Assertions.assertEquals("LIST (attribute 3 1)", table("p", "l", " 3 1 ").getPartitionKey());
    }

    @Test
    public void hashSubpartitionIsRecognizedCaseInsensitively() throws Exception {
        var table = table("S", "H", "2");
        Assertions.assertTrue(table.hasPartitions());
        Assertions.assertEquals("HASH (attribute 2)", table.getPartitionKey());
    }

    @Test
    public void intervalStrategyWithoutKeyDoesNotInventAttributes() throws Exception {
        Assertions.assertEquals("INTERVAL", table("p", "i", "  ").getPartitionKey());
    }

    @Test
    public void unknownStrategyPreservesRawAttributeReferences() throws Exception {
        Assertions.assertEquals("PARTITION (attribute 5)", table("p", "future", "5").getPartitionKey());
        Assertions.assertEquals("PARTITION", table("p", null, null).getPartitionKey());
    }

    @Test
    public void absentPartitionCatalogColumnsRemainAnOrdinaryTable() throws Exception {
        var table = table(null, null, null);
        Assertions.assertFalse(table.hasPartitions());
        Assertions.assertNull(table.getPartitionKey());
        var monitor = Mockito.mock(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor.class);
        Assertions.assertNull(table.getPartitions(monitor));
        Mockito.verifyNoInteractions(monitor);
    }

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
