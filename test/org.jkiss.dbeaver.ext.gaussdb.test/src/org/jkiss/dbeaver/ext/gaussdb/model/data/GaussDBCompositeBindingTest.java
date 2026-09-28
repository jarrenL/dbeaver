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

package org.jkiss.dbeaver.ext.gaussdb.model.data;

import org.jkiss.dbeaver.ext.postgresql.PostgreValueParser;
import org.jkiss.dbeaver.ext.postgresql.model.data.PostgreStructValueHandler;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.data.DBDComposite;
import org.jkiss.dbeaver.model.data.DBDFormatSettings;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.data.JDBCComposite;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBCompositeBindingTest {
    private final JDBCSession session = mock(JDBCSession.class);
    private final JDBCPreparedStatement statement = mock(JDBCPreparedStatement.class);
    private final DBSTypedObject type = mock(DBSTypedObject.class);
    private final PostgreStructValueHandler handler = PostgreStructValueHandler.INSTANCE;

    private JDBCComposite composite() {
        JDBCComposite value = mock(JDBCComposite.class);
        when(value.getValues()).thenReturn(new Object[] {new String[] {"中文 文本", "x,y", "NULL"}});
        return value;
    }

    @Test
    void gaussProviderRoutesCompositeTypesToTheSharedHandler() {
        when(type.getTypeID()).thenReturn(Types.STRUCT);
        when(type.getTypeName()).thenReturn("business_record");
        assertSame(handler, new GaussDBValueHandlerProvider().getValueHandler(
            mock(DBPDataSource.class), mock(DBDFormatSettings.class), type));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 7})
    void bindingUsesOneBasedIndexAndPreservesArrayContent(int index) throws Exception {
        JDBCComposite value = composite();
        handler.bindValueObject(session, statement, type, index, value);
        ArgumentCaptor<String> literal = ArgumentCaptor.forClass(String.class);
        verify(statement).setObject(eq(index + 1), literal.capture(), eq(Types.OTHER));
        verifyNoMoreInteractions(statement);
        String row = literal.getValue();
        String[] fields = PostgreValueParser.parseSingleObject(row.substring(1, row.length() - 1));
        assertEquals(1, fields.length);
        assertEquals(List.of("中文 文本", "x,y", "NULL"), PostgreValueParser.parseArrayString(fields[0], ","));
        verify(value, never()).release();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void javaNullAndNullCompositeUseSqlNull(boolean wrapped) throws Exception {
        JDBCComposite value = mock(JDBCComposite.class);
        when(value.isNull()).thenReturn(true);
        handler.bindValueObject(session, statement, type, 2, wrapped ? value : null);
        verify(statement).setNull(3, Types.STRUCT);
        verifyNoMoreInteractions(statement);
        verify(value, never()).getValues();
        verify(value, never()).release();
    }

    @Test
    void bindingFailureKeepsCauseAndCanRetryTheSameValue() throws Exception {
        JDBCComposite value = composite();
        SQLException failure = new SQLException("synthetic bind failure", "08006");
        doThrow(failure).doNothing().when(statement).setObject(eq(1), anyString(), eq(Types.OTHER));
        DBCException error = assertThrows(DBCException.class,
            () -> handler.bindValueObject(session, statement, type, 0, value));
        assertSame(failure, error.getCause());
        handler.bindValueObject(session, statement, type, 0, value);
        ArgumentCaptor<String> literals = ArgumentCaptor.forClass(String.class);
        verify(statement, times(2)).setObject(eq(1), literals.capture(), eq(Types.OTHER));
        assertEquals(literals.getAllValues().get(0), literals.getAllValues().get(1));
        verifyNoMoreInteractions(statement);
        verify(value, never()).release();
    }

    @Test
    void unsupportedPlainObjectFailsWithoutBinding() {
        assertThrows(DBCException.class, () -> handler.bindValueObject(session, statement, type, 0, new Object()));
        verifyNoInteractions(statement);
    }

    @Test
    void unsupportedCompositeMustNotSilentlyLeaveTheParameterUnbound() {
        DBDComposite value = mock(DBDComposite.class);
        assertThrows(DBCException.class, () -> handler.bindValueObject(session, statement, type, 0, value));
        verifyNoInteractions(statement);
    }
}

