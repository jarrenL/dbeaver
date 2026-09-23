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

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jkiss.dbeaver.data.office.export.DataExporterXLSX;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBPNamedObject;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.data.handlers.JDBCStringValueHandler;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.tools.transfer.stream.IStreamDataExporterSite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses the production streaming exporter and reopens the serialized XLSX, not a mocked workbook. */
public class GaussDBExcelExportTest {
    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"中文银行𠀀", "O'Reilly\n第二行", "=1+1", "+SUM(A1:A2)", "@SUM(A1:A2)", "001", ""})
    public void stringsAndFormulaLikeInputRemainLiteralText(String value) throws Exception {
        try (XSSFWorkbook workbook = export(DBPDataKind.STRING, Map.of(), new Object[]{value})) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("金额 中文", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(2, sheet.getPhysicalNumberOfRows());
            assertEquals(1, sheet.getRow(1).getPhysicalNumberOfCells());
            assertEquals(CellType.STRING, sheet.getRow(1).getCell(0).getCellType());
            assertEquals(value, sheet.getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    public void nullMarkerAndLiteralNullAreControlledByExportSettings() throws Exception {
        try (XSSFWorkbook workbook = export(DBPDataKind.STRING, Map.of("nullString", "<空>"),
            new Object[]{null}, new Object[]{"NULL"}, new Object[]{""})) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("<空>", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("NULL", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals("", sheet.getRow(3).getCell(0).getStringCellValue());
        }
    }

    @Test
    public void booleansCanRemainTypedOrUseLocalizedLabels() throws Exception {
        try (XSSFWorkbook workbook = export(DBPDataKind.BOOLEAN, Map.of(), new Object[]{true}, new Object[]{false})) {
            assertEquals(CellType.BOOLEAN, workbook.getSheetAt(0).getRow(1).getCell(0).getCellType());
            assertTrue(workbook.getSheetAt(0).getRow(1).getCell(0).getBooleanCellValue());
            assertFalse(workbook.getSheetAt(0).getRow(2).getCell(0).getBooleanCellValue());
        }
        try (XSSFWorkbook workbook = export(DBPDataKind.BOOLEAN, Map.of("trueString", "是", "falseString", "否"),
            new Object[]{true}, new Object[]{false})) {
            assertEquals("是", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals("否", workbook.getSheetAt(0).getRow(2).getCell(0).getStringCellValue());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775807", "-9223372036854775808", "12345678901234567890123456789012345678",
        "123456789012345.67890123456789012345678", "0.1234567890123456", "1E-400", "1E400"})
    public void exactNumbersOutsideSpreadsheetPrecisionArePreservedAsText(String value) throws Exception {
        BigDecimal number = new BigDecimal(value);
        try (XSSFWorkbook workbook = export(DBPDataKind.NUMERIC, Map.of(), new Object[]{number})) {
            var cell = workbook.getSheetAt(0).getRow(1).getCell(0);
            assertEquals(CellType.STRING, cell.getCellType(), "Must not silently round or underflow: " + value);
            assertEquals(number.toString(), cell.getStringCellValue());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "123.45", "-12.3400", "999999999999999", "0.00000000000000000000000000000000000001"})
    public void ordinaryNumbersRemainNumericCells(String value) throws Exception {
        BigDecimal number = new BigDecimal(value);
        try (XSSFWorkbook workbook = export(DBPDataKind.NUMERIC, Map.of(), new Object[]{number})) {
            var cell = workbook.getSheetAt(0).getRow(1).getCell(0);
            assertEquals(CellType.NUMERIC, cell.getCellType());
            assertEquals(0, number.compareTo(BigDecimal.valueOf(cell.getNumericCellValue())));
        }
    }

    @Test
    public void longAndBigIntegerJdbcValuesKeepTheirExactDigits() throws Exception {
        Number[] values = {Long.MIN_VALUE, Long.MAX_VALUE, new BigInteger("12345678901234567890123456789012345678")};
        for (Number value : values) {
            try (XSSFWorkbook workbook = export(DBPDataKind.NUMERIC, Map.of(), new Object[]{value})) {
                var cell = workbook.getSheetAt(0).getRow(1).getCell(0);
                assertEquals(CellType.STRING, cell.getCellType());
                assertEquals(value.toString(), cell.getStringCellValue());
            }
        }
    }

    @Test
    public void disabledHeaderDoesNotConsumeTheFirstDataRow() throws Exception {
        try (XSSFWorkbook workbook = export(DBPDataKind.STRING, Map.of("header", "none"),
            new Object[]{"第一行"}, new Object[]{"第二行"})) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(2, sheet.getPhysicalNumberOfRows());
            assertEquals("第一行", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("第二行", sheet.getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    public void rowLimitSplitsSheetsWithoutLosingRowsAndRepeatsHeaders() throws Exception {
        try (XSSFWorkbook workbook = export(DBPDataKind.STRING, Map.of("splitByRowCount", 3),
            new Object[]{"一"}, new Object[]{"二"}, new Object[]{"三"})) {
            assertEquals(2, workbook.getNumberOfSheets());
            assertEquals(3, workbook.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals(2, workbook.getSheetAt(1).getPhysicalNumberOfRows());
            assertEquals("金额 中文", workbook.getSheetAt(1).getRow(0).getCell(0).getStringCellValue());
            assertEquals("一", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals("二", workbook.getSheetAt(0).getRow(2).getCell(0).getStringCellValue());
            assertEquals("三", workbook.getSheetAt(1).getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    public void trimmingIsExplicitAndLongStringsObserveTheXlsxCellLimit() throws Exception {
        try (XSSFWorkbook workbook = export(DBPDataKind.STRING, Map.of(), new Object[]{"  原文  "},
            new Object[]{"中".repeat(32768)})) {
            assertEquals("  原文  ", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals(32767, workbook.getSheetAt(0).getRow(2).getCell(0).getStringCellValue().length());
        }
        try (XSSFWorkbook workbook = export(DBPDataKind.STRING, Map.of("trimString", true), new Object[]{"  原文  "})) {
            assertEquals("原文", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"use existing sheets", "create new sheets"})
    public void appendingWorkbookRetainsOldDataAndWritesNewRows(String strategy) throws Exception {
        Path existing = temporaryDirectory.resolve("existing.xlsx");
        try (XSSFWorkbook seed = new XSSFWorkbook(); var output = Files.newOutputStream(existing)) {
            var sheet = seed.createSheet("历史数据");
            sheet.createRow(0).createCell(0).setCellValue("原始表头");
            sheet.createRow(1).createCell(0).setCellValue("旧数据");
            seed.write(output);
        }
        byte[] original = Files.readAllBytes(existing);
        try (XSSFWorkbook workbook = export(DBPDataKind.STRING, Map.of("appendStrategy", strategy), existing,
            new Object[]{"新增一"}, new Object[]{"新增二"})) {
            assertEquals("原始表头", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("旧数据", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            boolean reuse = "use existing sheets".equals(strategy);
            assertEquals(reuse ? 1 : 2, workbook.getNumberOfSheets());
            var target = workbook.getSheetAt(reuse ? 0 : 1);
            assertEquals(reuse ? 4 : 3, target.getPhysicalNumberOfRows());
            assertEquals("新增一", target.getRow(reuse ? 2 : 1).getCell(0).getStringCellValue());
            assertEquals("新增二", target.getRow(reuse ? 3 : 2).getCell(0).getStringCellValue());
            if (!reuse) {
                assertEquals("金额 中文", target.getRow(0).getCell(0).getStringCellValue());
            }
        }
        // The input file is never the output stream in this test; verify reading it did not alter its bytes.
        assertArrayEquals(original, Files.readAllBytes(existing));
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 100})
    public void sparseWorkbookAppendsAfterLastRowWithoutFillingGaps(int lastRow) throws Exception {
        Path existing = temporaryDirectory.resolve("sparse.xlsx");
        try (XSSFWorkbook seed = new XSSFWorkbook(); var output = Files.newOutputStream(existing)) {
            var sheet = seed.createSheet("稀疏行");
            sheet.createRow(0).createCell(0).setCellValue("旧表头");
            sheet.createRow(lastRow).createCell(0).setCellValue("旧尾行");
            seed.write(output);
        }
        try (XSSFWorkbook workbook = export(DBPDataKind.STRING, Map.of("appendStrategy", "use existing sheets"),
            existing, new Object[]{"追加"})) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(3, sheet.getPhysicalNumberOfRows());
            assertEquals("旧表头", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("旧尾行", sheet.getRow(lastRow).getCell(0).getStringCellValue());
            assertEquals("追加", sheet.getRow(lastRow + 1).getCell(0).getStringCellValue());
            for (int i = 1; i < lastRow; i++) {
                assertNull(sheet.getRow(i), "Existing gaps must remain unchanged");
            }
        }
    }

    @Test
    public void rowLimitCanAppendAcrossMultipleExistingSheets() throws Exception {
        Path existing = temporaryDirectory.resolve("multiple.xlsx");
        try (XSSFWorkbook seed = new XSSFWorkbook(); var output = Files.newOutputStream(existing)) {
            for (int i = 0; i < 2; i++) {
                var sheet = seed.createSheet("历史" + i);
                sheet.createRow(0).createCell(0).setCellValue("旧表头" + i);
                sheet.createRow(1).createCell(0).setCellValue("旧数据" + i);
            }
            seed.write(output);
        }
        try (XSSFWorkbook workbook = export(DBPDataKind.STRING,
            Map.of("appendStrategy", "use existing sheets", "splitByRowCount", 3), existing,
            new Object[]{"追加0"}, new Object[]{"追加1"})) {
            assertEquals(2, workbook.getNumberOfSheets());
            for (int i = 0; i < 2; i++) {
                var sheet = workbook.getSheetAt(i);
                assertEquals(3, sheet.getPhysicalNumberOfRows());
                assertEquals("旧表头" + i, sheet.getRow(0).getCell(0).getStringCellValue());
                assertEquals("旧数据" + i, sheet.getRow(1).getCell(0).getStringCellValue());
                assertEquals("追加" + i, sheet.getRow(2).getCell(0).getStringCellValue());
            }
        }
    }

    @Test
    public void emptyImportedSheetStartsWithHeaderAtZero() throws Exception {
        Path existing = temporaryDirectory.resolve("empty.xlsx");
        try (XSSFWorkbook seed = new XSSFWorkbook(); var output = Files.newOutputStream(existing)) {
            seed.createSheet("空白");
            seed.write(output);
        }
        try (XSSFWorkbook workbook = export(DBPDataKind.STRING, Map.of("appendStrategy", "use existing sheets"),
            existing, new Object[]{"首行"})) {
            assertEquals(1, workbook.getNumberOfSheets());
            var sheet = workbook.getSheetAt(0);
            assertEquals(2, sheet.getPhysicalNumberOfRows());
            assertEquals("金额 中文", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("首行", sheet.getRow(1).getCell(0).getStringCellValue());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1900-01-01 00:00:00", "2000-02-29 12:34:56", "2026-09-24 23:59:59.123"})
    public void dateCellsPreserveLocalDateTimeAndRequestedFormat(String value) throws Exception {
        Timestamp timestamp = Timestamp.valueOf(value);
        String format = "yyyy-mm-dd hh:mm:ss.000";
        try (XSSFWorkbook workbook = export(DBPDataKind.DATETIME, Map.of("dateFormat", format),
            new Object[]{timestamp})) {
            var cell = workbook.getSheetAt(0).getRow(1).getCell(0);
            assertEquals(CellType.NUMERIC, cell.getCellType());
            assertEquals(format, cell.getCellStyle().getDataFormatString());
            assertEquals(timestamp.toLocalDateTime(), cell.getLocalDateTimeCellValue());
        }
    }

    @Test
    public void dateBeforeSpreadsheetEpochUsesFormattedText() throws Exception {
        try (XSSFWorkbook workbook = export(DBPDataKind.DATETIME, Map.of("dateFormat", "yyyy-MM-dd HH:mm:ss"),
            new Object[]{Timestamp.valueOf("1899-12-31 12:34:56")})) {
            var cell = workbook.getSheetAt(0).getRow(1).getCell(0);
            assertEquals(CellType.STRING, cell.getCellType());
            assertEquals("1899-12-31 12:34:56", cell.getStringCellValue());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NUMERIC", "BOOLEAN"})
    public void typedNullExportsAsEmptyTextRatherThanZeroOrFalse(String kind) throws Exception {
        try (XSSFWorkbook workbook = export(DBPDataKind.valueOf(kind), Map.of(), new Object[]{null})) {
            var cell = workbook.getSheetAt(0).getRow(1).getCell(0);
            assertEquals(CellType.STRING, cell.getCellType());
            assertEquals("", cell.getStringCellValue());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"label", "none"})
    public void rowNumberOptionShiftsDataWithoutOverwritingIt(String header) throws Exception {
        try (XSSFWorkbook workbook = export(DBPDataKind.STRING, Map.of("header", header, "rownumber", true),
            new Object[]{"第一条"}, new Object[]{"第二条"})) {
            var sheet = workbook.getSheetAt(0);
            int start = "label".equals(header) ? 1 : 0;
            assertEquals(start + 2, sheet.getPhysicalNumberOfRows());
            DataFormatter formatter = new DataFormatter();
            for (int i = 0; i < 2; i++) {
                var row = sheet.getRow(start + i);
                assertEquals(2, row.getPhysicalNumberOfCells());
                // Existing option uses the sheet row index (zero when the header is disabled).
                assertEquals(Integer.toString(start + i), formatter.formatCellValue(row.getCell(0)));
                assertEquals(i == 0 ? "第一条" : "第二条", row.getCell(1).getStringCellValue());
            }
            if (start == 1) {
                assertEquals("金额 中文", sheet.getRow(0).getCell(1).getStringCellValue());
                assertNull(sheet.getRow(0).getCell(0));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    public void emptyDateFormatFallsBackWithoutLosingHistoricalDate(String format) throws Exception {
        try (XSSFWorkbook workbook = export(DBPDataKind.DATETIME, Map.of("dateFormat", format),
            new Object[]{Timestamp.valueOf("1899-12-31 12:34:56")},
            new Object[]{Timestamp.valueOf("2026-09-24 12:34:56")})) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("12/31/99", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("MM/dd/yy", sheet.getRow(2).getCell(0).getCellStyle().getDataFormatString());
            assertEquals(Timestamp.valueOf("2026-09-24 12:34:56").toLocalDateTime(),
                sheet.getRow(2).getCell(0).getLocalDateTimeCellValue());
        }
    }

    @Test
    public void defaultDateSettingsUseTheDocumentedDisplayFormat() throws Exception {
        assertEquals("MM/dd/yy", DataExporterXLSX.getDefaultProperties().get("dateFormat"));
        try (XSSFWorkbook workbook = export(DBPDataKind.DATETIME, Map.of(),
            new Object[]{Timestamp.valueOf("1899-12-31 12:34:56")})) {
            assertEquals("12/31/99", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    public void nullDateFormatUsesDefaultRatherThanFailing() throws Exception {
        Map<String, Object> options = new java.util.HashMap<>();
        options.put("dateFormat", null);
        try (XSSFWorkbook workbook = export(DBPDataKind.DATETIME, options,
            new Object[]{Timestamp.valueOf("1899-12-31 12:34:56")})) {
            assertEquals("12/31/99", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
        }
    }

    private XSSFWorkbook export(DBPDataKind kind, Map<String, Object> overrides, Object[]... rows) throws Exception {
        return export(kind, overrides, null, rows);
    }

    private XSSFWorkbook export(DBPDataKind kind, Map<String, Object> overrides, Path existing, Object[]... rows)
        throws Exception {
        IStreamDataExporterSite site = mock(IStreamDataExporterSite.class);
        var properties = DataExporterXLSX.getDefaultProperties();
        properties.putAll(overrides);
        when(site.getProperties()).thenReturn(properties);
        when(site.getSource()).thenReturn(mock(DBPNamedObject.class));
        when(site.getExportFormat()).thenReturn(DBDDisplayFormat.NATIVE);
        DBDAttributeBinding column = mock(DBDAttributeBinding.class);
        when(column.getName()).thenReturn("amount");
        when(column.getLabel()).thenReturn("金额 中文");
        when(column.getDataKind()).thenReturn(kind);
        when(column.getValueHandler()).thenReturn(JDBCStringValueHandler.INSTANCE);
        when(site.getAttributes()).thenReturn(new DBDAttributeBinding[]{column});
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        when(site.getOutputStream()).thenReturn(output);
        when(site.getOutputFile()).thenReturn(existing);
        DBCSession session = mock(DBCSession.class);
        when(session.getProgressMonitor()).thenReturn(new VoidProgressMonitor());
        DBCResultSet resultSet = mock(DBCResultSet.class);
        when(resultSet.getSession()).thenReturn(session);
        DataExporterXLSX exporter = new DataExporterXLSX();
        if (existing != null) {
            exporter.importData(site);
        }
        exporter.init(site);
        try {
            exporter.exportHeader(session);
            for (Object[] row : rows) {
                exporter.exportRow(session, resultSet, row);
            }
            exporter.exportFooter(new VoidProgressMonitor());
        } finally {
            exporter.dispose();
        }
        assertTrue(output.size() > 0);
        return new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()));
    }
}
