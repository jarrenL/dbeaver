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
package org.jkiss.dbeaver.ext.postgresql.model;

import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreSequenceDdlTest {
    private String body(long start, long min, long max, long increment, boolean cycle) throws Exception {
        var info = new PostgreSequence.AdditionalInfo();
        info.setLoaded(true);
        info.setStartValue(start);
        info.setMinValue(min);
        info.setMaxValue(max);
        info.setIncrementBy(increment);
        info.setCacheValue(1);
        info.setCycled(cycle);
        var sequence = new PostgreSequence(mock(PostgreSchema.class)) {
            @Override
            public AdditionalInfo getAdditionalInfo(DBRProgressMonitor monitor) {
                return info;
            }
        };
        var sql = new StringBuilder();
        sequence.getSequenceBody(mock(DBRProgressMonitor.class), sql, false);
        return sql.toString();
    }

    @Test
    void descendingSequencePreservesNegativeIncrement() throws Exception {
        assertTrue(body(-2, -100, -1, -3, false).contains("INCREMENT BY -3"));
    }

    @Test
    void loadedNegativeBoundsAreNotReplacedByDefaults() throws Exception {
        String ddl = body(-2, -100, -1, -3, false);
        assertTrue(ddl.contains("MINVALUE -100"));
        assertTrue(ddl.contains("MAXVALUE -1"));
        assertTrue(ddl.contains("START -2"));
        assertFalse(ddl.contains("NO MINVALUE"));
        assertFalse(ddl.contains("NO MAXVALUE"));
    }

    @Test
    void zeroMaximumIsARealLoadedBound() throws Exception {
        assertTrue(body(-1, -100, 0, -1, false).contains("MAXVALUE 0"));
    }

    @Test
    void ascendingSequenceRetainsExplicitCycleAndCache() throws Exception {
        String ddl = body(10, 1, 100, 3, true);
        assertTrue(ddl.contains("INCREMENT BY 3"));
        assertTrue(ddl.contains("MINVALUE 1"));
        assertTrue(ddl.contains("MAXVALUE 100"));
        assertTrue(ddl.contains("START 10"));
        assertTrue(ddl.contains("CACHE 1"));
        assertTrue(ddl.endsWith("CYCLE"));
        assertFalse(ddl.contains("NO CYCLE"));
    }
}
