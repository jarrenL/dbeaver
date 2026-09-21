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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.gaussdb.GaussDBConstants;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDialect;
import org.jkiss.dbeaver.model.DBPDataSource;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * GaussDB SQL dialect.
 * Extends PostgreSQL dialect with GaussDB-specific keywords, types, and syntax.
 */
public class GaussDBDialect extends PostgreDialect {

    @Override
    public boolean isDelimiterAfterBlock() {
        return true;
    }

    public void addExtraDataTypes(String... dataTypes) {
        addDataTypes(Arrays.asList(dataTypes));
    }

    @NotNull
    @Override
    public Collection<String> getDataTypes(@Nullable DBPDataSource dataSource) {
        Collection<String> dataTypes = new LinkedHashSet<>(super.getDataTypes(dataSource));
        dataTypes.addAll(Arrays.asList(GaussDBConstants.GAUSSDB_DATA_TYPES));
        return dataTypes;
    }
}
