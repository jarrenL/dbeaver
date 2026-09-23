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
    public void absentBoundariesDoNotGenerateAnInventedClause() {
        Assertions.assertEquals("", GaussDBTablePartition.formatPartitionExpression("r", null));
        Assertions.assertEquals("", GaussDBTablePartition.formatPartitionExpression("l", " \t"));
    }

    @Test
    public void signedDecimalsAndExponentsRemainNumeric() {
        Assertions.assertEquals("VALUES LESS THAN (-12,+3,.5,2.,1e-3)",
            GaussDBTablePartition.formatPartitionExpression("r", "{-12,+3,.5,2.,1e-3}"));
    }

    @Test
    public void quotedNullAndEmptyStringAreNotDefaultPartitions() {
        Assertions.assertEquals("VALUES ('NULL','',DEFAULT)",
            GaussDBTablePartition.formatPartitionExpression("l", "{\"NULL\",\"\",NULL}"));
    }

    @Test
    public void escapedQuotesAndBackslashesSurviveCatalogArrayDecoding() {
        Assertions.assertEquals("VALUES ('a\"b','c\\d')",
            GaussDBTablePartition.formatPartitionExpression("l", "{\"a\\\"b\",\"c\\\\d\"}"));
    }

    @Test
    public void timestampAndUnicodeBoundariesRemainStringLiterals() {
        Assertions.assertEquals("VALUES LESS THAN ('2024-02-29 23:59:59','中文,边界')",
            GaussDBTablePartition.formatPartitionExpression("R", "{\"2024-02-29 23:59:59\",\"中文,边界\"}"));
    }

    @Test
    public void apostropheAndSqlLookingBoundaryRemainQuotedData() {
        Assertions.assertEquals("VALUES ('x''); DROP TABLE t; --')",
            GaussDBTablePartition.formatPartitionExpression("l", "{\"x'); DROP TABLE t; --\"}"));
    }

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
