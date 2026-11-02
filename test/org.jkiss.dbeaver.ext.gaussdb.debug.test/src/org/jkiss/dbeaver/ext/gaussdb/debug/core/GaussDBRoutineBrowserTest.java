/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.jkiss.dbeaver.ext.gaussdb.model.*;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class GaussDBRoutineBrowserTest {
    @Test
    void packageIsTraversableEvenThoughItIsNotAnObjectContainer() {
        assertFalse(DBSObjectContainer.class.isAssignableFrom(GaussDBPackage.class));
        assertTrue(allowed(GaussDBPackage.class));
    }

    @Test
    void procedureAndFunctionFoldersRemainTraversable() {
        assertTrue(allowed(GaussDBProcedure.class));
        assertTrue(allowed(GaussDBFunction.class));
    }

    @Test
    void databaseAndSchemaRemainTraversableButTablesAreExcluded() {
        assertTrue(allowed(GaussDBDatabase.class));
        assertTrue(allowed(GaussDBSchema.class));
        assertFalse(allowed(GaussDBTable.class));
    }

    @Test
    void callersCannotMutateTheNextBrowserWhitelist() {
        GaussDBDebugCore.getRoutineBrowserTypes()[0] = Object.class;
        assertFalse(allowed(Object.class));
        assertFalse(allowed(GaussDBTable.class));
    }

    private static boolean allowed(Class<?> candidate) {
        return Arrays.stream(GaussDBDebugCore.getRoutineBrowserTypes()).anyMatch(type -> type.isAssignableFrom(candidate));
    }
}
