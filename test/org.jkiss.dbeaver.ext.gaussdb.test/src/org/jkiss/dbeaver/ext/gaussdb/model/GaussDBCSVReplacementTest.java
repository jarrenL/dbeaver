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

import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.data.handlers.JDBCStringValueHandler;
import org.jkiss.dbeaver.tools.transfer.stream.IStreamDataExporterSite;
import org.jkiss.dbeaver.tools.transfer.stream.exporter.DataExporterCSV;
import org.jkiss.utils.csv.CSVReader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Production CSV serialization and the CSV reader used by the importer, without a database or UI. */
public class GaussDBCSVReplacementTest {
    @ParameterizedTest
    @ValueSource(strings = {"$", "$1", "${line}", "\\", "C:\\csv", "\"$,", "中文😀"})
    public void replacementIsLiteralNotARegexReplacementProgram(String marker) throws Exception {
        String changed = "甲\"" + marker + "乙," + marker + "𠀀";
        String plain = "原文$1\\path";
        String csv = export(marker, "甲\"\n乙,\r\n𠀀", plain);
        assertEquals("\"" + changed.replace("\"", "\"\"") + "\"," + plain + "\n", csv);
        try (CSVReader reader = new CSVReader(new StringReader(csv), ",", "\"", "\0")) {
            assertArrayEquals(new String[]{changed, plain}, reader.readNext());
            assertNull(reader.readNext(), "Replacement must not add data rows");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\t", "\\n", "\\r\\n"})
    public void documentedControlCharacterEscapesStillExpand(String marker) throws Exception {
        String expanded = marker.replace("\\t", "\t").replace("\\n", "\n").replace("\\r", "\r");
        assertEquals("\"左" + expanded + "右\"\n", export(marker, "左\n右"));
    }

    private String export(String marker, Object... row) throws Exception {
        var site = mock(IStreamDataExporterSite.class);
        when(site.getProperties()).thenReturn(Map.of(
            "delimiter", ",", "rowDelimiter", "\\n", "header", "none",
            "quoteChar", "\"", "lineFeedEscapeString", marker));
        when(site.getExportFormat()).thenReturn(DBDDisplayFormat.NATIVE);
        DBDAttributeBinding[] columns = new DBDAttributeBinding[row.length];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = mock(DBDAttributeBinding.class);
            when(columns[i].getName()).thenReturn("c" + i);
            when(columns[i].getDataKind()).thenReturn(DBPDataKind.STRING);
            when(columns[i].getValueHandler()).thenReturn(JDBCStringValueHandler.INSTANCE);
        }
        when(site.getAttributes()).thenReturn(columns);
        StringWriter text = new StringWriter();
        when(site.getWriter()).thenReturn(new PrintWriter(text));
        var exporter = new DataExporterCSV();
        exporter.init(site);
        exporter.exportHeader(mock(DBCSession.class));
        exporter.exportRow(null, null, row);
        exporter.exportFooter(null);
        exporter.dispose();
        return text.toString();
    }
}
