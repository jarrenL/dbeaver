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
package org.jkiss.dbeaver.utils;

import java.sql.SQLException;
import org.jkiss.dbeaver.DBDatabaseException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.sql.DBSQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseExceptionContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"23505", "23503", "42501", "42601", "57014", "08006"})
    void sqlDiagnosticsKeepStateUnicodeAndOriginalChain(String state) {
        var source = mock(DBPDataSource.class);
        var original = new SQLException("中文对象 🧪 失败", state, 1234);
        var next = new SQLException("第二条诊断", "01000");
        var cleanup = new SQLException("cleanup failure");
        original.setNextException(next);
        original.addSuppressed(cleanup);
        var wrapped = new DBDatabaseException(original, source);
        assertSame(original, wrapped.getCause());
        assertSame(source, wrapped.getDataSource());
        assertFalse(wrapped.hasMessage());
        assertTrue(wrapped.getMessage().contains("[" + state + "]"));
        assertTrue(wrapped.getMessage().contains("[1234]"));
        assertTrue(wrapped.getMessage().contains("中文对象 🧪 失败"));
        assertSame(next, ((SQLException) wrapped.getCause()).getNextException());
        assertArrayEquals(new Throwable[]{cleanup}, wrapped.getCause().getSuppressed());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1234})
    void absentSqlMessageAndStateDoNotCauseSecondaryError(int vendorCode) {
        var original = new SQLException(null, null, vendorCode);
        var wrapped = new DBDatabaseException(original, (DBPDataSource) null);
        assertSame(original, wrapped.getCause());
        assertNull(wrapped.getDataSource());
        assertNotNull(wrapped.getMessage());
        if (vendorCode > 0) {
            assertTrue(wrapped.getMessage().contains("Error 1234"));
        }
    }

    @Test
    void explicitMessageAndNestedExecutionContextArePreserved() {
        var source = mock(DBPDataSource.class);
        var context = mock(DBCExecutionContext.class);
        when(context.getDataSource()).thenReturn(source);
        var original = new SQLException("failure", "23505");
        var inner = new DBCException(original, context);
        var outer = new DBCException("执行失败", inner);
        assertEquals("执行失败", outer.getMessage());
        assertTrue(outer.hasMessage());
        assertSame(inner, outer.getCause());
        assertSame(context, outer.getExecutionContext());
        assertSame(source, outer.getDataSource());
        var fallback = new DBDatabaseException(null, outer);
        assertFalse(fallback.hasMessage());
        assertSame(source, fallback.getDataSource());
    }

    @Test
    void sqlQueryContextIsNotLostOrAppendedToErrorMessage() {
        String sql = "SELECT '中文🧪' FROM \"Case Name\"";
        var source = mock(DBPDataSource.class);
        var context = mock(DBCExecutionContext.class);
        when(context.getDataSource()).thenReturn(source);
        var cause = new SQLException("object unavailable", "42P01");
        var error = new DBSQLException(sql, cause, context);
        assertEquals(sql, error.getSqlQuery());
        assertSame(cause, error.getCause());
        assertSame(context, error.getExecutionContext());
        assertSame(source, error.getDataSource());
        assertFalse(error.getMessage().contains(sql));
    }
}
