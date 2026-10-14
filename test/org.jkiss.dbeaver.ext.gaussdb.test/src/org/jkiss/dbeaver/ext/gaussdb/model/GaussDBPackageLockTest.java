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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;

public class GaussDBPackageLockTest {
    @Test
    void keyIsStableForNewObjectsWithTheSameCatalogIdentity() {
        long key = GaussDBPackageLock.getKey(packageObject(10, 20, "Business", "Payments"));
        Assertions.assertEquals(key,
            GaussDBPackageLock.getKey(packageObject(10, 20, "Business", "Payments")));
        Assertions.assertEquals(-4886319740177508517L, key);
    }

    @Test
    void keySeparatesDatabaseSchemaAndExactPackageName() {
        long base = GaussDBPackageLock.getKey(packageObject(10, 20, "business", "payments"));
        Assertions.assertNotEquals(base,
            GaussDBPackageLock.getKey(packageObject(11, 20, "business", "payments")));
        Assertions.assertNotEquals(base,
            GaussDBPackageLock.getKey(packageObject(10, 21, "business", "payments")));
        Assertions.assertNotEquals(base,
            GaussDBPackageLock.getKey(packageObject(10, 20, "other", "payments")));
        Assertions.assertNotEquals(base,
            GaussDBPackageLock.getKey(packageObject(10, 20, "business", "Payments")));
    }

    @Test
    void lockIsLimitedToDistributedAndCloudNativeDeployments() {
        Assertions.assertTrue(GaussDBPackageLock.isRequired(
            packageObject(GaussDBServerInfo.Deployment.DISTRIBUTED)));
        Assertions.assertTrue(GaussDBPackageLock.isRequired(
            packageObject(GaussDBServerInfo.Deployment.CLOUD_NATIVE)));
        Assertions.assertFalse(GaussDBPackageLock.isRequired(
            packageObject(GaussDBServerInfo.Deployment.CENTRALIZED)));
        Assertions.assertFalse(GaussDBPackageLock.isRequired(
            packageObject(GaussDBServerInfo.Deployment.UNKNOWN)));
    }

    private static GaussDBPackage packageObject(long databaseId, long schemaId, String schemaName, String name) {
        GaussDBPackage object = Mockito.mock(GaussDBPackage.class);
        GaussDBDatabase database = Mockito.mock(GaussDBDatabase.class);
        GaussDBSchema schema = Mockito.mock(GaussDBSchema.class);
        Mockito.when(object.getDatabase()).thenReturn(database);
        Mockito.when(object.getSchema()).thenReturn(schema);
        Mockito.when(object.getName()).thenReturn(name);
        Mockito.when(database.getObjectId()).thenReturn(databaseId);
        Mockito.when(schema.getObjectId()).thenReturn(schemaId);
        Mockito.when(schema.getName()).thenReturn(schemaName);
        return object;
    }

    private static GaussDBPackage packageObject(GaussDBServerInfo.Deployment deployment) {
        GaussDBPackage object = packageObject(10, 20, "business", "payments");
        GaussDBDataSource dataSource = Mockito.mock(GaussDBDataSource.class);
        Mockito.when(object.getDataSource()).thenReturn(dataSource);
        Mockito.when(dataSource.getServerInfo()).thenReturn(
            GaussDBServerInfo.forTest(deployment, Set.of("gs_package")));
        return object;
    }
}
