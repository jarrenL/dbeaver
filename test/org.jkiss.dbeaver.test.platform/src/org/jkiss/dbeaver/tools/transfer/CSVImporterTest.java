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
package org.jkiss.dbeaver.tools.transfer;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.tools.transfer.stream.IStreamDataImporterSite;
import org.jkiss.dbeaver.tools.transfer.stream.StreamDataImporterColumnInfo;
import org.jkiss.dbeaver.tools.transfer.stream.StreamEntityMapping;
import org.jkiss.dbeaver.tools.transfer.stream.importer.DataImporterCSV;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CSVImporterTest  extends DBeaverUnitTest {

    private static final Path DUMMY_FILE = Path.of("dummy");
    private final DataImporterCSV importer = new DataImporterCSV();
    private StreamEntityMapping mapping;
    private final Map<String, Object> properties = new HashMap<>();

    @Mock
    private IStreamDataImporterSite site;

    @BeforeEach
    public void init() throws DBException {
        mapping = new StreamEntityMapping(DUMMY_FILE);
        importer.init(site);
        Mockito.when(site.getProcessorProperties()).thenReturn(properties);
    }

    @Test
    public void generateColumnNames() throws DBException, IOException {
        List<StreamDataImporterColumnInfo> columnsInfo = readColumnsInfo("a,b,c,d", false);
        Assertions.assertEquals(4, columnsInfo.size());
        Assertions.assertEquals("Column1", columnsInfo.get(0).getName());
        Assertions.assertEquals("Column2", columnsInfo.get(1).getName());
        Assertions.assertEquals("Column3", columnsInfo.get(2).getName());
        Assertions.assertEquals("Column4", columnsInfo.get(3).getName());
    }

    @Test
    public void readColumnNames() throws DBException, IOException {
        List<StreamDataImporterColumnInfo> columnsInfo = readColumnsInfo("a,b,c,d", true);
        Assertions.assertEquals(4, columnsInfo.size());
        Assertions.assertEquals("a", columnsInfo.get(0).getName());
        Assertions.assertEquals("b", columnsInfo.get(1).getName());
        Assertions.assertEquals("c", columnsInfo.get(2).getName());
        Assertions.assertEquals("d", columnsInfo.get(3).getName());
    }

    @Test
    public void guessColumnTypes() throws DBException, IOException {
        List<StreamDataImporterColumnInfo> columnsInfo = readColumnsInfo("1,2.0,abc,false", false);
        Assertions.assertEquals(4, columnsInfo.size());
        Assertions.assertEquals(DBPDataKind.NUMERIC, columnsInfo.get(0).getDataKind());
        Assertions.assertEquals("INTEGER", columnsInfo.get(0).getTypeName());
        Assertions.assertEquals(DBPDataKind.NUMERIC, columnsInfo.get(1).getDataKind());
        Assertions.assertEquals("REAL", columnsInfo.get(1).getTypeName());
        Assertions.assertEquals(DBPDataKind.STRING, columnsInfo.get(2).getDataKind());
        Assertions.assertEquals(DBPDataKind.BOOLEAN, columnsInfo.get(3).getDataKind());
    }
  
    @Test
    public void guessColumnTypesWithLongData() throws DBException, IOException {
    	List<StreamDataImporterColumnInfo> columnsInfo = readColumnsInfo("2147483648,-9223372036854775808,1", false);
    	Assertions.assertEquals(3,  columnsInfo.size());
    	Assertions.assertEquals(DBPDataKind.NUMERIC, columnsInfo.get(0).getDataKind());
    	Assertions.assertEquals("BIGINT", columnsInfo.get(0).getTypeName());
    	Assertions.assertEquals(DBPDataKind.NUMERIC, columnsInfo.get(1).getDataKind());
    	Assertions.assertEquals("BIGINT", columnsInfo.get(1).getTypeName());
        Assertions.assertEquals(DBPDataKind.NUMERIC, columnsInfo.get(2).getDataKind());
        Assertions.assertEquals("INTEGER", columnsInfo.get(2).getTypeName());
    }
    
    @Test
    public void returnsEmptyListWithEmptyFile() throws DBException, IOException {
    	List<StreamDataImporterColumnInfo> columnsInfo = readColumnsInfo("", false);
    	Assertions.assertEquals(0,  columnsInfo.size());
    }
    

    @Test
    public void guessColumnTypesOverSamples() throws DBException, IOException {
        List<StreamDataImporterColumnInfo> columnsInfo = readColumnsInfo("1\n\n2\n3\ntest", false);
        Assertions.assertEquals(1, columnsInfo.size());
        Assertions.assertEquals(DBPDataKind.STRING, columnsInfo.get(0).getDataKind());
    }

    @Test
    public void guessColumnTypesDefault() throws DBException, IOException {
        List<StreamDataImporterColumnInfo> columnsInfo = readColumnsInfo(",", false);
        Assertions.assertEquals(2, columnsInfo.size());
        Assertions.assertEquals(DBPDataKind.STRING, columnsInfo.get(0).getDataKind());
        Assertions.assertEquals(DBPDataKind.STRING, columnsInfo.get(1).getDataKind());
    }

    private List<StreamDataImporterColumnInfo> readColumnsInfo(String data, boolean isHeaderPresent) throws DBException, IOException {
        properties.put("header", isHeaderPresent ? DataImporterCSV.HeaderPosition.top : DataImporterCSV.HeaderPosition.none);
        try (ByteArrayInputStream is = new ByteArrayInputStream(data.getBytes())) {
            return importer.readColumnsInfo(mapping, is);
        }
    }

    @Test
    void shortRowsWithWhitespaceTrimmingPreserveMissingColumnsAsNull() throws Exception {
        properties.put("trimWhitespaces", true);
        Assertions.assertEquals(java.util.Arrays.asList(java.util.Arrays.asList("one", null)),
            importRows("a,b\n  one  \n", 2));
    }

    @Test
    void quotedCommaQuotesAndMultilineCellsAreImportedIntact() throws Exception {
        Assertions.assertEquals(List.of(List.of("a,b", "say \"hi\""), List.of("line1\nline2", "中文")),
            importRows("a,b\n\"a,b\",\"say \"\"hi\"\"\"\n\"line1\nline2\",中文\n", 2));
    }

    @Test
    void emptyStringAndExplicitNullMarkerAreDistinct() throws Exception {
        properties.put("nullString", "<NULL>");
        Assertions.assertEquals(java.util.Arrays.asList(java.util.Arrays.asList("", null)),
            importRows("a,b\n,<NULL>\n", 2));
    }

    @Test
    void unterminatedQuotedCellRaisesImportError() {
        DBException error = Assertions.assertThrows(DBException.class,
            () -> importRows("a,b\n1,\"unfinished\n", 2));
        Assertions.assertInstanceOf(IOException.class, error.getCause());
        Assertions.assertTrue(error.getCause().getMessage().contains("Un-terminated quote"));
    }

    @Test
    void trimAndEmptyStringNullOptionsComposeWithoutLosingMissingCells() throws Exception {
        properties.put("trimWhitespaces", true);
        properties.put("emptyStringNull", true);
        Assertions.assertEquals(java.util.Arrays.asList(java.util.Arrays.asList(null, null, null)),
            importRows("a,b,c\n  ,  \n", 3));
    }

    private List<List<Object>> importRows(String data, int columns) throws Exception {
        properties.put("header", DataImporterCSV.HeaderPosition.top);
        properties.put("quoteChar", "\"");
        properties.put("delimiter", ",");
        var source = Mockito.mock(StreamEntityMapping.class);
        var infos = new java.util.ArrayList<StreamDataImporterColumnInfo>();
        for (int i = 0; i < columns; i++) {
            infos.add(new StreamDataImporterColumnInfo(source, i, "c" + i, "VARCHAR", 1000, DBPDataKind.STRING));
        }
        Mockito.when(source.getStreamColumns()).thenReturn(infos);
        Mockito.when(site.getSourceObject()).thenReturn(source);
        Mockito.when(site.getSettings()).thenReturn(Mockito.mock(
            org.jkiss.dbeaver.tools.transfer.stream.StreamProducerSettings.class));
        var dataSource = Mockito.mock(org.jkiss.dbeaver.model.DBPDataSource.class, Mockito.RETURNS_DEEP_STUBS);
        var monitor = Mockito.mock(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor.class);
        var consumer = Mockito.mock(IDataTransferConsumer.class);
        var rows = new java.util.ArrayList<List<Object>>();
        Mockito.doAnswer(invocation -> {
            org.jkiss.dbeaver.model.exec.DBCResultSet result = invocation.getArgument(1);
            var row = new java.util.ArrayList<Object>();
            for (int i = 0; i < columns; i++) {
                row.add(result.getAttributeValue(i));
            }
            rows.add(row);
            return null;
        }).when(consumer).fetchRow(Mockito.any(), Mockito.any());
        try (var input = new ByteArrayInputStream(data.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            importer.runImport(monitor, dataSource, input, consumer);
        }
        Mockito.verify(consumer).fetchEnd(Mockito.any(), Mockito.any());
        Mockito.verify(consumer).close();
        return rows;
    }
}
