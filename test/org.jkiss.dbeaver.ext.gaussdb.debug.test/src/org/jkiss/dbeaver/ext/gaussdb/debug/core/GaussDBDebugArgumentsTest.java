/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreProcedureParameter;
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
        when(parameter.getName()).thenReturn("p");
        when(parameter.getFullTypeName()).thenReturn("text");
        when(parameter.getDefaultValue()).thenReturn(defaultValue);
        return parameter;
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
