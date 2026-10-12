/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues and consumes acknowledgements for session-local settings required by GaussDB debugging.
 * Tokens are intentionally process-local and single-use so a saved launch configuration cannot
 * silently reuse an old acknowledgement on the next debug run.
 */
public final class GaussDBDebugSessionSettings {
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);
    private static final Map<String, Instant> TOKENS = new ConcurrentHashMap<>();

    private GaussDBDebugSessionSettings() {
    }

    public static boolean requiresConsent(@NotNull GaussDBProcedure routine) {
        return routine.getInvocationSchema() != null;
    }

    @NotNull
    public static String issueConsentToken() {
        purgeExpiredTokens();
        String token = UUID.randomUUID().toString();
        TOKENS.put(token, Instant.now());
        return token;
    }

    public static void requireAndConsumeConsent(
        @NotNull GaussDBProcedure routine,
        @NotNull Map<String, Object> configuration
    ) throws DBGException {
        if (!requiresConsent(routine)) {
            return;
        }
        Object rawToken = configuration.get(GaussDBDebugConstants.ATTR_SESSION_SETTINGS_CONSENT_TOKEN);
        Instant issued = rawToken instanceof String token ? TOKENS.remove(token) : null;
        if (issued == null || issued.plus(TOKEN_LIFETIME).isBefore(Instant.now())) {
            throw new DBGException(
                "Package member debugging requires an explicit acknowledgement for its session setting. " +
                "Open the debug configuration, review SET LOCAL search_path, and confirm it for this launch."
            );
        }
    }

    private static void purgeExpiredTokens() {
        Instant cutoff = Instant.now().minus(TOKEN_LIFETIME);
        TOKENS.values().removeIf(issued -> issued.isBefore(cutoff));
    }
}
