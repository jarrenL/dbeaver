/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataType;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreProcedure;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreProcedureParameter;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreSchema;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBDebugArgumentsTest {
    private PostgreProcedureParameter directed(org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameterKind kind) {
        var p = parameter(null);
        when(p.getParameterKind()).thenReturn(kind);
        return p;
    }

    @Test
    void procedureOutBeforeBetweenAndAfterInputsDoesNotConsumeBindings() throws Exception {
        var input = directed(org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameterKind.IN);
        var output = directed(org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameterKind.OUT);
        var plan = GaussDBDebugArguments.buildProcedure(List.of(output, input, output, input, output),
            List.of("first", "second"), List.of());
        assertEquals("NULL::text,?::text,NULL::text,?::text,NULL::text", plan.sql());
        assertEquals(List.of("first", "second"), plan.values());
    }

    @Test
    void inoutRemainsAnInputBinding() throws Exception {
        var plan = GaussDBDebugArguments.buildProcedure(
            List.of(directed(org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameterKind.INOUT)),
            List.of("original"), List.of());
        assertEquals("?::text", plan.sql());
        assertEquals(List.of("original"), plan.values());
    }

    @Test
    void outOnlyProcedureHasNoJdbcParameters() throws Exception {
        var plan = GaussDBDebugArguments.buildProcedure(
            List.of(directed(org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameterKind.OUT)), List.of(), List.of());
        assertEquals("NULL::text", plan.sql());
        assertTrue(plan.values().isEmpty());
    }

    @Test
    void omittedInputBeforeOutIsRejectedInsteadOfShiftingPositions() {
        var input = directed(org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameterKind.IN);
        when(input.getDefaultValue()).thenReturn("'default'");
        assertThrows(DBGException.class, () -> GaussDBDebugArguments.buildProcedure(
            List.of(input, directed(org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameterKind.OUT)),
            List.of(""), List.of("DEFAULT")));
    }

    @Test
    void trailingDefaultAfterOutCanBeOmitted() throws Exception {
        var input = directed(org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameterKind.IN);
        when(input.getDefaultValue()).thenReturn("'default'");
        var plan = GaussDBDebugArguments.buildProcedure(
            List.of(directed(org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameterKind.OUT), input),
            List.of(""), List.of("DEFAULT"));
        assertEquals("NULL::text", plan.sql());
        assertTrue(plan.values().isEmpty());
    }

    @Test
    void unknownDirectionDoesNotSilentlyDropParameters() {
        assertThrows(DBGException.class, () -> GaussDBDebugArguments.buildProcedure(
            List.of(directed(org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameterKind.UNKNOWN)), List.of(), List.of()));
    }

    @Test
    void historicalNumericAndUnicodeBoundaryValuesRemainBoundData() throws Exception {
        for (String value : List.of("-9223372036854775808", "9223372036854775807",
            "0.123456789012345678901234567890123456", "中文𠀀", "a'; DROP TABLE t; --", "a\nb\\c")) {
            var p = parameter(null);
            when(p.getFullTypeName()).thenReturn("numeric");
            var plan = GaussDBDebugArguments.build(List.of(p), List.of(value), List.of("VALUE"));
            assertEquals("?::numeric", plan.sql());
            assertEquals(List.of(value), plan.values());
        }
    }

    @Test
    void noParametersProduceNoBindings() throws Exception {
        var plan = GaussDBDebugArguments.build(List.of(), List.of(), List.of());
        assertEquals("", plan.sql());
        assertTrue(plan.values().isEmpty());
    }

    @Test
    void mixedNullAndValuePreservePositionAndType() throws Exception {
        var number = parameter(null);
        when(number.getFullTypeName()).thenReturn("numeric");
        var plan = GaussDBDebugArguments.build(List.of(number, parameter(null), number),
            List.of("unused", "中文", "111"), List.of("NULL", "VALUE", "VALUE"));
        assertEquals("?::numeric,?::text,?::numeric", plan.sql());
        assertEquals(Arrays.asList(null, "中文", "111"), plan.values());
    }

    @Test
    void planIsAnImmutableSnapshotIncludingSqlNull() {
        var values = new java.util.ArrayList<String>(Arrays.asList("first", null));
        var plan = new GaussDBDebugArguments.Plan("?::text,?::text", values);
        values.set(0, "changed");
        assertEquals(Arrays.asList("first", null), plan.values());
        assertThrows(UnsupportedOperationException.class, () -> plan.values().set(0, "changed"));
    }

    @Test
    void invalidModeCountsAndNullModesAreRejected() {
        assertThrows(DBGException.class, () -> GaussDBDebugArguments.build(
            List.of(parameter(null)), List.of("x"), List.of("VALUE", "NULL")));
        assertThrows(DBGException.class, () -> GaussDBDebugArguments.build(
            List.of(parameter(null)), List.of("x"), Arrays.asList((String) null)));
    }

    @Test
    void explicitNullCannotFollowAnOmittedDefault() {
        assertThrows(DBGException.class, () -> GaussDBDebugArguments.build(
            List.of(parameter("1"), parameter(null)), List.of("", ""), List.of("DEFAULT", "NULL")));
    }

    private PostgreProcedureParameter parameter(String defaultValue) {
        var parameter = mock(PostgreProcedureParameter.class);
        var type = dataType("pg_catalog", "text", "text");
        when(parameter.getName()).thenReturn("p");
        when(parameter.getParameterType()).thenReturn(type);
        when(parameter.getFullTypeName()).thenReturn("text");
        when(parameter.getDefaultValue()).thenReturn(defaultValue);
        return parameter;
    }

    private PostgreDataType dataType(String schemaName, String name, String fullTypeName) {
        var schema = mock(PostgreSchema.class);
        when(schema.getName()).thenReturn(schemaName);
        var type = mock(PostgreDataType.class);
        when(type.getParentObject()).thenReturn(schema);
        when(type.getName()).thenReturn(name);
        when(type.getTypeName()).thenReturn(name);
        when(type.getFullTypeName()).thenReturn(fullTypeName);
        return type;
    }

    private PostgreProcedureParameter typedParameter(PostgreDataType type, PostgreProcedure.ArgumentMode mode) {
        // Use the real parameter's metadata delegation, rather than stubbing the
        // final cast expression that the regression is meant to exercise.
        return new PostgreProcedureParameter(mock(PostgreProcedure.class), "p", type, mode, 1);
    }

    @Test
    void parameterTypeSchemaDistinguishesSameNamedTypesIndependentlyOfRoutineSchema() throws Exception {
        var first = dataType("qa_objects", "person_t", "person_t");
        var second = dataType("other_types", "person_t", "person_t");
        when(first.getObjectId()).thenReturn(51001L);
        when(second.getObjectId()).thenReturn(51002L);
        var monitor = mock(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor.class);
        var database = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreDatabase.class);
        when(database.getDataType(monitor, 51001L)).thenReturn(first);
        when(database.getDataType(monitor, 51002L)).thenReturn(second);
        var routineSchema = mock(PostgreSchema.class);
        when(routineSchema.getName()).thenReturn("routine_schema");
        when(routineSchema.getDatabase()).thenReturn(database);
        var source = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource.class, RETURNS_DEEP_STUBS);
        when(routineSchema.getDataSource()).thenReturn(source);
        var argumentTypes = mock(java.sql.Array.class);
        when(argumentTypes.getArray()).thenReturn(new Long[]{51001L, 51002L});
        var row = mock(java.sql.ResultSet.class);
        when(row.getArray("proallargtypes")).thenReturn(argumentTypes);
        when(row.getString("proname")).thenReturn("object_demo");
        var routine = new PostgreProcedure(monitor, routineSchema, row);

        var plan = GaussDBDebugArguments.buildProcedure(routine.getParameters(monitor), List.of("(1,Alice)", "(2,Bob)"), List.of());

        assertEquals("?::\"qa_objects\".\"person_t\",?::\"other_types\".\"person_t\"", plan.sql());
        assertEquals(List.of("(1,Alice)", "(2,Bob)"), plan.values());
        assertSame(routineSchema, routine.getContainer());
        verify(database).getDataType(monitor, 51001L);
        verify(database).getDataType(monitor, 51002L);
        verify(database, never()).getDataType(any(), anyString());
        verify(first, never()).getDatabase();
        verify(second, never()).getDatabase();
    }

    @Test
    void catalogNumericVarcharAndMultiwordAliasesKeepExistingModifiers() throws Exception {
        for (String[] type : List.of(
            new String[]{"numeric", "numeric(38,12)"},
            new String[]{"varchar", "varchar(80)"},
            new String[]{"character varying", "character varying(80)"},
            new String[]{"double precision", "double precision"},
            new String[]{"_int4", "_int4"})) {
            var parameter = typedParameter(dataType("pg_catalog", type[0], type[1]), PostgreProcedure.ArgumentMode.i);
            var plan = GaussDBDebugArguments.build(List.of(parameter), List.of("value"), List.of());
            assertEquals("?::" + type[1], plan.sql());
            assertEquals(List.of("value"), plan.values());
        }
    }

    @Test
    void rawCatalogIdentifiersAreQuotedAndEscapedAsSeparateNameParts() throws Exception {
        var parameter = typedParameter(
            dataType("Qa.\"Schema\"", "Person\"; DROP TABLE t; --", "Person\"; DROP TABLE t; --"),
            PostgreProcedure.ArgumentMode.i);
        var plan = GaussDBDebugArguments.build(List.of(parameter), List.of("value'); SELECT 1; --"), List.of());
        assertEquals("?::\"Qa.\"\"Schema\"\"\".\"Person\"\"; DROP TABLE t; --\"", plan.sql());
        assertEquals(List.of("value'); SELECT 1; --"), plan.values());

        var quotedName = typedParameter(dataType("\"Schema\"", "\"Type\"", "\"Type\""), PostgreProcedure.ArgumentMode.i);
        assertEquals("?::\"\"\"Schema\"\"\".\"\"\"Type\"\"\"",
            GaussDBDebugArguments.build(List.of(quotedName), List.of("x"), List.of()).sql());
    }

    @Test
    void arraysAndDomainsRetainTheirResolvedTypeIdentity() throws Exception {
        var array = dataType("qa_types", "_person_t", "_person_t");
        var domain = dataType("qa_domains", "person_list", "person_list");
        when(array.isArray()).thenReturn(true);
        var plan = GaussDBDebugArguments.build(List.of(
            typedParameter(array, PostgreProcedure.ArgumentMode.i), typedParameter(domain, PostgreProcedure.ArgumentMode.i)),
            List.of("{\"(1,Alice)\"}", "{\"(2,Bob)\"}"), List.of());

        assertEquals("?::\"qa_types\".\"_person_t\",?::\"qa_domains\".\"person_list\"", plan.sql());
        verify(array, never()).getElementType(any());
        verify(domain, never()).getBaseType(any());
        verify(domain, never()).getElementType(any());
    }

    @Test
    void userTypeNameCharactersAndSafeModifiersArePreserved() throws Exception {
        var parameter = typedParameter(dataType("qa_types", "Type(1)[]", "Type(1)[](12,-2)[][]"),
            PostgreProcedure.ArgumentMode.i);
        assertEquals("?::\"qa_types\".\"Type(1)[]\"(12,-2)[][]",
            GaussDBDebugArguments.build(List.of(parameter), List.of("x"), List.of()).sql());
    }

    @Test
    void invalidUserTypeModifiersAndMissingMetadataAreRejected() {
        for (String fullTypeName : List.of("person_t(12); SELECT 1", "person_t[3]", "other_t")) {
            var parameter = typedParameter(dataType("qa_types", "person_t", fullTypeName), PostgreProcedure.ArgumentMode.i);
            assertThrows(DBGException.class, () -> GaussDBDebugArguments.build(List.of(parameter), List.of("x"), List.of()));
        }
        var missing = mock(PostgreProcedureParameter.class);
        assertThrows(DBGException.class, () -> GaussDBDebugArguments.build(List.of(missing), List.of("x"), List.of()));
    }

    @Test
    void outUserTypeAndDefaultSuffixKeepPositionalBindings() throws Exception {
        var type = dataType("qa_types", "person_t", "person_t");
        var input = typedParameter(type, PostgreProcedure.ArgumentMode.i);
        var output = typedParameter(type, PostgreProcedure.ArgumentMode.o);
        var defaulted = typedParameter(type, PostgreProcedure.ArgumentMode.i);
        defaulted.setDefaultValue("make_person()");
        var plan = GaussDBDebugArguments.buildProcedure(List.of(input, output, defaulted),
            List.of("(1,Alice)", "ignored"), List.of("VALUE", "DEFAULT"));
        assertEquals("?::\"qa_types\".\"person_t\",NULL::\"qa_types\".\"person_t\"", plan.sql());
        assertEquals(List.of("(1,Alice)"), plan.values());
    }

    @Test
    void emptyTextAndLiteralNullRemainValues() throws Exception {
        var plan = GaussDBDebugArguments.build(List.of(parameter(null), parameter(null)),
            List.of("", "NULL"), List.of("VALUE", "VALUE"));
        assertEquals("?::text,?::text", plan.sql());
        assertEquals(List.of("", "NULL"), plan.values());
    }

    @Test
    void explicitNullDoesNotInterpolateUserText() throws Exception {
        var plan = GaussDBDebugArguments.build(List.of(parameter(null)), List.of("ignored"), List.of("NULL"));
        assertNull(plan.values().getFirst());
        assertEquals("?::text", plan.sql());
    }

    @Test
    void trailingDefaultsAreOmittedRatherThanPastedIntoSql() throws Exception {
        var plan = GaussDBDebugArguments.build(List.of(parameter(null), parameter("dangerous_function()")),
            List.of("hello'", "ignored"), List.of("VALUE", "DEFAULT"));
        assertEquals("?::text", plan.sql());
        assertEquals(List.of("hello'"), plan.values());
    }

    @Test
    void allDefaultsProduceAnEmptyArgumentList() throws Exception {
        var plan = GaussDBDebugArguments.build(List.of(parameter("10")), List.of(""), List.of("DEFAULT"));
        assertEquals("", plan.sql());
        assertTrue(plan.values().isEmpty());
    }

    @Test
    void rejectsNonTrailingDefaultsAndMissingMetadata() {
        assertThrows(DBGException.class, () -> GaussDBDebugArguments.build(
            List.of(parameter("10"), parameter(null)), List.of("", "x"), List.of("DEFAULT", "VALUE")));
        assertThrows(DBGException.class, () -> GaussDBDebugArguments.build(
            List.of(parameter(null)), List.of(""), List.of("DEFAULT")));
    }

    @Test
    void preservesLegacyNullAndEmptyStringConfigurations() throws Exception {
        var plan = GaussDBDebugArguments.build(List.of(parameter(null), parameter(null)), Arrays.asList(null, ""), List.of());
        assertEquals(Arrays.asList(null, ""), plan.values());
    }

    @Test
    void rejectsMalformedModesAndCounts() {
        assertThrows(DBGException.class, () -> GaussDBDebugArguments.build(List.of(parameter(null)), List.of(), List.of()));
        assertThrows(DBGException.class, () -> GaussDBDebugArguments.build(List.of(parameter(null)), List.of(""), List.of("BAD")));
        assertThrows(DBGException.class, () -> GaussDBDebugArguments.build(List.of(parameter(null)), Arrays.asList((String) null), List.of("VALUE")));
    }
}
