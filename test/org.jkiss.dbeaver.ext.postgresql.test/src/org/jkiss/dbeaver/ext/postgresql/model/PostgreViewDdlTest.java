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
package org.jkiss.dbeaver.ext.postgresql.model;

import org.jkiss.dbeaver.ext.postgresql.edit.PostgreViewManager;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgreViewDdlTest {
    private static class Manager extends PostgreViewManager {
        String ddl(PostgreView view) throws Exception {
            var actions = new ArrayList<DBEPersistAction>();
            createOrReplaceViewQuery(new VoidProgressMonitor(), actions, view, Map.of());
            assertEquals(1, actions.size());
            return actions.get(0).getScript();
        }
    }

    private String ddl(String source) throws Exception {
        var view = mock(PostgreView.class);
        var dataSource = mock(PostgreDataSource.class);
        when(dataSource.getSQLDialect()).thenReturn(new PostgreDialect());
        when(view.getDataSource()).thenReturn(dataSource);
        when(view.getFullyQualifiedName(DBPEvaluationContext.DDL)).thenReturn("test_schema.test_view");
        when(view.getTableTypeName()).thenReturn("VIEW");
        when(view.getObjectDefinitionText(any(), anyMap())).thenReturn(source);
        return new Manager().ddl(view);
    }

    @Test
    void createInsideStringDoesNotSuppressViewDeclaration() throws Exception {
        String source = "SELECT 'create' AS label";
        assertEquals("CREATE OR REPLACE VIEW test_schema.test_view\nAS " + source, ddl(source));
    }

    @Test
    void createInsideCommentDoesNotSuppressViewDeclaration() throws Exception {
        String source = "/* create a label */ SELECT 1 AS id";
        assertEquals("CREATE OR REPLACE VIEW test_schema.test_view\nAS " + source, ddl(source));
    }

    @Test
    void completeDeclarationWithLeadingCommentIsPreserved() throws Exception {
        String source = "-- view header\nCREATE OR REPLACE VIEW test_schema.test_view AS SELECT 1 AS id";
        assertEquals(source, ddl(source));
    }
}
