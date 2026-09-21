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

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBServerInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class GaussDBDatabaseManagerTest {

    @Test
    public void preservesExplicitCatalogValuesWhenDeploymentIsUnknown() throws DBException {
        Assertions.assertEquals(
            "A",
            GaussDBDatabaseManager.resolveCompatibilityValue("A", GaussDBServerInfo.Deployment.UNKNOWN)
        );
        Assertions.assertEquals(
            "MYSQL",
            GaussDBDatabaseManager.resolveCompatibilityValue("mysql", GaussDBServerInfo.Deployment.CLOUD_NATIVE)
        );
    }

    @Test
    public void resolvesDisplayNamesOnlyForKnownDeployments() throws DBException {
        Assertions.assertEquals(
            "A",
            GaussDBDatabaseManager.resolveCompatibilityValue("Oracle", GaussDBServerInfo.Deployment.CENTRALIZED)
        );
        Assertions.assertEquals(
            "ORA",
            GaussDBDatabaseManager.resolveCompatibilityValue("Oracle", GaussDBServerInfo.Deployment.DISTRIBUTED)
        );
        Assertions.assertThrows(
            DBException.class,
            () -> GaussDBDatabaseManager.resolveCompatibilityValue("Oracle", GaussDBServerInfo.Deployment.UNKNOWN)
        );
    }

    @Test
    public void rejectsUnknownCompatibilityValues() {
        Assertions.assertThrows(
            DBException.class,
            () -> GaussDBDatabaseManager.resolveCompatibilityValue("not-a-mode", GaussDBServerInfo.Deployment.CENTRALIZED)
        );
    }
}
