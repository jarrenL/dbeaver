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

import org.jkiss.dbeaver.tools.transfer.stream.IStreamDataExporter;
import org.jkiss.dbeaver.tools.transfer.stream.StreamTransferConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import java.io.*;
import java.lang.reflect.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class GaussDBStreamExportFailureTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void headerFailureIsNotIgnored(boolean ioFailure) throws Exception {
        var consumer = new StreamTransferConsumer();
        var processor = mock(IStreamDataExporter.class);
        var session = mock(DBCSession.class);
        Exception failure = ioFailure ? new IOException("header IO") : new DBException("header database");
        doThrow(failure).when(processor).exportHeader(session);
        set(consumer, "processor", processor);
        DBCException actual = assertThrows(DBCException.class,
            () -> invoke(consumer, "exportHeaderInFile", DBCSession.class, session));
        assertSame(failure, actual.getCause());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void footerFailureIsNotIgnored(boolean ioFailure) throws Exception {
        var consumer = new StreamTransferConsumer();
        var processor = mock(IStreamDataExporter.class);
        var monitor = mock(DBRProgressMonitor.class);
        Exception failure = ioFailure ? new IOException("footer IO") : new DBException("footer database");
        doThrow(failure).when(processor).exportFooter(monitor);
        set(consumer, "processor", processor);
        DBCException actual = assertThrows(DBCException.class,
            () -> invoke(consumer, "exportFooterInFile", DBRProgressMonitor.class, monitor));
        assertSame(failure, actual.getCause());
    }

    @Test
    public void footerFailureStillFinalizesAndKeepsCloseFailureSecondary() throws Exception {
        var consumer = new StreamTransferConsumer();
        var processor = mock(IStreamDataExporter.class);
        var output = mock(OutputStream.class);
        var monitor = mock(DBRProgressMonitor.class);
        DBException footer = new DBException("footer failure");
        IOException shutdown = new IOException("close failure");
        doThrow(footer).when(processor).exportFooter(monitor);
        doThrow(shutdown).when(output).close();
        set(consumer, "processor", processor);
        set(consumer, "outputStream", output);
        DBException actual = assertThrows(DBException.class,
            () -> invoke(consumer, "finishFile", DBRProgressMonitor.class, monitor));
        assertSame(footer, actual.getCause());
        assertArrayEquals(new Throwable[]{shutdown}, actual.getSuppressed());
        verify(processor).dispose();
        verify(output).close();
        invoke(consumer, "finishFile", DBRProgressMonitor.class, monitor);
        verify(processor, times(1)).exportFooter(monitor);
        verify(processor, times(1)).dispose();
    }

    @Test
    public void successfulFileFinalizationKeepsFooterBeforeDisposeAndClose() throws Exception {
        var consumer = new StreamTransferConsumer();
        var processor = mock(IStreamDataExporter.class);
        var output = mock(OutputStream.class);
        var monitor = mock(DBRProgressMonitor.class);
        set(consumer, "processor", processor);
        set(consumer, "outputStream", output);
        invoke(consumer, "finishFile", DBRProgressMonitor.class, monitor);
        var order = inOrder(processor, output);
        order.verify(processor).exportFooter(monitor);
        order.verify(processor).dispose();
        order.verify(output).flush();
        order.verify(output).close();
        assertNull(get(consumer, "processor"));
    }

    private static void invoke(Object owner, String name, Class<?> type, Object argument) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, type);
        method.setAccessible(true);
        try {
            method.invoke(owner, argument);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    @Test
    public void disposeFailureIsReportedAndOutputStillCloses() throws Exception {
        var consumer = new StreamTransferConsumer();
        var processor = mock(IStreamDataExporter.class);
        var output = mock(OutputStream.class);
        IOException failure = new IOException("synthetic workbook write failure");
        doThrow(failure).when(processor).dispose();
        set(consumer, "processor", processor);
        set(consumer, "outputStream", output);
        assertSame(failure, assertThrows(IOException.class, () -> close(consumer)));
        verify(output).close();
        assertNull(get(consumer, "processor"));
        close(consumer);
        verify(processor, times(1)).dispose();
        verify(output, times(1)).close();
    }

    @Test
    public void swallowedPrintWriterFailureIsReportedAndResourcesClose() throws Exception {
        var consumer = new StreamTransferConsumer();
        var processor = mock(IStreamDataExporter.class);
        var output = mock(OutputStream.class);
        Writer broken = new Writer() {
            public void write(char[] text, int offset, int length) throws IOException {
                throw new IOException("synthetic disk full");
            }
            public void flush() {}
            public void close() {}
        };
        PrintWriter writer = new PrintWriter(broken);
        writer.write("data");
        assertTrue(writer.checkError());
        set(consumer, "writer", writer);
        set(consumer, "processor", processor);
        set(consumer, "outputStream", output);
        Class<?> siteType = Class.forName(StreamTransferConsumer.class.getName() + "$StreamExportSite");
        var constructor = siteType.getDeclaredConstructor(StreamTransferConsumer.class);
        constructor.setAccessible(true);
        set(consumer, "exportSite", constructor.newInstance(consumer));
        assertThrows(IOException.class, () -> close(consumer));
        verify(processor).dispose();
        verify(output).close();
        close(consumer);
        verify(processor, times(1)).dispose();
        verify(output, times(1)).close();
    }

    @Test
    public void cleanupFailureDoesNotReplacePrimaryExportFailure() throws Exception {
        var consumer = new StreamTransferConsumer();
        var processor = mock(IStreamDataExporter.class);
        var output = mock(OutputStream.class);
        IOException primary = new IOException("primary write failure");
        IOException cleanup = new IOException("output close failure");
        doThrow(primary).when(processor).dispose();
        doThrow(cleanup).when(output).close();
        set(consumer, "processor", processor);
        set(consumer, "outputStream", output);
        IOException actual = assertThrows(IOException.class, () -> close(consumer));
        assertSame(primary, actual);
        assertArrayEquals(new Throwable[]{cleanup}, actual.getSuppressed());
        close(consumer);
        verify(output, times(1)).close();
    }

    @Test
    public void successfulFinalizationRunsDisposeAndClosesOnlyOnce() throws Exception {
        var consumer = new StreamTransferConsumer();
        var processor = mock(IStreamDataExporter.class);
        var output = mock(OutputStream.class);
        StringWriter text = new StringWriter();
        set(consumer, "writer", new PrintWriter(text));
        set(consumer, "processor", processor);
        set(consumer, "outputStream", output);
        doAnswer(call -> {
            text.write("final footer");
            return null;
        }).when(processor).dispose();
        close(consumer);
        assertEquals("final footer", text.toString());
        close(consumer);
        verify(processor, times(1)).dispose();
        verify(output, times(1)).close();
        assertNull(get(consumer, "writer"));
        assertNull(get(consumer, "outputStream"));
    }

    @Test
    public void flushFailureStillDisposesAndClosesWithSecondaryErrorsPreserved() throws Exception {
        var consumer = new StreamTransferConsumer();
        var processor = mock(IStreamDataExporter.class);
        var output = mock(OutputStream.class);
        IOException flush = new IOException("flush failure");
        IOException dispose = new IOException("dispose failure");
        IOException shutdown = new IOException("close failure");
        doThrow(flush).when(output).flush();
        doThrow(dispose).when(processor).dispose();
        doThrow(shutdown).when(output).close();
        set(consumer, "processor", processor);
        set(consumer, "outputStream", output);
        Class<?> siteType = Class.forName(StreamTransferConsumer.class.getName() + "$StreamExportSite");
        var constructor = siteType.getDeclaredConstructor(StreamTransferConsumer.class);
        constructor.setAccessible(true);
        set(consumer, "exportSite", constructor.newInstance(consumer));
        IOException actual = assertThrows(IOException.class, () -> close(consumer));
        assertSame(flush, actual);
        assertArrayEquals(new Throwable[]{dispose, shutdown}, actual.getSuppressed());
        close(consumer);
        verify(processor, times(1)).dispose();
        verify(output, times(1)).close();
    }

    @Test
    public void nonIoDisposeFailureRetainsCauseAndClosesOutput() throws Exception {
        var consumer = new StreamTransferConsumer();
        var processor = mock(IStreamDataExporter.class);
        var output = mock(OutputStream.class);
        var failure = new IllegalStateException("export state failure");
        doThrow(failure).when(processor).dispose();
        set(consumer, "processor", processor);
        set(consumer, "outputStream", output);
        IOException actual = assertThrows(IOException.class, () -> close(consumer));
        assertSame(failure, actual.getCause());
        verify(output).close();
    }

    private static void close(StreamTransferConsumer consumer) throws Exception {
        Method method = StreamTransferConsumer.class.getDeclaredMethod("closeExporter");
        method.setAccessible(true);
        try {
            method.invoke(consumer);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
