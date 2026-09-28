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
import org.jkiss.dbeaver.tools.transfer.IDataTransferEventProcessor;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferRegistry;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferEventProcessorDescriptor;
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
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"empty", "text", "truncated"})
    public void realXlsxAppendFailurePreservesFileAndSameConsumerCanRetry(String kind) throws Exception {
        var valid = temporaryDirectory.resolve("valid.xlsx");
        try (var seed = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = java.nio.file.Files.newOutputStream(valid)) {
            var sheet = seed.createSheet("原表");
            sheet.createRow(0).createCell(0).setCellValue("原表头");
            sheet.createRow(1).createCell(0).setCellValue("原数据");
            seed.write(out);
        }
        byte[] broken = switch (kind) {
            case "empty" -> new byte[0];
            case "text" -> "不是工作簿 中文".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            default -> java.util.Arrays.copyOf(java.nio.file.Files.readAllBytes(valid), 32);
        };
        var invalid = temporaryDirectory.resolve("invalid.xlsx");
        java.nio.file.Files.write(invalid, broken);
        var consumer = new StreamTransferConsumer();
        var exporter = new org.jkiss.dbeaver.data.office.export.DataExporterXLSX();
        var settings = mock(org.jkiss.dbeaver.tools.transfer.stream.StreamConsumerSettings.class);
        var runtime = mock(org.jkiss.dbeaver.tools.transfer.stream.StreamConsumerSettings.ConsumerRuntimeParameters.class);
        runtime.dataFileConflictBehavior = org.jkiss.dbeaver.tools.transfer.stream.StreamConsumerSettings.DataFileConflictBehavior.APPEND;
        set(consumer, "processor", exporter);
        set(consumer, "settings", settings);
        set(consumer, "runtimeParameters", runtime);
        set(consumer, "parameters", new org.jkiss.dbeaver.tools.transfer.IDataTransferConsumer.TransferParameters(true, false));
        set(consumer, "outputFile", invalid);
        Class<?> siteType = Class.forName(StreamTransferConsumer.class.getName() + "$StreamExportSite");
        var constructor = siteType.getDeclaredConstructor(StreamTransferConsumer.class);
        constructor.setAccessible(true);
        var site = (org.jkiss.dbeaver.tools.transfer.stream.IStreamDataExporterSite) constructor.newInstance(consumer);
        set(consumer, "exportSite", site);
        var monitor = new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor();
        IOException failure = assertThrows(IOException.class,
            () -> invoke(consumer, "openOutputStreams", DBRProgressMonitor.class, monitor));
        assertInstanceOf(DBException.class, failure.getCause());
        assertNotNull(failure.getCause().getCause());
        assertArrayEquals(broken, java.nio.file.Files.readAllBytes(invalid));
        assertNull(get(consumer, "outputStream"));

        // Retry against a valid workbook with the real consumer site, importer and file output.
        set(consumer, "outputFile", valid);
        var properties = org.jkiss.dbeaver.data.office.export.DataExporterXLSX.getDefaultProperties();
        properties.put("appendStrategy", "use existing sheets");
        set(consumer, "processorProperties", properties);
        var source = mock(org.jkiss.dbeaver.model.struct.DBSDataContainer.class);
        when(source.getName()).thenReturn("synthetic source");
        set(consumer, "dataContainer", source);
        var column = mock(org.jkiss.dbeaver.model.data.DBDAttributeBinding.class);
        when(column.getName()).thenReturn("value");
        when(column.getDataKind()).thenReturn(org.jkiss.dbeaver.model.DBPDataKind.STRING);
        when(column.getValueHandler()).thenReturn(org.jkiss.dbeaver.model.impl.jdbc.data.handlers.JDBCStringValueHandler.INSTANCE);
        set(consumer, "columnBindings", new org.jkiss.dbeaver.model.data.DBDAttributeBinding[]{column});
        when(settings.getValueFormat()).thenReturn(org.jkiss.dbeaver.model.data.DBDDisplayFormat.NATIVE);
        var session = mock(DBCSession.class);
        when(session.getProgressMonitor()).thenReturn(monitor);
        var resultSet = mock(org.jkiss.dbeaver.model.exec.DBCResultSet.class);
        when(resultSet.getSession()).thenReturn(session);
        invoke(consumer, "openOutputStreams", DBRProgressMonitor.class, monitor);
        try {
            exporter.init(site);
            exporter.exportHeader(session);
            exporter.exportRow(session, resultSet, new Object[]{"追加中文𠀀😀"});
            invoke(consumer, "finishFile", DBRProgressMonitor.class, monitor);
        } finally {
            invokeNoArgs(consumer, "closeOutputStreams");
        }
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(java.nio.file.Files.newInputStream(valid))) {
            assertEquals(1, workbook.getNumberOfSheets());
            var sheet = workbook.getSheetAt(0);
            assertEquals(3, sheet.getPhysicalNumberOfRows());
            assertEquals("原表头", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("原数据", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("追加中文𠀀😀", sheet.getRow(2).getCell(0).getStringCellValue());
        }
        assertArrayEquals(broken, java.nio.file.Files.readAllBytes(invalid));
        assertNull(get(consumer, "outputStream"));
        assertNull(get(consumer, "processor"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void failedAppendImportMustNotOpenOrTruncateExistingFile(boolean truncate) throws Exception {
        var consumer = new StreamTransferConsumer();
        var exporter = mock(org.jkiss.dbeaver.tools.transfer.stream.IAppendableDataExporter.class);
        var settings = mock(org.jkiss.dbeaver.tools.transfer.stream.StreamConsumerSettings.class);
        var runtime = mock(org.jkiss.dbeaver.tools.transfer.stream.StreamConsumerSettings.ConsumerRuntimeParameters.class);
        runtime.dataFileConflictBehavior = org.jkiss.dbeaver.tools.transfer.stream.StreamConsumerSettings.DataFileConflictBehavior.APPEND;
        var parameters = new org.jkiss.dbeaver.tools.transfer.IDataTransferConsumer.TransferParameters(true, false);
        byte[] original = "existing customer content 中文𠀀".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var file = temporaryDirectory.resolve("existing.bin");
        java.nio.file.Files.write(file, original);
        set(consumer, "processor", exporter);
        set(consumer, "settings", settings);
        set(consumer, "runtimeParameters", runtime);
        set(consumer, "parameters", parameters);
        set(consumer, "outputFile", file);
        DBException cause = new DBException("synthetic input parsing failure");
        doThrow(cause).when(exporter).importData(any());
        when(exporter.shouldTruncateOutputFileBeforeExport()).thenReturn(truncate);
        Exception observed = null;
        try {
            invoke(consumer, "openOutputStreams", DBRProgressMonitor.class, mock(DBRProgressMonitor.class));
        } catch (Exception e) {
            observed = e;
        } finally {
            invokeNoArgs(consumer, "closeOutputStreams");
        }
        Exception failure = observed;
        assertAll(
            () -> assertInstanceOf(IOException.class, failure),
            () -> assertNotNull(failure),
            () -> assertArrayEquals(original, java.nio.file.Files.readAllBytes(file))
        );
        assertSame(cause, failure.getCause());
        verify(exporter, never()).shouldTruncateOutputFileBeforeExport();
        // A corrected input can be retried on this same consumer; loading precedes opening/truncation.
        doAnswer(call -> {
            assertArrayEquals(original, java.nio.file.Files.readAllBytes(file));
            return null;
        }).when(exporter).importData(any());
        invoke(consumer, "openOutputStreams", DBRProgressMonitor.class, mock(DBRProgressMonitor.class));
        OutputStream stream = (OutputStream) get(consumer, "outputStream");
        byte[] added = "\nnew row 中文".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (truncate) stream.write(original); // Structured exporter rewrites the successfully loaded content.
        stream.write(added);
        invokeNoArgs(consumer, "closeOutputStreams");
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(original);
        expected.write(added);
        assertArrayEquals(expected.toByteArray(), java.nio.file.Files.readAllBytes(file));
        verify(exporter, times(2)).importData(any());
        verify(exporter).shouldTruncateOutputFileBeforeExport();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void jobRunStopsAtFailedPipeAndPreservesCancellationStatus(boolean cancelled) throws Exception {
        Class<?> jobType = org.jkiss.dbeaver.tools.transfer.DataTransferJob.class;
        var job = mock(org.jkiss.dbeaver.tools.transfer.DataTransferJob.class, invocation ->
            invocation.getMethod().getName().equals("run")
                ? invocation.callRealMethod() : RETURNS_DEFAULTS.answer(invocation));
        var settings = mock(org.jkiss.dbeaver.tools.transfer.DataTransferSettings.class);
        var producer = mock(org.jkiss.dbeaver.tools.transfer.IDataTransferProducer.class);
        var consumer = mock(org.jkiss.dbeaver.tools.transfer.IDataTransferConsumer.class);
        var monitor = mock(DBRProgressMonitor.class);
        var logger = mock(org.jkiss.dbeaver.Log.class);
        for (var entry : java.util.Map.of("settings", settings, "log", logger).entrySet()) {
            Field field = jobType.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(job, entry.getValue());
        }
        var pipe = new org.jkiss.dbeaver.tools.transfer.DataTransferPipe(producer, consumer);
        var next = mock(org.jkiss.dbeaver.tools.transfer.DataTransferPipe.class);
        when(settings.getDataPipes()).thenReturn(java.util.List.of(pipe, next));
        when(settings.acquireDataPipe(any(), isNull())).thenReturn(pipe, next, null);
        when(producer.getObjectFullName(any())).thenReturn("synthetic-source");
        when(consumer.getObjectFullName(any())).thenReturn("synthetic-output");
        DBException original = cancelled
            ? new org.jkiss.dbeaver.runtime.DBInterruptedException("synthetic cancellation")
            : new DBException("synthetic database read failure");
        DBException secondary = new DBException("synthetic notification failure");
        doThrow(original).when(producer).transferData(any(), same(consumer), isNull(), isNull(), isNull(), eq(-1L));
        doThrow(secondary).when(consumer).finishTransfer(any(), same(original), isNull(), eq(false));
        Method run = jobType.getDeclaredMethod("run", DBRProgressMonitor.class);
        run.setAccessible(true);
        var status = (org.eclipse.core.runtime.IStatus) run.invoke(job, monitor);
        if (cancelled) {
            assertEquals(org.eclipse.core.runtime.IStatus.CANCEL, status.getSeverity());
        } else {
            // The caller handles the attached exception; OK suppresses duplicate UI dialogs.
            assertEquals(org.eclipse.core.runtime.IStatus.OK, status.getSeverity());
            assertSame(original, status.getException());
        }
        assertArrayEquals(new Throwable[]{secondary}, original.getSuppressed());
        verify(settings, times(1)).acquireDataPipe(any(), isNull());
        verifyNoInteractions(next);
        verify(monitor, atLeastOnce()).done();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,none", "false,checked", "false,runtime", "false,same",
        "true,none", "true,checked", "true,runtime", "true,same"})
    public void transferJobKeepsProducerErrorWhenErrorNotificationFails(boolean cancelled, String notification) throws Exception {
        Class<?> jobType = org.jkiss.dbeaver.tools.transfer.DataTransferJob.class;
        var job = mock(org.jkiss.dbeaver.tools.transfer.DataTransferJob.class);
        var settings = mock(org.jkiss.dbeaver.tools.transfer.DataTransferSettings.class);
        var producer = mock(org.jkiss.dbeaver.tools.transfer.IDataTransferProducer.class);
        var consumer = mock(org.jkiss.dbeaver.tools.transfer.IDataTransferConsumer.class);
        var monitor = mock(DBRProgressMonitor.class);
        var logger = mock(org.jkiss.dbeaver.Log.class);
        for (var entry : java.util.Map.of("settings", settings, "log", logger).entrySet()) {
            Field field = jobType.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(job, entry.getValue());
        }
        when(producer.getObjectFullName(monitor)).thenReturn("synthetic-source");
        when(consumer.getObjectFullName(monitor)).thenReturn("synthetic-output");
        DBException original = cancelled
            ? new org.jkiss.dbeaver.runtime.DBInterruptedException("synthetic cancellation")
            : new DBException("synthetic database read failure");
        Exception secondary = switch (notification) {
            case "checked" -> new DBException("synthetic notification failure");
            case "runtime" -> new IllegalStateException("synthetic extension failure");
            case "same" -> original;
            default -> null;
        };
        doThrow(original).when(producer).transferData(monitor, consumer, null, null, null, -1);
        if (secondary != null) doThrow(secondary).when(consumer).finishTransfer(monitor, original, null, false);
        var pipe = new org.jkiss.dbeaver.tools.transfer.DataTransferPipe(producer, consumer);
        Method transfer = jobType.getDeclaredMethod("transferData", DBRProgressMonitor.class,
            org.jkiss.dbeaver.tools.transfer.DataTransferPipe.class);
        transfer.setAccessible(true);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> transfer.invoke(job, monitor, pipe));
        assertSame(original, thrown.getCause());
        assertArrayEquals(secondary == null || secondary == original ? new Throwable[0] : new Throwable[]{secondary},
            original.getSuppressed());
        verify(consumer).finishTransfer(monitor, original, null, false);
        verify(monitor).done();
    }

    @Test
    public void successEventIsSentOnlyOnFinalSummaryAfterFileIsClosed() throws Exception {
        var consumer = new StreamTransferConsumer();
        var exporter = mock(IStreamDataExporter.class);
        var output = mock(OutputStream.class);
        var monitor = mock(DBRProgressMonitor.class);
        var settings = mock(org.jkiss.dbeaver.tools.transfer.stream.StreamConsumerSettings.class);
        var eventSettings = java.util.Map.<String, Object>of("tag", "success");
        when(settings.getEventProcessors()).thenReturn(java.util.Map.of("test", eventSettings));
        var registry = mock(DataTransferRegistry.class);
        var descriptor = mock(DataTransferEventProcessorDescriptor.class);
        @SuppressWarnings("unchecked")
        IDataTransferEventProcessor<StreamTransferConsumer> events = mock(IDataTransferEventProcessor.class);
        when(registry.getEventProcessorById("test")).thenReturn(descriptor);
        when(descriptor.<StreamTransferConsumer>create()).thenReturn(events);
        set(consumer, "settings", settings);
        set(consumer, "parameters", new org.jkiss.dbeaver.tools.transfer.IDataTransferConsumer.TransferParameters());
        set(consumer, "processor", exporter);
        set(consumer, "outputStream", output);
        Field singleton = DataTransferRegistry.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        try {
            singleton.set(null, registry);
            consumer.finishTransfer(monitor, null, null, false);
            verifyNoInteractions(events);
            consumer.finishTransfer(monitor, null, null, true);
            var order = inOrder(exporter, output, events);
            order.verify(exporter).exportFooter(monitor);
            order.verify(exporter).dispose();
            order.verify(output).close();
            order.verify(events).processEvent(monitor, IDataTransferEventProcessor.Event.FINISH,
                consumer, null, eventSettings);
            verifyNoMoreInteractions(events);
            verify(output, times(1)).close();
        } finally {
            singleton.set(null, previous);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    public void failedTransferClosesOutputWithoutWritingSuccessFooter(boolean cancelled, boolean closeFails) throws Exception {
        var consumer = new StreamTransferConsumer();
        var processor = mock(IStreamDataExporter.class);
        var output = mock(OutputStream.class);
        var monitor = mock(DBRProgressMonitor.class);
        var settings = mock(org.jkiss.dbeaver.tools.transfer.stream.StreamConsumerSettings.class);
        var eventSettings = java.util.Map.<String, Object>of("tag", "synthetic failure");
        when(settings.getEventProcessors()).thenReturn(java.util.Map.of("test", eventSettings));
        var registry = mock(DataTransferRegistry.class);
        var descriptor = mock(DataTransferEventProcessorDescriptor.class);
        @SuppressWarnings("unchecked")
        IDataTransferEventProcessor<StreamTransferConsumer> events = mock(IDataTransferEventProcessor.class);
        when(registry.getEventProcessorById("test")).thenReturn(descriptor);
        when(descriptor.<StreamTransferConsumer>create()).thenReturn(events);
        set(consumer, "settings", settings);
        set(consumer, "parameters", new org.jkiss.dbeaver.tools.transfer.IDataTransferConsumer.TransferParameters());
        set(consumer, "processor", processor);
        set(consumer, "outputStream", output);
        Exception original = cancelled
            ? new org.jkiss.dbeaver.runtime.DBInterruptedException("synthetic cancellation")
            : new IOException("source read failed");
        IOException shutdown = new IOException("output close failed");
        if (closeFails) doThrow(shutdown).when(output).close();
        // Restore the global registry after this isolated component test.
        Class<?> registryType = org.jkiss.dbeaver.tools.transfer.registry.DataTransferRegistry.class;
        Field singleton = registryType.getDeclaredField("instance");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        try {
            singleton.set(null, registry);
            consumer.finishTransfer(monitor, original, null, false);
            verify(processor).dispose();
            verify(processor, never()).exportFooter(any());
            verify(output).close();
            assertArrayEquals(closeFails ? new Throwable[]{shutdown} : new Throwable[0], original.getSuppressed());
            assertNull(get(consumer, "processor"));
            assertNull(get(consumer, "outputStream"));
            var order = inOrder(output, events);
            order.verify(output).close();
            order.verify(events).processError(monitor, original, consumer, null, eventSettings);
            verify(events, never()).processEvent(any(), any(), any(), any(), any());
            consumer.finishTransfer(monitor, original, null, false);
            verify(output, times(1)).close();
        } finally {
            singleton.set(null, previous);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"footer", "dispose", "zip"})
    public void finalizationFailureNotifiesErrorInsteadOfSuccess(String stage) throws Exception {
        var consumer = new StreamTransferConsumer();
        var exporter = mock(IStreamDataExporter.class);
        var output = mock(java.util.zip.ZipOutputStream.class);
        var monitor = mock(DBRProgressMonitor.class);
        var settings = mock(org.jkiss.dbeaver.tools.transfer.stream.StreamConsumerSettings.class);
        var eventSettings = java.util.Map.<String, Object>of("stage", stage);
        when(settings.getEventProcessors()).thenReturn(java.util.Map.of("test", eventSettings));
        var registry = mock(DataTransferRegistry.class);
        var descriptor = mock(DataTransferEventProcessorDescriptor.class);
        @SuppressWarnings("unchecked")
        IDataTransferEventProcessor<StreamTransferConsumer> events = mock(IDataTransferEventProcessor.class);
        when(registry.getEventProcessorById("test")).thenReturn(descriptor);
        when(descriptor.<StreamTransferConsumer>create()).thenReturn(events);
        set(consumer, "settings", settings);
        set(consumer, "parameters", new org.jkiss.dbeaver.tools.transfer.IDataTransferConsumer.TransferParameters());
        set(consumer, "processor", exporter);
        set(consumer, "outputStream", output);
        set(consumer, "zipStream", output);
        IOException cause = new IOException("synthetic " + stage);
        switch (stage) {
            case "footer" -> doThrow(cause).when(exporter).exportFooter(monitor);
            case "dispose" -> doThrow(cause).when(exporter).dispose();
            default -> doThrow(cause).when(output).finish();
        }
        Field singleton = DataTransferRegistry.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        try {
            singleton.set(null, registry);
            DBException actual = assertThrows(DBException.class,
                () -> consumer.finishTransfer(monitor, null, null, false));
            assertEquals(1, actual.getSuppressed().length);
            Throwable reported = actual.getSuppressed()[0];
            assertSame(cause, reported.getCause());
            var order = inOrder(output, events);
            order.verify(output).close();
            order.verify(events).processError(monitor, reported, consumer, null, eventSettings);
            verify(events, never()).processEvent(any(), any(), any(), any(), any());
            assertNull(get(consumer, "processor"));
            assertNull(get(consumer, "outputStream"));
        } finally {
            singleton.set(null, previous);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void zipMultipleFailuresPreserveFirstAndStillReleaseResources(boolean reuseException) throws Exception {
        var consumer = new StreamTransferConsumer();
        var zip = mock(java.util.zip.ZipOutputStream.class);
        IOException entry = new IOException("entry failure");
        IOException finish = reuseException ? entry : new IOException("finish failure");
        IOException flush = reuseException ? entry : new IOException("flush failure");
        IOException shutdown = reuseException ? entry : new IOException("close failure");
        doThrow(entry).when(zip).closeEntry();
        doThrow(finish).when(zip).finish();
        doThrow(flush).when(zip).flush();
        doThrow(shutdown).when(zip).close();
        set(consumer, "zipStream", zip);
        set(consumer, "outputStream", zip);
        IOException actual = assertThrows(IOException.class, () -> invokeNoArgs(consumer, "closeOutputStreams"));
        assertSame(entry, actual);
        assertArrayEquals(reuseException ? new Throwable[0] : new Throwable[]{finish, flush, shutdown},
            actual.getSuppressed());
        var order = inOrder(zip);
        order.verify(zip).closeEntry();
        order.verify(zip).finish();
        order.verify(zip).flush();
        order.verify(zip).close();
        assertNull(get(consumer, "zipStream"));
        assertNull(get(consumer, "outputStream"));
        invokeNoArgs(consumer, "closeOutputStreams");
        verifyNoMoreInteractions(zip);
    }

    @ParameterizedTest
    @ValueSource(strings = {"entry", "finish", "flush"})
    public void zipFinalizationFailureIsReportedWithoutSkippingClose(String stage) throws Exception {
        var consumer = new StreamTransferConsumer();
        var zip = mock(java.util.zip.ZipOutputStream.class);
        IOException failure = new IOException("synthetic zip " + stage);
        switch (stage) {
            case "entry" -> doThrow(failure).when(zip).closeEntry();
            case "finish" -> doThrow(failure).when(zip).finish();
            default -> doThrow(failure).when(zip).flush();
        }
        set(consumer, "zipStream", zip);
        set(consumer, "outputStream", zip);
        assertSame(failure, assertThrows(IOException.class, () -> invokeNoArgs(consumer, "closeOutputStreams")));
        verify(zip).closeEntry();
        verify(zip).finish();
        verify(zip).close();
        assertNull(get(consumer, "zipStream"));
        assertNull(get(consumer, "outputStream"));
        invokeNoArgs(consumer, "closeOutputStreams");
        verify(zip, times(1)).close();
    }

    @Test
    public void successfulZipFinalizationProducesReadableUnicodeEntry() throws Exception {
        var consumer = new StreamTransferConsumer();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var zip = new java.util.zip.ZipOutputStream(bytes, java.nio.charset.StandardCharsets.UTF_8);
        zip.putNextEntry(new java.util.zip.ZipEntry("导出.csv"));
        String value = "标题,内容\r\n1,中文𠀀😀\r\n";
        var writer = new PrintWriter(new OutputStreamWriter(zip, java.nio.charset.StandardCharsets.UTF_8));
        writer.write(value);
        set(consumer, "writer", writer);
        set(consumer, "zipStream", zip);
        set(consumer, "outputStream", zip);
        invokeNoArgs(consumer, "closeOutputStreams");
        try (var input = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()),
            java.nio.charset.StandardCharsets.UTF_8)) {
            assertEquals("导出.csv", input.getNextEntry().getName());
            assertEquals(value, new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertNull(input.getNextEntry());
        }
        assertNull(get(consumer, "writer"));
        assertNull(get(consumer, "outputStream"));
    }

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
        invokeNoArgs(consumer, "closeExporter");
    }

    private static void invokeNoArgs(StreamTransferConsumer consumer, String name) throws Exception {
        Method method = StreamTransferConsumer.class.getDeclaredMethod(name);
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
