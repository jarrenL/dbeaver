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
package org.jkiss.dbeaver.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class VersionUtilsTest {
    @ParameterizedTest
    @CsvSource({
        "1.9,1.10,-1", "26.1.5,26.1.5,0", "1.01,1.1,0",
        "1.0,1.0.1,-1", "1_2,1-2,0", "2.0,1.999,1",
        "1.alpha,1.beta,-1", "1.beta1,1.beta2,-1",
        "1.2147483647,1.2147483646,1",
        "1.999999999,1.10000000000,-1",
        "1.999999999999999999999999999999,1.1000000000000000000000000000000,-1",
        "1.000000000000000000000000000009,1.9,0"
    })
    void comparesComponentsWithoutNumericWidthLimits(String left, String right, int expected) {
        assertEquals(expected, Integer.signum(VersionUtils.compareVersions(left, right)));
        assertEquals(-expected, Integer.signum(VersionUtils.compareVersions(right, left)));
        assertEquals(expected < 0, VersionUtils.isVersionLessThan(left, right));
    }

    @ParameterizedTest
    @CsvSource({
        "1.9;1.10;1.2,1.10", "2.0-beta;1.9;3.0-alpha,1.9",
        "1.0-alpha;1.0-beta,1.0-beta", "1.0;1.0;1.0,1.0",
        "1.999999999;1.10000000000,1.10000000000"
    })
    void selectsLatestWithoutMutatingInputAndIndependentOfOrder(String candidates, String expected) {
        var versions = new ArrayList<>(List.of(candidates.split(";")));
        var original = List.copyOf(versions);
        assertEquals(expected, VersionUtils.findLatestVersion(versions));
        assertEquals(original, versions);
        Collections.reverse(versions);
        assertEquals(expected, VersionUtils.findLatestVersion(List.copyOf(versions)));
    }

    @Test
    void emptyCandidateListHasNoLatestVersion() {
        assertNull(VersionUtils.findLatestVersion(List.of()));
    }
}
