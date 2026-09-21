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

public class DBCompatibilityEnumTest {

    @Test
    public void resolvesCentralizedAndDistributedCompatibilityValues() {
        Assertions.assertEquals("A", DBCompatibilityEnum.ORACLE.getcValue());
        Assertions.assertEquals("ORA", DBCompatibilityEnum.ORACLE.getdValue());
        Assertions.assertEquals("B", DBCompatibilityEnum.MYSQL.getcValue());
        Assertions.assertEquals("MYSQL", DBCompatibilityEnum.MYSQL.getdValue());
        Assertions.assertEquals("C", DBCompatibilityEnum.TERADATA.getcValue());
        Assertions.assertEquals("TD", DBCompatibilityEnum.TERADATA.getdValue());
        Assertions.assertEquals("Oracle", DBCompatibilityEnum.queryTextByValue("A"));
        Assertions.assertEquals("Oracle", DBCompatibilityEnum.queryTextByValue("ORA"));
        Assertions.assertEquals("MySQL", DBCompatibilityEnum.queryTextByValue("B"));
        Assertions.assertEquals("MySQL", DBCompatibilityEnum.queryTextByValue("MYSQL"));
        Assertions.assertEquals("Teradata", DBCompatibilityEnum.queryTextByValue("C"));
        Assertions.assertEquals("Teradata", DBCompatibilityEnum.queryTextByValue("TD"));
        Assertions.assertEquals("PostgreSQL", DBCompatibilityEnum.queryTextByValue("PG"));
    }

    @Test
    public void keepsMAsAnIndependentCompatibilityMode() {
        Assertions.assertSame(DBCompatibilityEnum.M, DBCompatibilityEnum.of("m"));
        Assertions.assertSame(DBCompatibilityEnum.M, DBCompatibilityEnum.fromValue("m"));
        Assertions.assertEquals("M", DBCompatibilityEnum.queryTextByValue("M"));
        Assertions.assertEquals("M", DBCompatibilityEnum.getDValueByText("M"));
    }

    @Test
    public void defaultsUnknownDisplayTextToPostgreSQL() {
        Assertions.assertEquals("PG", DBCompatibilityEnum.getDValueByText(null));
        Assertions.assertEquals("PG", DBCompatibilityEnum.getDValueByText("unknown"));
        Assertions.assertEquals("", DBCompatibilityEnum.queryTextByValue(null));
    }

    @Test
    public void doesNotGuessCompatibilityValueForUnknownDeployment() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> DBCompatibilityEnum.ORACLE.getValue(GaussDBServerInfo.Deployment.UNKNOWN)
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> DBCompatibilityEnum.MYSQL.getValue(GaussDBServerInfo.Deployment.CLOUD_NATIVE)
        );
    }
}
