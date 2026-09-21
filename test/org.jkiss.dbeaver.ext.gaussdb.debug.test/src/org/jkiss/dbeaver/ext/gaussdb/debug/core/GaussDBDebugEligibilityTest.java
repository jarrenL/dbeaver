/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.jkiss.dbeaver.ext.gaussdb.model.DBCompatibilityEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GaussDBDebugEligibilityTest {
    @Test
    void rejectsMCompatibilityBeforeStartingSession() {
        assertTrue(GaussDBDebugCore.getRoutineEligibilityError(
            DBCompatibilityEnum.M, true, 16389, "plpgsql").contains("M compatibility"));
    }

    @Test
    void rejectsUnknownCompatibilityBeforeStartingSession() {
        assertTrue(GaussDBDebugCore.getRoutineEligibilityError(
            null, true, 16389, "plpgsql").contains("unknown"));
    }

    @Test
    void rejectsUnsavedAndNonProceduralLanguageRoutines() {
        assertNotNull(GaussDBDebugCore.getRoutineEligibilityError(
            DBCompatibilityEnum.ORACLE, false, 0, "plpgsql"));
        assertTrue(GaussDBDebugCore.getRoutineEligibilityError(
            DBCompatibilityEnum.ORACLE, true, 16389, "sql").contains("language"));
    }

    @Test
    void acceptsSupportedLanguagesOutsideMMode() {
        assertNull(GaussDBDebugCore.getRoutineEligibilityError(
            DBCompatibilityEnum.ORACLE, true, 16389, "plpgsql"));
        assertNull(GaussDBDebugCore.getRoutineEligibilityError(
            DBCompatibilityEnum.POSTGRES, true, 16389, "PLSQL"));
    }
}
