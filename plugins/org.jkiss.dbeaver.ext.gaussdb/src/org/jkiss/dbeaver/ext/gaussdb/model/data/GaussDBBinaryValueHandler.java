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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBytes;
import org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentBLOB;
import org.jkiss.dbeaver.model.impl.jdbc.data.JDBCContentAbstract;
import org.jkiss.dbeaver.model.impl.jdbc.data.handlers.JDBCContentValueHandler;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;
import java.sql.SQLException;
import java.sql.Types;

/** Preserves empty bytea values on drivers that bind zero binary bytes as SQL NULL. */
public class GaussDBBinaryValueHandler extends JDBCContentValueHandler {
    public static final GaussDBBinaryValueHandler INSTANCE = new GaussDBBinaryValueHandler();

    @Override
    protected void bindParameter(
        @NotNull JDBCSession session, @NotNull JDBCPreparedStatement statement,
        @NotNull DBSTypedObject paramType, int paramIndex, @Nullable Object value
    ) throws DBCException, SQLException {
        if (value instanceof JDBCContentAbstract content
            && (content instanceof JDBCContentBytes || content instanceof JDBCContentBLOB)
            && !content.isNull() && content.getContentLength() == 0) {
            // Nonempty hex input represents zero bytes without triggering ORA empty-string semantics.
            statement.setObject(paramIndex, "\\x", Types.OTHER);
        } else {
            super.bindParameter(session, statement, paramType, paramIndex, value);
        }
    }
}
