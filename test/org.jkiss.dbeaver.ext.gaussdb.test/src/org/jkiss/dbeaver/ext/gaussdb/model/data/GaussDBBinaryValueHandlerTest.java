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

import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBytes;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;
import org.junit.jupiter.api.Test;
import java.sql.Types;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaussDBBinaryValueHandlerTest {
    private final JDBCSession session = mock(JDBCSession.class);
    private final JDBCPreparedStatement statement = mock(JDBCPreparedStatement.class);
    private final DBSTypedObject type = mock(DBSTypedObject.class);

    private void bind(byte[] bytes) throws Exception {
        when(type.getTypeID()).thenReturn(Types.BINARY);
        when(type.getTypeName()).thenReturn("bytea");
        GaussDBBinaryValueHandler.INSTANCE.bindValueObject(session, statement, type, 2,
            new JDBCContentBytes(mock(DBCExecutionContext.class), bytes));
    }

    @Test void emptyUsesHexParameterWithOneBasedIndex() throws Exception {
        bind(new byte[0]);
        verify(statement).setObject(3, "\\x", Types.OTHER);
        verifyNoMoreInteractions(statement);
    }

    @Test void nonemptyKeepsBinaryBinding() throws Exception {
        byte[] bytes = {0, (byte) 255};
        bind(bytes);
        verify(statement).setBytes(3, bytes);
        verifyNoMoreInteractions(statement);
    }

    @Test void sqlNullIsNotEmpty() throws Exception {
        bind(null);
        verify(statement).setNull(3, Types.BINARY, "bytea");
        verifyNoMoreInteractions(statement);
    }

    @Test void bindingFailureIsNotSwallowed() throws Exception {
        doThrow(new java.sql.SQLException("synthetic", "08006")).when(statement).setObject(3, "\\x", Types.OTHER);
        assertThrows(DBCException.class, () -> bind(new byte[0]));
    }

    @Test void providerChoosesGaussByteaHandler() {
        when(type.getTypeName()).thenReturn("BYTEA");
        assertSame(GaussDBBinaryValueHandler.INSTANCE, new GaussDBValueHandlerProvider().getValueHandler(
            mock(org.jkiss.dbeaver.model.DBPDataSource.class), mock(org.jkiss.dbeaver.model.data.DBDFormatSettings.class), type));
    }
}
