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
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBDebugConsentTokenTest {
    private static final String KEY = GaussDBDebugConstants.ATTR_SESSION_SETTINGS_CONSENT_TOKEN;

    @Test
    void unusedPageAcknowledgementBecomesAnIndependentSingleUseLaunchAcknowledgement() throws Exception {
        String pageToken = GaussDBDebugSessionSettings.issueConsentToken();
        Map<String, Object> local = new HashMap<>(Map.of(KEY, pageToken));
        assertTrue(GaussDBDebugSessionSettings.claimConsentToken(local));
        assertNotEquals(pageToken, local.get(KEY));
        assertDoesNotThrow(() -> GaussDBDebugSessionSettings.requireAndConsumeConsent(packageRoutine(), local));
        assertThrows(DBGException.class, () -> GaussDBDebugSessionSettings.requireAndConsumeConsent(packageRoutine(), local));
    }

    @Test
    void savedAcknowledgementCannotBeReusedOnToolbarRepeat() throws Exception {
        Map<String, Object> saved = Map.of(KEY, GaussDBDebugSessionSettings.issueConsentToken());
        Map<String, Object> first = new HashMap<>(saved);
        Map<String, Object> second = new HashMap<>(saved);
        assertTrue(GaussDBDebugSessionSettings.claimConsentToken(first));
        assertFalse(GaussDBDebugSessionSettings.claimConsentToken(second));
        assertFalse(second.containsKey(KEY));
        assertNotEquals(saved.get(KEY), first.get(KEY));
        assertTrue(saved.containsKey(KEY));
        GaussDBDebugSessionSettings.requireAndConsumeConsent(packageRoutine(), first);
    }

    @Test
    void invalidAndMissingAcknowledgementsRequirePromptWithoutInventingConsent() {
        for (Map<String, Object> local : List.of(new HashMap<String, Object>(),
            new HashMap<String, Object>(Map.of(KEY, "no-longer-valid")), new HashMap<String, Object>(Map.of(KEY, 42)))) {
            assertFalse(GaussDBDebugSessionSettings.claimConsentToken(local));
            assertFalse(local.containsKey(KEY));
            assertThrows(DBGException.class, () -> GaussDBDebugSessionSettings.requireAndConsumeConsent(packageRoutine(), local));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiredPageAcknowledgementCannotBeClaimed() throws Exception {
        String token = GaussDBDebugSessionSettings.issueConsentToken();
        var field = GaussDBDebugSessionSettings.class.getDeclaredField("TOKENS");
        field.setAccessible(true);
        ((Map<String, Instant>) field.get(null)).put(token, Instant.now().minusSeconds(31 * 60));
        Map<String, Object> local = new HashMap<>(Map.of(KEY, token));
        assertFalse(GaussDBDebugSessionSettings.claimConsentToken(local));
        assertFalse(local.containsKey(KEY));
    }

    @Test
    void concurrentLaunchesCanClaimThePageAcknowledgementOnlyOnce() throws Exception {
        Map<String, Object> saved = Map.of(KEY, GaussDBDebugSessionSettings.issueConsentToken());
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        var results = new ArrayList<Future<Boolean>>();
        try {
            for (int i = 0; i < 16; i++) {
                results.add(pool.submit(() -> {
                    gate.await();
                    Map<String, Object> local = new HashMap<>(saved);
                    if (!GaussDBDebugSessionSettings.claimConsentToken(local)) return false;
                    GaussDBDebugSessionSettings.requireAndConsumeConsent(packageRoutine(), local);
                    return true;
                }));
            }
            gate.countDown();
            int granted = 0;
            for (var result : results) if (result.get(5, TimeUnit.SECONDS)) granted++;
            assertEquals(1, granted);
        } finally {
            gate.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void standaloneRoutineStillNeedsNoSessionSettingAcknowledgement() {
        var routine = mock(GaussDBProcedure.class);
        assertDoesNotThrow(() -> GaussDBDebugSessionSettings.requireAndConsumeConsent(routine, Map.of()));
    }

    private static GaussDBProcedure packageRoutine() {
        var routine = mock(GaussDBProcedure.class);
        when(routine.getInvocationSchema()).thenReturn("\"Business Data\"");
        return routine;
    }
}
