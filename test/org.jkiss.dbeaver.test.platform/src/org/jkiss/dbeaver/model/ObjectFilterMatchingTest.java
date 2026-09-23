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
package org.jkiss.dbeaver.model;

import org.jkiss.dbeaver.model.struct.DBSObjectFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ObjectFilterMatchingTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactIncludeHonorsCaseAndTreatsRegexSymbolsLiterally(boolean sensitive) {
        var filter = new DBSObjectFilter();
        filter.setCaseSensitive(sensitive);
        filter.setInclude(List.of("Bank.+", "中文表"));
        assertTrue(filter.matches("Bank.+"));
        assertTrue(filter.matches("中文表"));
        assertEquals(!sensitive, filter.matches("bank.+"));
        assertFalse(filter.matches("BankABC"));
        assertEquals(!sensitive, filter.matchesAny("unrelated", "bank.+"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactExcludeHonorsCaseAndWinsAcrossAlternativeNames(boolean sensitive) {
        var filter = new DBSObjectFilter();
        filter.setCaseSensitive(sensitive);
        filter.setInclude(List.of("%"));
        filter.setExclude(List.of("Bank"));
        assertFalse(filter.matches("Bank"));
        assertEquals(sensitive, filter.matches("bank"));
        assertFalse(filter.matchesAny("public.Bank", "Bank"));
        assertEquals(sensitive, filter.matchesAny("public.bank", "bank"));
    }

    @Test
    void togglingCaseSensitivityInvalidatesWarmIncludePatternsInBothDirections() {
        var filter = new DBSObjectFilter();
        filter.setInclude(List.of("Bank%"));
        assertTrue(filter.matches("banking"));
        filter.setCaseSensitive(true);
        assertFalse(filter.matches("banking"));
        assertTrue(filter.matches("Banking"));
        filter.setCaseSensitive(false);
        assertTrue(filter.matches("banking"));
    }

    @Test
    void togglingCaseSensitivityInvalidatesWarmExcludePatternsInBothDirections() {
        var filter = new DBSObjectFilter();
        filter.setExclude(List.of("Bank%"));
        assertFalse(filter.matches("banking"));
        filter.setCaseSensitive(true);
        assertTrue(filter.matches("banking"));
        assertFalse(filter.matches("Banking"));
        filter.setCaseSensitive(false);
        assertFalse(filter.matches("banking"));
    }

    @Test
    void replacingMasksInvalidatesCachedPatternsWithoutRecreatingFilter() {
        var filter = new DBSObjectFilter();
        filter.setInclude(List.of("Bank%"));
        filter.setExclude(List.of("BankPrivate%"));
        assertTrue(filter.matches("BankPublic"));
        assertFalse(filter.matches("BankPrivate"));
        filter.setInclude(List.of("Other%"));
        filter.setExclude(List.of());
        assertFalse(filter.matches("BankPublic"));
        assertTrue(filter.matches("OtherPublic"));
    }

    @Test
    void copiedFilterKeepsIndependentMasksAndCaseSetting() {
        var original = new DBSObjectFilter();
        original.setInclude(List.of("Bank%"));
        original.setCaseSensitive(true);
        assertFalse(original.matches("banking"));
        var copy = new DBSObjectFilter(original);
        copy.setCaseSensitive(false);
        copy.addInclude("Other%");
        assertTrue(copy.matches("banking"));
        assertTrue(copy.matches("OtherPublic"));
        assertFalse(original.matches("banking"));
        assertFalse(original.matches("OtherPublic"));
    }
}
