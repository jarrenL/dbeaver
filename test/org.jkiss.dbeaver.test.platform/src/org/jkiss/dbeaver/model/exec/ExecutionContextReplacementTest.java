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
package org.jkiss.dbeaver.model.exec;

import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.struct.DBSInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionContextReplacementTest {
    private final DBPDataSource dataSource = mock(DBPDataSource.class);
    private final DBCExecutionContext context = mock(DBCExecutionContext.class);

    private DBSInstance instance(String name) {
        DBSInstance instance = mock(DBSInstance.class);
        when(instance.getName()).thenReturn(name);
        return instance;
    }

    private DBSInstance owner() {
        DBSInstance owner = instance("业务库");
        when(context.getOwnerInstance()).thenReturn(owner);
        when(context.getDataSource()).thenReturn(dataSource);
        return owner;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacedOwnerUsesSameDatabaseRegardlessOfConnectionState(boolean connected) throws Exception {
        owner();
        when(context.isConnected()).thenReturn(connected);
        DBSInstance replacement = instance("业务库");
        DBSInstance other = instance("other");
        doReturn(List.of(other, replacement)).when(dataSource).getAvailableInstances();
        assertSame(replacement, DBExecUtils.findReplacementInstance(dataSource, context));
        verify(context, never()).close();
        verify(context, never()).isConnected();
        verify(dataSource, never()).getDefaultInstance();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stillRegisteredOwnerMustNotBeReplacedEvenWhenDisconnected(boolean connected) throws Exception {
        DBSInstance owner = owner();
        when(context.isConnected()).thenReturn(connected);
        doReturn(List.of(instance("业务库"), owner)).when(dataSource).getAvailableInstances();
        assertNull(DBExecUtils.findReplacementInstance(dataSource, context));
        verify(context, never()).close();
    }

    @Test
    void missingDatabaseDoesNotFallBackToAnotherDatabase() {
        owner();
        doReturn(List.of(instance("other"))).when(dataSource).getAvailableInstances();
        assertNull(DBExecUtils.findReplacementInstance(dataSource, context));
        verify(dataSource, never()).getDefaultInstance();
    }

    @Test
    void emptyCacheDuringRefreshDoesNotTriggerReplacement() {
        owner();
        doReturn(List.of()).when(dataSource).getAvailableInstances();
        assertNull(DBExecUtils.findReplacementInstance(dataSource, context));
    }

    @Test
    void ambiguousNamesAreNotGuessed() {
        owner();
        doReturn(List.of(instance("业务库"), instance("业务库"))).when(dataSource).getAvailableInstances();
        assertNull(DBExecUtils.findReplacementInstance(dataSource, context));
    }

    @Test
    void unrelatedDatasourceIsNotInspected() {
        owner();
        DBPDataSource other = mock(DBPDataSource.class);
        assertNull(DBExecUtils.findReplacementInstance(other, context));
        verifyNoInteractions(other);
    }

    @Test
    void absentContextDoesNotCreateOrInspectConnections() {
        assertNull(DBExecUtils.findReplacementInstance(dataSource, null));
        verifyNoInteractions(dataSource);
    }
}
