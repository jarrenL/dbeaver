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

package org.jkiss.dbeaver.ext.gaussdb.model;

import java.sql.ResultSet;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreProcedureKind;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreSchema;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;

/**
 * GaussDB function — extends GaussDBProcedure since GaussDB treats functions
 * and procedures through the same pg_proc table, differing only by prokind.
 * This class exists primarily for type identification in the object tree
 * and editor registration. All DDL logic is inherited from GaussDBProcedure.
 */
public class GaussDBFunction extends GaussDBProcedure {

    public GaussDBFunction(DBRProgressMonitor monitor, PostgreSchema schema, ResultSet dbResult) {
        super(monitor, schema, dbResult);
    }

    public GaussDBFunction(PostgreSchema schema) {
        super(schema);
        setKind(PostgreProcedureKind.f);
    }

    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        GaussDBSchema schema = (GaussDBSchema) getContainer();
        return schema.getGaussDBFunctionsCache().refreshObject(monitor, schema, this);
    }
}
