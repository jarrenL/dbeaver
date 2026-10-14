/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
import java.util.List;

/** Standalone contract checks for live-runner mode selection; no database is required. */
public class LiveDebugModesTest extends LiveDebug {
    private static int assertions;

    private static void expect(List<String> expected, String configured) {
        props.clear();
        if (configured != null) {
            props.setProperty("review.modes", configured);
        }
        if (!expected.equals(reviewModes())) {
            throw new AssertionError("Expected " + expected + " for review.modes=" + configured
                + " but got " + reviewModes());
        }
        assertions++;
    }

    private static void reject(String configured) {
        props.clear();
        props.setProperty("review.modes", configured);
        try {
            reviewModes();
            throw new AssertionError("Expected review.modes to be rejected: [" + configured + "]");
        } catch (IllegalArgumentException expected) {
            assertions++;
        }
    }

    public static void main(String[] args) {
        expect(List.of("ora", "mysql", "pg"), null);
        expect(List.of("ora"), "ora");
        expect(List.of("ora", "pg"), " ORA, pg,ora ");
        reject("");
        reject("   ");
        reject("ora,,pg");
        reject("m");
        System.out.println("TOTAL MODE CHECKS " + assertions);
    }
}
