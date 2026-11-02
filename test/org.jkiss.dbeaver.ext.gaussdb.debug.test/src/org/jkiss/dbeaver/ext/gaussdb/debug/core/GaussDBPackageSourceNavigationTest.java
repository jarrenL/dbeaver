/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.internal.GaussDBDebugResolver;
import org.jkiss.dbeaver.ext.gaussdb.model.*;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.navigator.*;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBPackageSourceNavigationTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void coldPackageFunctionLoadsOnlyItsOwningPackageMemberFolder() throws Exception {
        var monitor = mock(DBRProgressMonitor.class);
        var navigator = mock(DBNModel.class);
        var schema = mock(GaussDBSchema.class);
        var gaussPackage = mock(GaussDBPackage.class);
        var unrelatedPackage = mock(GaussDBPackage.class);
        var routine = mock(GaussDBFunction.class);
        when(routine.getPropackageid()).thenReturn(91L);
        when(routine.getSchema()).thenReturn(schema);
        when(gaussPackage.getObjectId()).thenReturn(91L);
        when(unrelatedPackage.getObjectId()).thenReturn(92L);
        when(schema.getPackages(monitor)).thenReturn(List.of(unrelatedPackage, gaussPackage));
        var packageNode = mock(DBNDatabaseNode.class);
        var functions = mock(DBNDatabaseFolder.class);
        var procedures = mock(DBNDatabaseFolder.class);
        when(navigator.getNodeByObject(monitor, gaussPackage, false)).thenReturn(packageNode);
        when(packageNode.getChildren(monitor)).thenReturn(new DBNDatabaseNode[]{procedures, functions});
        when(functions.getChildrenClass()).thenReturn((Class) GaussDBFunction.class);
        when(procedures.getChildrenClass()).thenReturn((Class) GaussDBProcedure.class);
        new GaussDBDebugResolver(mock(DBPDataSourceContainer.class)).prepareSourceNavigation(routine, navigator, monitor);
        verify(functions).getChildren(monitor);
        // GaussDBFunction extends GaussDBProcedure; both matching member folders may load.
        verify(navigator, never()).getNodeByObject(monitor, unrelatedPackage, false);
        verify(schema, never()).getGaussDBProceduresCache();
    }

    @Test
    void standaloneSourceDoesNotLoadPackageCatalog() throws Exception {
        var navigator = mock(DBNModel.class);
        var routine = mock(GaussDBProcedure.class);
        new GaussDBDebugResolver(mock(DBPDataSourceContainer.class))
            .prepareSourceNavigation(routine, navigator, mock(DBRProgressMonitor.class));
        verifyNoInteractions(navigator);
        verify(routine, never()).getSchema();
    }

    @Test
    void cancellationDoesNotLoadPackageMembers() throws Exception {
        var monitor = mock(DBRProgressMonitor.class);
        when(monitor.isCanceled()).thenReturn(true);
        var routine = mock(GaussDBProcedure.class);
        when(routine.getPropackageid()).thenReturn(91L);
        var navigator = mock(DBNModel.class);
        new GaussDBDebugResolver(mock(DBPDataSourceContainer.class)).prepareSourceNavigation(routine, navigator, monitor);
        verifyNoInteractions(navigator);
        verify(routine, never()).getSchema();
    }

    @Test
    void missingPackageFailsWithoutRetargetingSameNamedMember() throws Exception {
        var routine = mock(GaussDBProcedure.class);
        var schema = mock(GaussDBSchema.class);
        var monitor = mock(DBRProgressMonitor.class);
        when(routine.getPropackageid()).thenReturn(91L);
        when(routine.getSchema()).thenReturn(schema);
        when(schema.getPackages(monitor)).thenReturn(List.of());
        var navigator = mock(DBNModel.class);
        assertThrows(DBException.class, () -> new GaussDBDebugResolver(mock(DBPDataSourceContainer.class))
            .prepareSourceNavigation(routine, navigator, monitor));
        verifyNoInteractions(navigator);
    }
}
