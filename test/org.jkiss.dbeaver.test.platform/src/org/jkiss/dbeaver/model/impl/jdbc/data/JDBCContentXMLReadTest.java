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

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.data.DBDContentCached;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.SQLXML;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JDBCContentXMLReadTest {
    private static class TrackedReader extends StringReader {
        boolean closed;
        TrackedReader(String text) { super(text); }
        @Override public void close() { closed = true; super.close(); }
    }
    @Test
    void successfulReadClosesReaderBeforeFreeAndReusesCache() throws Exception {
        var xml = mock(SQLXML.class);
        var reader = new TrackedReader("<a>中文𠀀</a>");
        doAnswer(invocation -> { assertTrue(reader.closed); return null; }).when(xml).free();
        when(xml.getCharacterStream()).thenReturn(reader);
        var content = new JDBCContentXML(mock(DBCExecutionContext.class), xml);
        var monitor = mock(DBRProgressMonitor.class);
        var storage = content.getContents(monitor);
        assertEquals("<a>中文𠀀</a>", ((DBDContentCached) storage).getCachedValue());
        assertTrue(reader.closed);
        assertSame(storage, content.getContents(monitor));
        content.release();
        verify(xml).getCharacterStream();
        verify(xml).free();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationBeforeReadOrAfterCloseKeepsXmlRetryable(boolean afterClose) throws Exception {
        var xml = mock(SQLXML.class);
        var monitor = mock(DBRProgressMonitor.class);
        var reader = new TrackedReader("<old/>") {
            @Override public void close() {
                super.close();
                if (afterClose) when(monitor.isCanceled()).thenReturn(true);
            }
        };
        when(xml.getCharacterStream()).thenReturn(reader);
        when(monitor.isCanceled()).thenReturn(!afterClose);
        var content = new JDBCContentXML(mock(DBCExecutionContext.class), xml);
        assertThrows(DBException.class, () -> content.getContents(monitor));
        verify(xml, never()).free();
        if (afterClose) assertTrue(reader.closed);
        else verify(xml, never()).getCharacterStream();
        when(monitor.isCanceled()).thenReturn(false);
        var retry = new TrackedReader("<new>中文</new>");
        when(xml.getCharacterStream()).thenReturn(retry);
        assertEquals("<new>中文</new>", ((DBDContentCached) content.getContents(monitor)).getCachedValue());
        assertTrue(retry.closed);
        verify(xml).free();
        content.release();
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "close", "both"})
    void failureClosesReaderAndDoesNotCacheIncompleteAttempt(String mode) throws Exception {
        var xml = mock(SQLXML.class);
        var reader = mock(Reader.class);
        var readFailure = new IOException("read failed");
        var closeFailure = new IOException("close failed");
        if (!mode.equals("close")) {
            when(reader.read(any(char[].class))).thenThrow(readFailure);
        } else {
            when(reader.read(any(char[].class))).thenReturn(-1);
        }
        if (!mode.equals("read")) {
            doThrow(closeFailure).when(reader).close();
        }
        when(xml.getCharacterStream()).thenReturn(reader);
        var content = new JDBCContentXML(mock(DBCExecutionContext.class), xml);
        var monitor = mock(DBRProgressMonitor.class);
        var thrown = assertThrows(DBException.class, () -> content.getContents(monitor));
        assertSame(mode.equals("close") ? closeFailure : readFailure, thrown.getCause());
        if (mode.equals("both")) {
            assertArrayEquals(new Throwable[] {closeFailure}, readFailure.getSuppressed());
        }
        verify(reader).close();
        verify(xml, never()).free();
        var retry = new TrackedReader("<retry>成功</retry>");
        when(xml.getCharacterStream()).thenReturn(retry);
        var storage = content.getContents(monitor);
        assertEquals("<retry>成功</retry>", ((DBDContentCached) storage).getCachedValue());
        assertTrue(retry.closed);
        verify(xml, times(2)).getCharacterStream();
        verify(xml).free();
    }
}
