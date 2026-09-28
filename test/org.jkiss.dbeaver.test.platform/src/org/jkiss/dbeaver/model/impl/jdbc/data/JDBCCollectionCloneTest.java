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

package org.jkiss.dbeaver.model.impl.jdbc.data;

import org.jkiss.dbeaver.model.data.DBDValueCloneable;
import org.jkiss.dbeaver.model.data.DBDValueHandler;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JDBCCollectionCloneTest {
    private final DBRProgressMonitor monitor = mock(DBRProgressMonitor.class);
    private final DBSDataType type = mock(DBSDataType.class);
    private final DBDValueHandler handler = mock(DBDValueHandler.class);

    private JDBCCollection collection(Object... items) {
        return new JDBCCollection(monitor, type, handler, items);
    }

    @Test
    void constructorDoesNotBorrowTheInputArray() {
        Object[] input = {"original"};
        JDBCCollection value = collection(input);
        input[0] = "outside";
        assertEquals("original", value.getItem(0));
    }

    @Test
    void editingCloneDoesNotChangeOriginalOrItsModifiedFlag() throws Exception {
        JDBCCollection original = collection("原值");
        JDBCCollection copy = (JDBCCollection) original.cloneValue(monitor);
        copy.setItem(0, "副本");
        assertEquals("原值", original.getItem(0));
        assertFalse(original.isModified());
        assertTrue(copy.isModified());
        assertSame(type, copy.getComponentType());
        assertSame(handler, copy.getComponentValueHandler());
    }

    @Test
    void nestedCollectionIsClonedIndependently() throws Exception {
        JDBCCollection original = collection(collection("原值"));
        JDBCCollection copy = (JDBCCollection) original.cloneValue(monitor);
        JDBCCollection child = (JDBCCollection) copy.getItem(0);
        child.setItem(0, "修改");
        assertEquals("原值", ((JDBCCollection) original.getItem(0)).getItem(0));
        assertNotSame(original.getItem(0), child);
    }

    @Test
    void nullAndEmptyRemainDistinctAfterClone() throws Exception {
        JDBCCollection nullValue = collection((Object[]) null);
        JDBCCollection empty = collection();
        assertTrue(((JDBCCollection) nullValue.cloneValue(monitor)).isNull());
        JDBCCollection copy = (JDBCCollection) empty.cloneValue(monitor);
        assertFalse(copy.isNull());
        assertEquals(0, copy.getItemCount());
    }

    @Test
    void releasingCopyDoesNotReleaseOriginalArray() throws Exception {
        JDBCCollection original = collection("原值");
        JDBCCollection copy = (JDBCCollection) original.cloneValue(monitor);
        copy.release();
        assertTrue(copy.isNull());
        assertEquals("原值", original.getItem(0));
    }

    @Test
    void childCloneFailureIsNotSilentlySharedAndRetryWorks() throws Exception {
        JDBCCollection original = collection("placeholder");
        DBDValueCloneable child = mock(DBDValueCloneable.class);
        DBDValueCloneable cloned = mock(DBDValueCloneable.class);
        DBCException failure = new DBCException("synthetic clone failure");
        original.setItem(0, child);
        when(child.cloneValue(monitor)).thenThrow(failure).thenReturn(cloned);
        assertSame(failure, assertThrows(DBCException.class, () -> original.cloneValue(monitor)));
        assertSame(child, original.getItem(0));
        JDBCCollection copy = (JDBCCollection) original.cloneValue(monitor);
        assertSame(cloned, copy.getItem(0));
        verify(child, never()).release();
    }

    @Test
    void failureReleasesOnlyAlreadyCreatedClones() throws Exception {
        JDBCCollection original = collection("one", "two");
        DBDValueCloneable first = mock(DBDValueCloneable.class);
        DBDValueCloneable firstCopy = mock(DBDValueCloneable.class);
        DBDValueCloneable second = mock(DBDValueCloneable.class);
        original.setItem(0, first);
        original.setItem(1, second);
        when(first.cloneValue(monitor)).thenReturn(firstCopy);
        when(second.cloneValue(monitor)).thenThrow(new DBCException("synthetic failure"));
        assertThrows(DBCException.class, () -> original.cloneValue(monitor));
        verify(firstCopy).release();
        verify(first, never()).release();
        verify(second, never()).release();
        assertSame(first, original.getItem(0));
        assertSame(second, original.getItem(1));
    }

    @Test
    void cleanupFailureDoesNotMaskCloneFailureOrStopOtherCleanup() throws Exception {
        JDBCCollection original = collection("one", "two", "three");
        DBDValueCloneable first = mock(DBDValueCloneable.class);
        DBDValueCloneable second = mock(DBDValueCloneable.class);
        DBDValueCloneable third = mock(DBDValueCloneable.class);
        DBDValueCloneable firstCopy = mock(DBDValueCloneable.class);
        DBDValueCloneable secondCopy = mock(DBDValueCloneable.class);
        original.setContents(new Object[] {first, second, third});
        when(first.cloneValue(monitor)).thenReturn(firstCopy);
        when(second.cloneValue(monitor)).thenReturn(secondCopy);
        DBCException failure = new DBCException("clone failed");
        RuntimeException cleanup = new IllegalStateException("cleanup failed");
        when(third.cloneValue(monitor)).thenThrow(failure);
        doThrow(cleanup).when(firstCopy).release();
        assertSame(failure, assertThrows(DBCException.class, () -> original.cloneValue(monitor)));
        assertArrayEquals(new Throwable[] {cleanup}, failure.getSuppressed());
        verify(secondCopy).release();
        verify(first, never()).release();
        verify(second, never()).release();
        verify(third, never()).release();
    }

    @Test
    void identityCloneIsNeverReleasedOnLaterFailure() throws Exception {
        JDBCCollection original = collection("one", "two");
        DBDValueCloneable first = mock(DBDValueCloneable.class);
        DBDValueCloneable second = mock(DBDValueCloneable.class);
        original.setContents(new Object[] {first, second});
        when(first.cloneValue(monitor)).thenReturn(first);
        when(second.cloneValue(monitor)).thenThrow(new DBCException("clone failed"));
        assertThrows(DBCException.class, () -> original.cloneValue(monitor));
        verify(first, never()).release();
        verify(second, never()).release();
    }
}
