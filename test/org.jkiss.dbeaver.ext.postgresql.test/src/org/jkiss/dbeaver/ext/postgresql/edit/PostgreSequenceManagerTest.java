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
        void validateUnsupportedRestart() throws Exception {
            var sequence = mock(PostgreSequence.class);
            when(sequence.getName()).thenReturn("sequence_name");
            when(sequence.supportsSequenceRestart()).thenReturn(false);
            var command = new ObjectChangeCommand(sequence) {
                @Override
                public Map<Object, Object> getProperties() {
                    return Map.of("lastValue", 0L);
                }
            };
            validateObjectProperties(new VoidProgressMonitor(), command, Map.of());
        }

        String change(Map<Object, Object> properties) throws Exception {
            return change(properties, true);
        }

        String change(Map<Object, Object> properties, boolean restartSupported) throws Exception {
            var sequence = mock(PostgreSequence.class);
            when(sequence.supportsSequenceRestart()).thenReturn(restartSupported);
            when(sequence.getFullyQualifiedName(DBPEvaluationContext.DDL)).thenReturn("\"业务\".\"流水 号\"");
            var command = new ObjectChangeCommand(sequence) {
                @Override
                public Map<Object, Object> getProperties() {
                    return properties;
                }
            };
            var actions = new ArrayList<DBEPersistAction>();
            try {
                addObjectModifyActions(new VoidProgressMonitor(), mock(DBCExecutionContext.class), actions, command, Map.of());
            } catch (org.jkiss.dbeaver.DBException failure) {
                assertTrue(actions.isEmpty(), "Unsupported restart must not leave partial ALTER actions");
                throw failure;
            }
            assertTrue(actions.size() <= 1);
            return actions.isEmpty() ? null : actions.get(0).getScript();
        }
    }

    @Test
    void completeModificationPreservesZeroNegativeValuesAndQualifiedName() throws Exception {
        assertEquals("ALTER SEQUENCE \"业务\".\"流水 号\"\n\tINCREMENT BY -3\n\tMINVALUE -100"
            + "\n\tMAXVALUE 0\n\tSTART -90\n\tRESTART -30\n\tCACHE 64\n\tNO CYCLE",
            new Manager().change(Map.of("incrementBy", -3L, "minValue", -100L, "maxValue", 0L,
                "startValue", -90L, "lastValue", -30L, "cacheValue", 64L, "cycled", false)));
    }

    @Test
    void cycleEnableDoesNotResetUnchangedSequenceProperties() throws Exception {
        assertEquals("ALTER SEQUENCE \"业务\".\"流水 号\"\n\tCYCLE", new Manager().change(Map.of("cycled", true)));
    }

    @Test
    void emptyOrUnrelatedChangesDoNotEmitAnEmptyAlterStatement() throws Exception {
        assertNull(new Manager().change(Map.of()));
        assertNull(new Manager().change(Map.of("description", "仅修改说明")));
    }

    @Test
    void nullLastValueDoesNotGenerateRestartNull() throws Exception {
        var properties = new LinkedHashMap<Object, Object>();
        properties.put("lastValue", null);
        properties.put("incrementBy", 2L);
        assertEquals("ALTER SEQUENCE \"业务\".\"流水 号\"\n\tINCREMENT BY 2", new Manager().change(properties));
    }

    @Test
    void unsupportedRestartRejectsTheWholeChangeBeforeGeneratingSql() {
        var failure = assertThrows(org.jkiss.dbeaver.DBException.class,
            () -> new Manager().change(Map.of("lastValue", 0L, "incrementBy", 3L), false));
        assertEquals(org.jkiss.dbeaver.ext.postgresql.internal.PostgreSQLMessages.sequence_restart_not_supported,
            failure.getMessage());
    }

    @Test
    void commandValidationAlsoRejectsUnsupportedRestart() {
        assertThrows(org.jkiss.dbeaver.DBException.class, () -> new Manager().validateUnsupportedRestart());
    }

    @Test
    void unsupportedRestartDoesNotBlockOtherSequenceOptions() throws Exception {
        assertEquals("ALTER SEQUENCE \"业务\".\"流水 号\"\n\tCYCLE",
            new Manager().change(Map.of("cycled", true), false));
    }

    @Test
    void currentValuePropertyRemainsVisibleButUsesRestartCapabilityForEditing() throws Exception {
        var getter = PostgreSequence.AdditionalInfo.class.getMethod("getLastValue");
        var property = getter.getAnnotation(org.jkiss.dbeaver.model.meta.Property.class);
        var descriptor = new org.jkiss.dbeaver.runtime.properties.ObjectPropertyDescriptor(
            null, null, property, getter, "en", false);
        var source = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource.class);
        var schema = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreSchema.class);
        var server = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreServerExtension.class);
        when(schema.getDataSource()).thenReturn(source);
        when(source.getServerType()).thenReturn(server);
        var sequence = new PostgreSequence(schema);
        when(server.supportsSequenceRestart()).thenReturn(false);
        assertTrue(descriptor.isViewable());
        assertFalse(descriptor.isEditPossible(sequence));
        when(server.supportsSequenceRestart()).thenReturn(true);
        assertTrue(descriptor.isEditPossible(sequence));
        assertEquals(property.editableExpr(), property.updatableExpr());
    }
}
