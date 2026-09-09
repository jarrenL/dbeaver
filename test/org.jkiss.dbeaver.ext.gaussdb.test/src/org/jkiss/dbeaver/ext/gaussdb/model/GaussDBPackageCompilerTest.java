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

import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.SQLException;

public class GaussDBPackageCompilerTest {

    @Test
    public void generatesAllCompileVariantsWithQualifiedName() {
        GaussDBPackage object = Mockito.mock(GaussDBPackage.class);
        Mockito.when(object.getFullyQualifiedName(DBPEvaluationContext.DDL)).thenReturn("\"Mixed Schema\".\"Test Package\"");

        Assertions.assertEquals(
            "ALTER PACKAGE \"Mixed Schema\".\"Test Package\" COMPILE",
            GaussDBPackageCompiler.getCompileSQL(object, GaussDBPackageCompileTarget.ALL)
        );
        Assertions.assertEquals(
            "ALTER PACKAGE \"Mixed Schema\".\"Test Package\" COMPILE SPECIFICATION",
            GaussDBPackageCompiler.getCompileSQL(object, GaussDBPackageCompileTarget.SPECIFICATION)
        );
        Assertions.assertEquals(
            "ALTER PACKAGE \"Mixed Schema\".\"Test Package\" COMPILE BODY",
            GaussDBPackageCompiler.getCompileSQL(object, GaussDBPackageCompileTarget.BODY)
        );
    }

    @Test
    public void compileErrorUsesLineLevelLocationAndSourcePart() {
        GaussDBPackageCompileError error = new GaussDBPackageCompileError(
            GaussDBPackageCompileTarget.BODY,
            "undefined identifier",
            17
        );

        Assertions.assertTrue(error.isError());
        Assertions.assertEquals(17, error.getLine());
        Assertions.assertEquals(1, error.getPosition());
        Assertions.assertSame(GaussDBPackageCompileTarget.BODY, error.getSourcePart());
    }

    @Test
    public void extractsDirectServerErrorLocationWhenCompilationThrows() {
        GaussDBPackageCompileError error = GaussDBPackageCompiler.toCompileError(
            new SQLException("compilation of PL/pgSQL package body near line 23: undefined identifier", "42601"),
            GaussDBPackageCompileTarget.ALL
        );

        Assertions.assertEquals(23, error.getLine());
        Assertions.assertSame(GaussDBPackageCompileTarget.BODY, error.getSourcePart());
    }
}
