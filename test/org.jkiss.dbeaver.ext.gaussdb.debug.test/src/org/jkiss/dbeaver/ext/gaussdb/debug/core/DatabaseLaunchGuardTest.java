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

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.jkiss.dbeaver.debug.DBGController;
import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.debug.DBGLaunchGuard;
import org.jkiss.dbeaver.debug.core.DatabaseLaunchDelegate;
import org.jkiss.dbeaver.debug.core.model.DatabaseDebugTarget;
import org.jkiss.dbeaver.debug.core.model.DatabaseProcess;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabaseLaunchGuardTest {
    @Test
    void preparesLaunchBeforeAnyControllerProcessOrTargetAndKeepsSavedConfigurationUnchanged() throws Exception {
        var fixture = new Fixture(true);
        when(fixture.guard.prepareLaunch(eq(fixture.container), anyMap(), any(DBRProgressMonitor.class)))
            .thenAnswer(invocation -> {
                fixture.events.add("guard");
                Map<String, Object> attributes = invocation.getArgument(1);
                attributes.put("sessionConsent", "fresh-for-this-launch");
                return true;
            });

        fixture.launch();

        assertEquals(List.of("guard", "controller", "process", "target", "connect"), fixture.events);
        assertEquals("fresh-for-this-launch", fixture.controllerAttributes.get(0).get("sessionConsent"));
        assertEquals("old-consumed-token", fixture.savedAttributes.get("sessionConsent"));
        assertNotSame(fixture.savedAttributes, fixture.controllerAttributes.get(0));
        verify(fixture.launch).addDebugTarget(fixture.target);
    }

    @Test
    void declinedAcknowledgementReturnsCleanlyWithoutCancelingCallerOrCreatingDebugResources() throws Exception {
        var fixture = new Fixture(true);
        when(fixture.guard.prepareLaunch(eq(fixture.container), anyMap(), any(DBRProgressMonitor.class)))
            .thenAnswer(invocation -> {
                fixture.events.add("guard");
                return false;
            });

        assertDoesNotThrow(() -> { fixture.launch(); });

        assertEquals(List.of("guard"), fixture.events);
        verify(fixture.monitor, never()).setCanceled(anyBoolean());
        verifyNoInteractions(fixture.controller, fixture.process, fixture.target, fixture.launch);
        assertTrue(fixture.controllerAttributes.isEmpty());
    }

    @Test
    void providerWithoutGuardKeepsExistingLaunchBehavior() throws Exception {
        var fixture = new Fixture(false);

        fixture.launch();

        assertEquals(List.of("controller", "process", "target", "connect"), fixture.events);
        assertEquals(fixture.savedAttributes, fixture.controllerAttributes.get(0));
        verifyNoInteractions(fixture.guard);
        verify(fixture.target).connect(fixture.monitor);
        verify(fixture.launch).addDebugTarget(fixture.target);
    }

    @Test
    void nullProgressMonitorIsNormalizedForGuardAndTarget() throws Exception {
        var fixture = new Fixture(true);
        when(fixture.guard.prepareLaunch(eq(fixture.container), anyMap(), any(DBRProgressMonitor.class)))
            .thenAnswer(invocation -> {
                fixture.events.add("guard");
                assertNotNull(invocation.getArgument(2));
                return true;
            });

        assertDoesNotThrow(() -> {
            fixture.launch(fixture.configuration, "debug", fixture.launch, null);
        });

        assertEquals(List.of("guard", "controller", "process", "target", "connect"), fixture.events);
        verify(fixture.target).connect(isA(NullProgressMonitor.class));
        verify(fixture.launch).addDebugTarget(fixture.target);
        verifyNoInteractions(fixture.monitor);
        assertEquals("old-consumed-token", fixture.savedAttributes.get("sessionConsent"));
    }

    @Test
    void declinedAcknowledgementWithNullMonitorDoesNotCreateDebugResources() throws Exception {
        var fixture = new Fixture(true);
        when(fixture.guard.prepareLaunch(eq(fixture.container), anyMap(), any(DBRProgressMonitor.class)))
            .thenReturn(false);

        assertDoesNotThrow(() -> {
            fixture.launch(fixture.configuration, "debug", fixture.launch, null);
        });

        verify(fixture.guard).prepareLaunch(eq(fixture.container), anyMap(), any(DBRProgressMonitor.class));
        verifyNoInteractions(fixture.controller, fixture.process, fixture.target, fixture.launch, fixture.monitor);
        assertTrue(fixture.controllerAttributes.isEmpty());
    }

    @Test
    void eachToolbarRelaunchGetsItsOwnMutableAttributesInsteadOfReusingConsumedConsent() throws Exception {
        var fixture = new Fixture(true);
        var runs = new AtomicInteger();
        when(fixture.guard.prepareLaunch(eq(fixture.container), anyMap(), any(DBRProgressMonitor.class)))
            .thenAnswer(invocation -> {
                Map<String, Object> attributes = invocation.getArgument(1);
                assertEquals("old-consumed-token", attributes.get("sessionConsent"));
                attributes.put("sessionConsent", "launch-" + runs.incrementAndGet());
                return true;
            });

        fixture.launch();
        fixture.launch();

        assertEquals(2, runs.get());
        assertEquals("launch-1", fixture.controllerAttributes.get(0).get("sessionConsent"));
        assertEquals("launch-2", fixture.controllerAttributes.get(1).get("sessionConsent"));
        assertEquals("old-consumed-token", fixture.savedAttributes.get("sessionConsent"));
        verify(fixture.target, times(2)).connect(fixture.monitor);
    }

    @Test
    void preparationFailureDoesNotCreateProcessOrMutateSavedConfiguration() throws Exception {
        var fixture = new Fixture(true);
        when(fixture.guard.prepareLaunch(eq(fixture.container), anyMap(), any(DBRProgressMonitor.class)))
            .thenAnswer(invocation -> {
                fixture.events.add("guard");
                Map<String, Object> attributes = invocation.getArgument(1);
                attributes.put("sessionConsent", "temporary");
                throw new DBGException("Unable to resolve the selected Package member");
            });

        CoreException error = assertThrows(CoreException.class, () -> { fixture.launch(); });

        assertTrue(error.getMessage().contains("Unable to resolve the selected Package member"));
        assertEquals(List.of("guard"), fixture.events);
        verify(fixture.monitor, never()).setCanceled(anyBoolean());
        assertEquals("old-consumed-token", fixture.savedAttributes.get("sessionConsent"));
        verifyNoInteractions(fixture.controller, fixture.process, fixture.target, fixture.launch);
    }

    @Test
    void alreadyCanceledLaunchDoesNotPromptOrCreateDebugResources() throws Exception {
        var fixture = new Fixture(true);
        when(fixture.monitor.isCanceled()).thenReturn(true);

        fixture.launch();

        assertTrue(fixture.events.isEmpty());
        verifyNoInteractions(fixture.guard, fixture.controller, fixture.process, fixture.target, fixture.launch);
    }

    @Test
    void cancellationWhilePromptingPreventsResourceCreationEvenIfGuardReturnsTrue() throws Exception {
        var fixture = new Fixture(true);
        var canceled = new java.util.concurrent.atomic.AtomicBoolean();
        when(fixture.monitor.isCanceled()).thenAnswer(invocation -> canceled.get());
        when(fixture.guard.prepareLaunch(eq(fixture.container), anyMap(), any(DBRProgressMonitor.class)))
            .thenAnswer(invocation -> {
                fixture.events.add("guard");
                canceled.set(true);
                return true;
            });

        fixture.launch();

        assertEquals(List.of("guard"), fixture.events);
        verifyNoInteractions(fixture.controller, fixture.process, fixture.target, fixture.launch);
        assertEquals("old-consumed-token", fixture.savedAttributes.get("sessionConsent"));
    }

    private static final class Fixture extends DatabaseLaunchDelegate {
        final DBPDataSourceContainer container = mock(DBPDataSourceContainer.class,
            withSettings().defaultAnswer(RETURNS_DEEP_STUBS).extraInterfaces(IAdaptable.class));
        final DBGLaunchGuard guard = mock(DBGLaunchGuard.class);
        final DBGController controller = mock(DBGController.class);
        final DatabaseProcess process = mock(DatabaseProcess.class);
        final DatabaseDebugTarget target = mock(DatabaseDebugTarget.class);
        final ILaunchConfiguration configuration = mock(ILaunchConfiguration.class);
        final ILaunch launch = mock(ILaunch.class);
        final IProgressMonitor monitor = mock(IProgressMonitor.class);
        final Map<String, Object> savedAttributes = Map.of("sessionConsent", "old-consumed-token", "routine", "package.member");
        final List<String> events = new ArrayList<>();
        final List<Map<String, Object>> controllerAttributes = new ArrayList<>();

        Fixture(boolean installGuard) throws Exception {
            when(container.getDriver().getProviderId()).thenReturn("postgresql");
            when(((IAdaptable) container).getAdapter(DBGLaunchGuard.class)).thenReturn(installGuard ? guard : null);
            when(configuration.getAttributes()).thenReturn(savedAttributes);
            when(configuration.getName()).thenReturn("Repeat Package debug");
            doAnswer(invocation -> {
                events.add("connect");
                return null;
            }).when(target).connect(any(IProgressMonitor.class));
        }

        void launch() throws CoreException {
            launch(configuration, "debug", launch, monitor);
        }

        @Override
        protected DBPDataSourceContainer getDataSourceContainer(ILaunchConfiguration configuration) {
            return container;
        }

        @Override
        protected DBGController createController(DBPDataSourceContainer container, Map<String, Object> attributes) {
            events.add("controller");
            controllerAttributes.add(new HashMap<>(attributes));
            return controller;
        }

        @Override
        protected DatabaseProcess createProcess(ILaunch launch, String name) {
            events.add("process");
            return process;
        }

        @Override
        protected DatabaseDebugTarget createDebugTarget(ILaunch launch, DBGController controller, DatabaseProcess process) {
            events.add("target");
            return target;
        }
    }
}
