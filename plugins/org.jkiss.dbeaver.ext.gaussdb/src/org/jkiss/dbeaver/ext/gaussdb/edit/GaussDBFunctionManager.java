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

package org.jkiss.dbeaver.ext.gaussdb.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBFunction;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBSchema;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreSchema;
import org.jkiss.dbeaver.model.edit.DBECommandContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.cache.DBSObjectCache;

import java.util.Map;

/**
 * GaussDBFunctionManager — extends GaussDBProcedureManager to reuse all
 * create/modify/delete/rename logic. Only overrides the minimum necessary
 * to handle the Function type instead of Procedure.
 */
public class GaussDBFunctionManager extends GaussDBProcedureManager {

    @Nullable
    @Override
    public DBSObjectCache<GaussDBSchema, GaussDBFunction> getObjectsCache(GaussDBFunction object) {
        GaussDBSchema schema = (GaussDBSchema) object.getContainer();
        return schema.getGaussDBFunctionsCache();
    }

    @Override
    protected GaussDBFunction createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull final Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new GaussDBFunction((PostgreSchema) container);
    }
}
