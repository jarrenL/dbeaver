/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.jkiss.dbeaver.model.qm.QMEvent;
import org.jkiss.dbeaver.model.qm.meta.QMMObject;
import org.jkiss.dbeaver.ui.controls.querylog.QueryLogViewer;
import org.jkiss.utils.LongKeyMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryHistoryPageTest {
    @Test
    void evictingPageRowsRemovesWidgetMappingsButDoesNotDeleteHistory() throws Exception {
        var viewer = mock(QueryLogViewer.class);
        var table = mock(Table.class);
        var retained = mock(TableItem.class);
        var evicted = mock(TableItem.class);
        var object = mock(QMMObject.class);
        when(object.getObjectId()).thenReturn(42L);
        var event = mock(QMEvent.class);
        when(event.getObject()).thenReturn(object);
        when(evicted.getData()).thenReturn(event);
        when(table.getItemCount()).thenReturn(2);
        when(table.getItem(1)).thenReturn(evicted);
        var mappings = new LongKeyMap<TableItem>();
        mappings.put(41L, retained);
        mappings.put(42L, evicted);
        field(viewer, "logTable", table);
        field(viewer, "entriesPerPage", 1);
        field(viewer, "objectToItemMap", mappings);
        var trim = QueryLogViewer.class.getDeclaredMethod("trimToPageSize");
        trim.setAccessible(true);
        trim.invoke(viewer);
        assertSame(retained, mappings.get(41L));
        assertNull(mappings.get(42L), "Evicted SWT item must not remain in object lookup");
        verify(table).remove(new int[] {1});
        verify(object, never()).deleteFromHistory();
    }

    private static void field(Object target, String name, Object value) throws Exception {
        var field = QueryLogViewer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
