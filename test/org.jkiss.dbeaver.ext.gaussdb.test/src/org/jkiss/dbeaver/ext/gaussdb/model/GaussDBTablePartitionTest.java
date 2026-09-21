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

public class GaussDBTablePartitionTest {
    @Test
    public void formatsNumericAndSpecialBoundaries() {
        Assertions.assertEquals(
            "VALUES LESS THAN (10,MAXVALUE)",
            GaussDBTablePartition.formatPartitionExpression("r", "{10,NULL}"));
        Assertions.assertEquals(
            "VALUES (1,2,DEFAULT)",
            GaussDBTablePartition.formatPartitionExpression("l", "{1,2,NULL}"));
        Assertions.assertEquals(
            "HASH BUCKET (3)",
            GaussDBTablePartition.formatPartitionExpression("h", "{3}"));
    }

    @Test
    public void preservesQuotedStringsAndArrayEscapes() {
        Assertions.assertEquals(
            "VALUES ('a,b','NULL','x y','O''Reilly',' x ')",
            GaussDBTablePartition.formatPartitionExpression(
                "l", "{\"a,b\",\"NULL\",\"x y\",\"O'Reilly\",\" x \"}"));
        Assertions.assertEquals(
            "VALUES ('cn')",
            GaussDBTablePartition.formatPartitionExpression("l", "{cn}"));
    }
}
