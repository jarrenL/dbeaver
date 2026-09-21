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
