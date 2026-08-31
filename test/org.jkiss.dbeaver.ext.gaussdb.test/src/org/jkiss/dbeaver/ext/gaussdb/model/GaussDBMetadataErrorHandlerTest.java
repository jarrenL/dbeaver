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

import org.jkiss.dbeaver.DBException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

public class GaussDBMetadataErrorHandlerTest {

    @Test
    public void recognizesOptionalMetadataStatesThroughWrappedExceptions() {
        for (String sqlState : List.of("42501", "42P01", "42703", "42883", "3F000")) {
            SQLException sqlException = new SQLException("Optional metadata is unavailable", sqlState);
            Assertions.assertTrue(GaussDBMetadataErrorHandler.isOptionalMetadataError(
                new DBException("Wrapped metadata failure", sqlException)));
            Assertions.assertEquals(
                sqlState,
                GaussDBMetadataErrorHandler.getOptionalMetadataSqlState(sqlException)
            );
        }
    }

    @Test
    public void rejectsConnectionAuthenticationAndUnclassifiedErrors() {
        Assertions.assertFalse(GaussDBMetadataErrorHandler.isOptionalMetadataError(
            new SQLException("Connection failed", "08006")));
        Assertions.assertFalse(GaussDBMetadataErrorHandler.isOptionalMetadataError(
            new SQLException("Authentication failed", "28P01")));
        Assertions.assertFalse(GaussDBMetadataErrorHandler.isOptionalMetadataError(
            new DBException("No SQL state")));
    }
}
