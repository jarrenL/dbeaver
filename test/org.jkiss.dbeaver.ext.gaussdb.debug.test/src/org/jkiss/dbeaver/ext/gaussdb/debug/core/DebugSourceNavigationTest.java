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

import org.eclipse.core.runtime.IAdaptable;
import org.jkiss.dbeaver.debug.DBGController;
import org.jkiss.dbeaver.debug.DBGResolver;
import org.jkiss.dbeaver.debug.core.DebugUtils;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNModel;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DebugSourceNavigationTest {
    private static class Fixture {
        final DBGController controller = mock(DBGController.class);
        final DBGResolver resolver = mock(DBGResolver.class);
        final DBNModel model = mock(DBNModel.class);
        final DBRProgressMonitor monitor = mock(DBRProgressMonitor.class);
        final DBSObject routine = mock(DBSObject.class);
        final DBSObject schema = mock(DBSObject.class);
        final DBNDatabaseNode parent = mock(DBNDatabaseNode.class);
        final DBNDatabaseNode node = mock(DBNDatabaseNode.class);
        final Map<String, Object> configuration = Map.of("launch", "unchanged");

        Fixture() throws Exception {
            var container = mock(DBPDataSourceContainer.class, withSettings().extraInterfaces(IAdaptable.class));
            when(((IAdaptable) container).getAdapter(DBGResolver.class)).thenReturn(resolver);
            when(controller.getDataSourceContainer()).thenReturn(container);
            when(controller.getDebugConfiguration()).thenReturn(configuration);
            when(resolver.resolveObject(configuration, 42L, monitor)).thenReturn(routine);
            when(routine.getParentObject()).thenReturn(schema);
            when(model.getNodeByObject(monitor, schema, false)).thenReturn(parent);
        }

        DBNDatabaseNode resolve() throws Exception {
            return DebugUtils.resolveSourceNode(controller, 42L, model, monitor);
        }
    }

    @Test
    void missingNavigatorChildRefreshesOwnerAndResolvesReplacementModel() throws Exception {
        var f = new Fixture();
        var replacement = mock(DBSObject.class);
        when(f.resolver.resolveObject(f.configuration, 42L, f.monitor)).thenReturn(f.routine, replacement);
        when(f.model.getNodeByObject(f.monitor, replacement, false)).thenReturn(f.node);
        assertSame(f.node, f.resolve());
        verify(f.parent).refreshNode(f.monitor, f.controller);
        verify(f.resolver, times(2)).resolveObject(f.configuration, 42L, f.monitor);
        assertEquals(Map.of("launch", "unchanged"), f.configuration);
    }

    @Test
    void existingSourceDoesNotRefreshOrReplaceNavigator() throws Exception {
        var f = new Fixture();
        when(f.model.getNodeByObject(f.monitor, f.routine, false)).thenReturn(f.node);
        assertSame(f.node, f.resolve());
        verifyNoInteractions(f.parent);
        verify(f.resolver).resolveObject(f.configuration, 42L, f.monitor);
    }

    @Test
    void cancellationDoesNotRefreshOwner() throws Exception {
        var f = new Fixture();
        when(f.monitor.isCanceled()).thenReturn(true);
        assertNull(f.resolve());
        verifyNoInteractions(f.parent);
    }

    @Test
    void stillMissingAfterRefreshStopsAfterOneRetry() throws Exception {
        var f = new Fixture();
        assertNull(f.resolve());
        verify(f.parent).refreshNode(f.monitor, f.controller);
        verify(f.resolver, times(2)).resolveObject(f.configuration, 42L, f.monitor);
    }
}
