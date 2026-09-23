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
package org.jkiss.dbeaver.ext.postgresql.edit;

import org.jkiss.dbeaver.ext.postgresql.model.PostgreSequence;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreSequenceManagerTest extends org.jkiss.junit.DBeaverUnitTest {
    private static class Manager extends PostgreSequenceManager {
        String change(Map<Object, Object> properties) {
            var sequence = mock(PostgreSequence.class);
            when(sequence.getFullyQualifiedName(DBPEvaluationContext.DDL)).thenReturn("\"业务\".\"流水 号\"");
            var command = new ObjectChangeCommand(sequence) {
                @Override
                public Map<Object, Object> getProperties() {
                    return properties;
                }
            };
            var actions = new ArrayList<DBEPersistAction>();
            addObjectModifyActions(new VoidProgressMonitor(), mock(DBCExecutionContext.class), actions, command, Map.of());
            assertTrue(actions.size() <= 1);
            return actions.isEmpty() ? null : actions.get(0).getScript();
        }
    }

    @Test
    void completeModificationPreservesZeroNegativeValuesAndQualifiedName() {
        assertEquals("ALTER SEQUENCE \"业务\".\"流水 号\"\n\tINCREMENT BY -3\n\tMINVALUE -100"
            + "\n\tMAXVALUE 0\n\tSTART -90\n\tRESTART -30\n\tCACHE 64\n\tNO CYCLE",
            new Manager().change(Map.of("incrementBy", -3L, "minValue", -100L, "maxValue", 0L,
                "startValue", -90L, "lastValue", -30L, "cacheValue", 64L, "cycled", false)));
    }

    @Test
    void cycleEnableDoesNotResetUnchangedSequenceProperties() {
        assertEquals("ALTER SEQUENCE \"业务\".\"流水 号\"\n\tCYCLE", new Manager().change(Map.of("cycled", true)));
    }

    @Test
    void emptyOrUnrelatedChangesDoNotEmitAnEmptyAlterStatement() {
        assertNull(new Manager().change(Map.of()));
        assertNull(new Manager().change(Map.of("description", "仅修改说明")));
    }

    @Test
    void nullLastValueDoesNotGenerateRestartNull() {
        var properties = new LinkedHashMap<Object, Object>();
        properties.put("lastValue", null);
        properties.put("incrementBy", 2L);
        assertEquals("ALTER SEQUENCE \"业务\".\"流水 号\"\n\tINCREMENT BY 2", new Manager().change(properties));
    }
}
