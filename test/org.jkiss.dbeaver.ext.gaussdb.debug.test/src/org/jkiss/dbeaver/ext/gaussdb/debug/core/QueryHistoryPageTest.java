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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryHistoryPageTest {
    @ParameterizedTest
    @CsvSource({"0, 10", "1, 10", "9, 10", "10, 10", "11, 10", "15, 10", "201, 200"})
    void pageBoundaryPreservesRetainedRowsAndUnderlyingEvents(int count, int pageSize) throws Exception {
        var viewer = mock(QueryLogViewer.class);
        var table = mock(Table.class);
        var mappings = new LongKeyMap<TableItem>();
        var items = new TableItem[count];
        var objects = new QMMObject[count];
        when(table.getItemCount()).thenReturn(count);
        for (int i = 0; i < count; i++) {
            items[i] = mock(TableItem.class);
            objects[i] = mock(QMMObject.class);
            when(objects[i].getObjectId()).thenReturn((long) i + 1);
            var event = mock(QMEvent.class);
            when(event.getObject()).thenReturn(objects[i]);
            when(items[i].getData()).thenReturn(event);
            when(table.getItem(i)).thenReturn(items[i]);
            mappings.put(i + 1, items[i]);
        }
        field(viewer, "logTable", table);
        field(viewer, "entriesPerPage", pageSize);
        field(viewer, "objectToItemMap", mappings);
        var trim = QueryLogViewer.class.getDeclaredMethod("trimToPageSize");
        trim.setAccessible(true);
        trim.invoke(viewer);
        for (int i = 0; i < count; i++) {
            if (i < pageSize) {
                assertSame(items[i], mappings.get(i + 1));
            } else {
                assertNull(mappings.get(i + 1));
            }
            verify(objects[i], never()).deleteFromHistory();
        }
        if (count > pageSize) {
            verify(table).remove(java.util.stream.IntStream.range(pageSize, count).toArray());
        } else {
            verify(table, never()).remove(any(int[].class));
            verify(table, never()).getItem(anyInt());
        }
    }

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
