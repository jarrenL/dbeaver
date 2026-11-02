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

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Validates the installed extension registry without constructing a workbench. */
class DatabaseLaunchRegistrationTest {
    private static final String TYPE_ID = "org.jkiss.dbeaver.debug.launchConfiguration";

    @Test
    void databaseLaunchTypeAdvertisesOnlyItsImplementedDebugMode() {
        ILaunchConfigurationType type = databaseType();

        assertTrue(type.supportsMode(ILaunchManager.DEBUG_MODE));
        assertFalse(type.supportsMode(ILaunchManager.RUN_MODE),
            "The database delegate creates a debug target and does not implement an independent Run launch");
        assertEquals(Set.of(Set.of(ILaunchManager.DEBUG_MODE)), type.getSupportedModeCombinations());
    }

    @Test
    void everyAdvertisedModeCombinationHasARegisteredConfigurationPage() {
        var combinations = databaseType().getSupportedModeCombinations();
        assertFalse(combinations.isEmpty());
        IConfigurationElement[] groups = databaseTabGroups();
        assertTrue(groups.length > 0, "The database debug configuration tab group must be installed");

        for (Set<String> combination : combinations) {
            boolean matched = Arrays.stream(groups).anyMatch(group -> {
                IConfigurationElement[] modes = group.getChildren("launchMode");
                // Eclipse uses a group without launchMode children as its all-modes fallback.
                return modes.length == 0 || Arrays.stream(modes).anyMatch(mode ->
                    combination.equals(Arrays.stream(mode.getAttribute("mode").split(","))
                        .map(String::trim).collect(Collectors.toSet())));
            });
            assertTrue(matched, "No tab group for advertised database launch modes " + combination);
        }
    }

    @Test
    void registeredTabGroupClassIsIncludedInItsContributingBundle() {
        IConfigurationElement[] groups = databaseTabGroups();
        assertTrue(groups.length > 0);
        for (IConfigurationElement group : groups) {
            String className = group.getAttribute("class");
            assertNotNull(className);
            assertFalse(className.isBlank());
            var bundle = Platform.getBundle(group.getContributor().getName());
            assertNotNull(bundle, "Tab group contributor must be installed");
            assertNotNull(bundle.getEntry(className.replace('.', '/') + ".class"),
                "The installed tab group class is missing: " + className);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "org.eclipse.debug.ui.launchGroup.debug",
        "org.jkiss.dbeaver.debug.launchGroup"
    })
    void standardAndDatabaseDebugDialogsKeepTheirDebugMode(String groupId) {
        IConfigurationElement group = Arrays.stream(Platform.getExtensionRegistry().getConfigurationElementsFor(
            "org.eclipse.debug.ui.launchGroups"))
            .filter(element -> groupId.equals(element.getAttribute("id"))).findFirst().orElseThrow();

        assertEquals(ILaunchManager.DEBUG_MODE, group.getAttribute("mode"));
        assertTrue(databaseType().supportsMode(group.getAttribute("mode")));
    }

    private static ILaunchConfigurationType databaseType() {
        ILaunchConfigurationType type = DebugPlugin.getDefault().getLaunchManager().getLaunchConfigurationType(TYPE_ID);
        assertNotNull(type, "The installed database debug launch type is missing");
        return type;
    }

    private static IConfigurationElement[] databaseTabGroups() {
        return Arrays.stream(Platform.getExtensionRegistry().getConfigurationElementsFor(
                "org.eclipse.debug.ui.launchConfigurationTabGroups"))
            .filter(element -> TYPE_ID.equals(element.getAttribute("type")))
            .toArray(IConfigurationElement[]::new);
    }
}
