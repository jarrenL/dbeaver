/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.jkiss.dbeaver.ext.postgresql.model.PostgreProcedureParameter;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDataSource;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDialect;
import org.jkiss.dbeaver.ext.postgresql.edit.PostgreCommandGrantPrivilege;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDatabase;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDefaultPrivilege;
import org.jkiss.dbeaver.ext.postgresql.model.PostgrePrivilegeGrant;
import org.jkiss.dbeaver.ext.postgresql.model.PostgrePrivilegeType;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreRoleReference;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreSchema;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreTable;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameterKind;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** Opt-in JDBC contracts, not a replacement for an actual debugger or GUI test. */
class GaussDBHistoricalJdbcLiveTest extends org.jkiss.junit.DBeaverUnitTest {
    private static class SequenceActions extends org.jkiss.dbeaver.ext.postgresql.edit.PostgreSequenceManager {
        private org.jkiss.dbeaver.ext.postgresql.model.PostgreSequence model(String schemaName, String name, String description) {
            var source = mock(GaussDBDataSource.class);
            when(source.getSQLDialect()).thenReturn(new org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDialect());
            var schema = mock(org.jkiss.dbeaver.ext.gaussdb.model.GaussDBSchema.class);
            when(schema.getDataSource()).thenReturn(source);
            when(schema.getName()).thenReturn(schemaName);
            var sequence = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreSequence.class);
            when(sequence.getDataSource()).thenReturn(source);
            when(sequence.getSchema()).thenReturn(schema);
            when(sequence.getName()).thenReturn(name);
            when(sequence.getDescription()).thenReturn(description);
            String qualifiedName = org.jkiss.dbeaver.model.DBUtils.getQuotedIdentifier(schema) + "."
                + org.jkiss.dbeaver.model.DBUtils.getQuotedIdentifier(source, name);
            when(sequence.getFullyQualifiedName(org.jkiss.dbeaver.model.DBPEvaluationContext.DDL)).thenReturn(qualifiedName);
            return sequence;
        }

        String rename(String schema, String oldName, String newName) throws Exception {
            var sequence = model(schema, oldName, null);
            // Probe server SQL support independently of the GaussDB client capability gate.
            when(sequence.supportsSequenceRename()).thenReturn(true);
            var command = new ObjectRenameCommand(sequence, "Rename test sequence", java.util.Map.of(), newName);
            var actions = new java.util.ArrayList<org.jkiss.dbeaver.model.edit.DBEPersistAction>();
            addObjectRenameActions(new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(),
                mock(org.jkiss.dbeaver.model.exec.DBCExecutionContext.class), actions, command, java.util.Map.of());
            assertEquals(1, actions.size());
            return actions.get(0).getScript();
        }

        String comment(String schema, String name, String description) {
            var command = new ObjectChangeCommand(model(schema, name, description)) {
                @Override
                public boolean hasProperty(Object id) {
                    return org.jkiss.dbeaver.model.DBConstants.PROP_ID_DESCRIPTION.equals(id);
                }
            };
            var actions = new java.util.ArrayList<org.jkiss.dbeaver.model.edit.DBEPersistAction>();
            addObjectExtraActions(new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(),
                mock(org.jkiss.dbeaver.model.exec.DBCExecutionContext.class), actions, command, java.util.Map.of());
            assertEquals(1, actions.size());
            return actions.get(0).getScript();
        }

        String action(String qualifiedName, boolean create) {
            var sequence = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreSequence.class);
            when(sequence.getFullyQualifiedName(org.jkiss.dbeaver.model.DBPEvaluationContext.DDL)).thenReturn(qualifiedName);
            var actions = new java.util.ArrayList<org.jkiss.dbeaver.model.edit.DBEPersistAction>();
            var context = mock(org.jkiss.dbeaver.model.exec.DBCExecutionContext.class);
            if (create) {
                var command = mock(ObjectCreateCommand.class);
                when(command.getObject()).thenReturn(sequence);
                addObjectCreateActions(new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(),
                    context, actions, command, java.util.Map.of());
            } else {
                var command = new ObjectDeleteCommand(sequence, "Delete test sequence");
                addObjectDeleteActions(new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(),
                    context, actions, command, java.util.Map.of());
            }
            assertEquals(1, actions.size());
            return actions.get(0).getScript();
        }
    }

    @Test
    void sequenceManagerCreateDropAgainstDistributedDatabase() throws Exception {
        assertSequenceManagerLifecycle(System.getenv("GAUSSDB_HISTORY_CONNECTION"));
    }

    @Test
    void sequenceManagerRenameAndCommentAgainstDistributedDatabase() throws Exception {
        assertSequenceRenameAndComment(System.getenv("GAUSSDB_HISTORY_CONNECTION"));
    }

    @Test
    void sequenceManagerRenameAndCommentAgainstCentralizedDatabase() throws Exception {
        assertSequenceRenameAndComment(System.getenv("GAUSSDB_HISTORY_CENTRAL_CONNECTION"));
    }

    private void assertSequenceRenameAndComment(String config) throws Exception {
        inIsolatedSchema((c, s) -> {
            var manager = new SequenceActions();
            String oldName = s + ".\"流水 号\"";
            String newName = s + ".\"流水\"\"改名\"";
            execute(c, manager.action(oldName, true));
            assertRows(c, "SELECT nextval('" + oldName + "')", List.of(List.of("1")));
            String oid;
            try (var statement = c.createStatement(); var rows = statement.executeQuery("SELECT '" + oldName + "'::regclass::oid")) {
                assertTrue(rows.next());
                oid = rows.getString(1);
            }
            String description = "中文 '注释'; SELECT 99;\n第二行";
            execute(c, manager.comment(s, "流水 号", description));
            try {
                execute(c, manager.rename(s, "流水 号", "流水\"改名"));
            } catch (java.sql.SQLException failure) {
                if ("0A000".equals(failure.getSQLState()) && failure.getMessage().contains("RENAME SEQUENCE is not yet supported")) {
                    assumeTrue(false, "Server rejects sequence rename; rename preservation/collision positive paths not verified");
                }
                throw failure;
            }
            assertRows(c, "SELECT '" + newName + "'::regclass::oid,nextval('" + newName + "'),obj_description('"
                + newName + "'::regclass)", List.of(List.of(oid, "2", description)));
            assertEquals("42P01", assertThrows(java.sql.SQLException.class,
                () -> execute(c, "SELECT nextval('" + oldName + "')")).getSQLState());
            execute(c, manager.action(s + ".occupied", true));
            assertEquals("42P07", assertThrows(java.sql.SQLException.class,
                () -> execute(c, manager.rename(s, "流水\"改名", "occupied"))).getSQLState());
            assertRows(c, "SELECT '" + newName + "'::regclass::oid,nextval('" + newName + "')", List.of(List.of(oid, "3")));
            execute(c, manager.comment(s, "流水\"改名", null));
            assertRows(c, "SELECT obj_description('" + newName + "'::regclass) IS NULL", List.of(List.of("t")));
        }, java.util.Map.of(), config);
    }

    @Test
    void sequenceManagerCommentRoundtripAgainstDistributedDatabase() throws Exception {
        assertSequenceComment(System.getenv("GAUSSDB_HISTORY_CONNECTION"));
    }

    @Test
    void sequenceManagerCommentRoundtripAgainstCentralizedDatabase() throws Exception {
        assertSequenceComment(System.getenv("GAUSSDB_HISTORY_CENTRAL_CONNECTION"));
    }

    private void assertSequenceComment(String config) throws Exception {
        inIsolatedSchema((c, s) -> {
            var manager = new SequenceActions();
            String name = s + ".\"流水 号\"";
            execute(c, manager.action(name, true));
            assertRows(c, "SELECT nextval('" + name + "')", List.of(List.of("1")));
            String description = "中文 '注释'; SELECT 99;\n第二行";
            execute(c, manager.comment(s, "流水 号", description));
            assertRows(c, "SELECT obj_description('" + name + "'::regclass),nextval('" + name + "')",
                List.of(List.of(description, "2")));
            execute(c, manager.comment(s, "流水 号", null));
            assertRows(c, "SELECT obj_description('" + name + "'::regclass) IS NULL,nextval('" + name + "')",
                List.of(List.of("t", "3")));
        }, java.util.Map.of(), config);
    }

    @Test
    void sequenceManagerCreateDropAgainstCentralizedDatabase() throws Exception {
        assertSequenceManagerLifecycle(System.getenv("GAUSSDB_HISTORY_CENTRAL_CONNECTION"));
    }

    private void assertSequenceManagerLifecycle(String config) throws Exception {
        inIsolatedSchema((c, s) -> {
            String name = s + ".\"流水 号\"";
            var manager = new SequenceActions();
            execute(c, manager.action(name, true));
            assertRows(c, "SELECT nextval('" + name + "'),nextval('" + name + "')", List.of(List.of("1", "2")));
            assertRows(c, "SELECT relkind FROM pg_class WHERE oid='" + name + "'::regclass", List.of(List.of("S")));
            execute(c, manager.action(name, false));
            assertEquals("42P01", assertThrows(java.sql.SQLException.class,
                () -> execute(c, "SELECT nextval('" + name + "')")).getSQLState());
            execute(c, manager.action(name, true));
            assertRows(c, "SELECT nextval('" + name + "')", List.of(List.of("1")));
            execute(c, manager.action(name, false));
            assertRows(c, "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace"
                + " WHERE n.nspname='" + s + "' AND c.relname='流水 号'", List.of(List.of("0")));
        }, java.util.Map.of(), config);
    }

    @Test
    void intervalPartitionDdlRetainsAutomaticMonthlyExpansion() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".interval_roundtrip";
            try {
                execute(c, "CREATE TABLE " + table + "(id int, event_date timestamp) "
                    + "PARTITION BY RANGE(event_date) INTERVAL('1 month') "
                    + "(PARTITION p_initial VALUES LESS THAN('2024-02-01'))");
            } catch (java.sql.SQLException failure) {
                if (failure.getMessage() != null && failure.getMessage().contains(
                    "Interval partitioned table is only supported in single-node mode")) {
                    assumeTrue(false, "Server restricts interval partitions to single-node mode (SQLSTATE "
                        + failure.getSQLState() + "); positive path not verified");
                }
                throw failure;
            }
            String partitions = "SELECT count(*) FROM pg_partition WHERE parentid='" + table + "'::regclass AND parttype='p'";
            assertRows(c, partitions, List.of(List.of("1")));
            execute(c, "INSERT INTO " + table + " VALUES(1,'2024-01-31'),(2,'2024-02-29'),(3,'2024-03-01')");
            assertRows(c, partitions, List.of(List.of("3")));
            String ddl = readProductionTableDdl(c, table);
            assertNotNull(ddl);
            assertTrue(ddl.toUpperCase(java.util.Locale.ROOT).contains("INTERVAL"));
            execute(c, "DROP TABLE " + table);
            execute(c, ddl);
            // pg_get_tabledef exports the declared initial partition and interval policy,
            // not the data-dependent partitions generated while populating the old table.
            assertRows(c, partitions, List.of(List.of("1")));
            execute(c, "INSERT INTO " + table + " VALUES(2,'2024-02-29'),(3,'2024-03-01'),(4,'2024-04-01')");
            assertRows(c, partitions, List.of(List.of("4")));
            assertRows(c, "SELECT id,to_char(event_date,'YYYY-MM-DD') FROM " + table + " ORDER BY id",
                List.of(List.of("2", "2024-02-29"), List.of("3", "2024-03-01"), List.of("4", "2024-04-01")));
        }, java.util.Map.of(), System.getenv("GAUSSDB_HISTORY_CENTRAL_CONNECTION") != null
            ? System.getenv("GAUSSDB_HISTORY_CENTRAL_CONNECTION") : System.getenv("GAUSSDB_HISTORY_CONNECTION"));
    }

    @Test
    void hashPartitionDdlRoundtripPreservesCompleteDisjointRouting() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".hash_roundtrip";
            execute(c, "CREATE TABLE " + table + "(id int, bucket_key int) DISTRIBUTE BY HASH(id) "
                + "PARTITION BY HASH(bucket_key)(PARTITION p0,PARTITION p1,PARTITION p2,PARTITION p3)");
            String ddl = readProductionTableDdl(c, table);
            assertNotNull(ddl);
            assertTrue(ddl.toUpperCase(java.util.Locale.ROOT).contains("PARTITION BY HASH"));
            execute(c, "DROP TABLE " + table);
            execute(c, ddl);
            execute(c, "INSERT INTO " + table + " SELECT i,CASE WHEN i=0 THEN NULL ELSE i-10 END FROM generate_series(0,20) AS i");
            var partitionIds = new java.util.ArrayList<Integer>();
            for (String partition : List.of("p0", "p1", "p2", "p3")) {
                try (var statement = c.createStatement(); var rows = statement.executeQuery(
                    "SELECT id FROM " + table + " PARTITION(" + partition + ")")) {
                    while (rows.next()) partitionIds.add(rows.getInt(1));
                }
            }
            partitionIds.sort(Integer::compareTo);
            assertEquals(java.util.stream.IntStream.rangeClosed(0, 20).boxed().toList(), partitionIds);
            assertEquals(21, count(c, table));
            assertRows(c, "SELECT id FROM " + table + " WHERE bucket_key IS NULL", List.of(List.of("0")));
            assertRows(c, "SELECT relname FROM pg_partition WHERE parentid='" + table
                + "'::regclass AND parttype='p' ORDER BY relname",
                List.of(List.of("p0"), List.of("p1"), List.of("p2"), List.of("p3")));
        });
    }

    @Test
    void listPartitionDdlRoundtripPreservesUnicodeRoutingAndMutation() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".list_roundtrip";
            execute(c, "CREATE TABLE " + table + "(id int, region varchar(20)) DISTRIBUTE BY HASH(id) "
                + "PARTITION BY LIST(region)(PARTITION p_cn VALUES('华北','华南'),PARTITION p_other VALUES('海外'))");
            String ddl = readProductionTableDdl(c, table);
            assertNotNull(ddl);
            assertTrue(ddl.toUpperCase(java.util.Locale.ROOT).contains("PARTITION BY LIST"));
            execute(c, "DROP TABLE " + table);
            execute(c, ddl);
            execute(c, "INSERT INTO " + table + " VALUES(1,'华北'),(2,'华南'),(3,'海外')");
            assertRows(c, "SELECT id,region FROM " + table + " PARTITION(p_cn) ORDER BY id",
                List.of(List.of("1", "华北"), List.of("2", "华南")));
            assertRows(c, "SELECT id FROM " + table + " PARTITION(p_other)", List.of(List.of("3")));
            execute(c, "ALTER TABLE " + table + " ADD PARTITION p_west VALUES('西部')");
            execute(c, "INSERT INTO " + table + " VALUES(4,'西部')");
            assertRows(c, "SELECT id FROM " + table + " PARTITION(p_west)", List.of(List.of("4")));
            execute(c, "ALTER TABLE " + table + " DROP PARTITION p_other");
            assertRows(c, "SELECT id FROM " + table + " ORDER BY id", List.of(List.of("1"), List.of("2"), List.of("4")));
            assertRows(c, "SELECT relname FROM pg_partition WHERE parentid='" + table
                + "'::regclass AND parttype='p' ORDER BY relname", List.of(List.of("p_cn"), List.of("p_west")));
        });
    }

    @Test
    void productionTableDdlRebuildsRangePartitionBoundariesAndNames() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".range_roundtrip";
            execute(c, "CREATE TABLE " + table + "(id int, label varchar(40)) DISTRIBUTE BY HASH(id) "
                + "PARTITION BY RANGE(id)(PARTITION p_negative VALUES LESS THAN(0),"
                + "PARTITION p_positive VALUES LESS THAN(100),PARTITION p_rest VALUES LESS THAN(MAXVALUE))");
            String ddl = readProductionTableDdl(c, table);
            assertNotNull(ddl);
            assertTrue(ddl.toUpperCase(java.util.Locale.ROOT).contains("PARTITION BY RANGE"));
            execute(c, "DROP TABLE " + table);
            execute(c, ddl);
            execute(c, "INSERT INTO " + table + " VALUES(-1,'negative'),(0,'zero'),(99,'edge'),(100,'rest'),(2147483647,'max')");
            assertRows(c, "SELECT id FROM " + table + " PARTITION(p_negative)", List.of(List.of("-1")));
            assertRows(c, "SELECT id FROM " + table + " PARTITION(p_positive) ORDER BY id",
                List.of(List.of("0"), List.of("99")));
            assertRows(c, "SELECT id FROM " + table + " PARTITION(p_rest) ORDER BY id",
                List.of(List.of("100"), List.of("2147483647")));
            assertRows(c, "SELECT relname FROM pg_partition WHERE parentid='" + table
                + "'::regclass AND parttype='p' ORDER BY relname",
                List.of(List.of("p_negative"), List.of("p_positive"), List.of("p_rest")));
        });
    }

    @Test
    void rangePartitionAddAndDropUpdateRoutingCatalogAndSurvivingRows() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".partition_rows";
            execute(c, "CREATE TABLE " + table + "(id int, label text) DISTRIBUTE BY HASH(id) "
                + "PARTITION BY RANGE(id)(PARTITION p_low VALUES LESS THAN(10),PARTITION p_mid VALUES LESS THAN(20))");
            execute(c, "INSERT INTO " + table + " VALUES(1,'low'),(9,'low-edge'),(10,'mid-edge'),(19,'mid')");
            assertRows(c, "SELECT id FROM " + table + " PARTITION(p_low) ORDER BY id",
                List.of(List.of("1"), List.of("9")));
            assertRows(c, "SELECT id FROM " + table + " PARTITION(p_mid) ORDER BY id",
                List.of(List.of("10"), List.of("19")));
            execute(c, "ALTER TABLE " + table + " ADD PARTITION p_high VALUES LESS THAN(30)");
            execute(c, "INSERT INTO " + table + " VALUES(20,'high-edge'),(29,'中文')");
            assertRows(c, "SELECT id FROM " + table + " PARTITION(p_high) ORDER BY id",
                List.of(List.of("20"), List.of("29")));
            String catalog = "SELECT relname FROM pg_partition WHERE parentid='" + table
                + "'::regclass AND parttype='p' ORDER BY relname";
            assertRows(c, catalog, List.of(List.of("p_high"), List.of("p_low"), List.of("p_mid")));
            execute(c, "ALTER TABLE " + table + " DROP PARTITION p_mid");
            assertRows(c, catalog, List.of(List.of("p_high"), List.of("p_low")));
            assertRows(c, "SELECT id,label FROM " + table + " ORDER BY id",
                List.of(List.of("1", "low"), List.of("9", "low-edge"), List.of("20", "high-edge"), List.of("29", "中文")));
        });
    }

    @Test
    void nonexistentDatabaseIsRejectedAndValidConnectionsRemainUsable() throws Exception {
        inIsolatedSchema((c, s) -> {
            String missing = "dbv_missing_" + UUID.randomUUID().toString().replace("-", "");
            try (var check = c.prepareStatement("SELECT count(*) FROM pg_database WHERE datname=?")) {
                check.setString(1, missing);
                try (var rows = check.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
            }
            var failure = assertThrows(java.sql.SQLException.class, () ->
                withIndependentConnection(connection -> fail("Nonexistent database must not connect"), missing));
            assertRows(c, "SELECT 11", List.of(List.of("11")));
            withIndependentConnection(connection -> assertRows(connection, "SELECT 12", List.of(List.of("12"))));
            if ("28000".equals(failure.getSQLState())) {
                String message = failure.getMessage().toLowerCase(java.util.Locale.ROOT);
                assertTrue(message.contains("hba"), "Expected database-specific HBA rejection: " + failure.getMessage());
                assumeTrue(false, "HBA rejects the unknown database before database lookup; 3D000 path not tested");
            }
            assertEquals("3D000", failure.getSQLState());
        });
    }

    @Test
    void enumValuesUseDeclarationOrderAndPreserveUnicodeLabels() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TYPE " + s + ".status_value AS ENUM ('等待','处理中','完成')");
            execute(c, "CREATE TABLE " + s + ".enum_values(id int, value " + s + ".status_value)");
            execute(c, "INSERT INTO " + s + ".enum_values VALUES(1,'完成'),(2,'等待'),(3,'处理中'),(4,NULL)");
            assertRows(c, "SELECT id,value FROM " + s + ".enum_values WHERE value IS NOT NULL ORDER BY value",
                List.of(List.of("2", "等待"), List.of("3", "处理中"), List.of("1", "完成")));
            try (var statement = c.createStatement(); var rows = statement.executeQuery(
                "SELECT value FROM " + s + ".enum_values WHERE id=4")) {
                assertTrue(rows.next());
                assertNull(rows.getString(1));
                assertTrue(rows.wasNull());
            }
            try (var insert = c.prepareStatement("INSERT INTO " + s + ".enum_values VALUES(5,?::" + s + ".status_value)")) {
                insert.setString(1, "非法枚举");
                var failure = assertThrows(java.sql.SQLException.class, insert::executeUpdate);
                assertEquals("22P02", failure.getSQLState());
            }
            assertRows(c, "SELECT count(*) FROM " + s + ".enum_values", List.of(List.of("4")));
        });
    }

    @Test
    void rangeValuesDistinguishBoundsEmptyAndSqlNull() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".range_values(id int, value int4range)");
            execute(c, "INSERT INTO " + s + ".range_values VALUES(1,'[1,5)'),(2,'empty'),(3,NULL),(4,'(,5)')");
            assertRows(c, "SELECT value::text,lower(value),upper(value),lower_inc(value),upper_inc(value),"
                    + "value @> 1,value @> 5 FROM " + s + ".range_values WHERE id=1",
                List.of(List.of("[1,5)", "1", "5", "t", "f", "t", "f")));
            try (var statement = c.createStatement(); var rows = statement.executeQuery(
                "SELECT value,isempty(value),lower_inf(value) FROM " + s + ".range_values ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals("int4range", rows.getMetaData().getColumnTypeName(1));
                assertNotNull(rows.getObject(1));
                assertTrue(rows.next());
                assertEquals("empty", rows.getString(1));
                assertTrue(rows.getBoolean(2));
                assertTrue(rows.next());
                assertNull(rows.getObject(1));
                assertTrue(rows.wasNull());
                assertTrue(rows.next());
                assertTrue(rows.getBoolean(3));
                assertFalse(rows.next());
            }
        });
    }

    @Test
    void textArrayPreservesEscapesAndDistinguishesNullArrayFromEmptyArray() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".text_arrays(id int, value text[])");
            String[] values = {"中文,逗号", "quoted\"value", "slash\\value", "line1\nline2", "NULL", null};
            var array = c.createArrayOf("text", values);
            try (var insert = c.prepareStatement("INSERT INTO " + s + ".text_arrays VALUES(1,?)")) {
                insert.setArray(1, array);
                assertEquals(1, insert.executeUpdate());
            } finally {
                array.free();
            }
            execute(c, "INSERT INTO " + s + ".text_arrays VALUES(2,ARRAY[]::text[]),(3,NULL)");
            try (var statement = c.createStatement(); var rows = statement.executeQuery(
                "SELECT value FROM " + s + ".text_arrays ORDER BY id")) {
                assertTrue(rows.next());
                var actual = rows.getArray(1);
                assertFalse(rows.wasNull());
                try { assertArrayEquals(values, (Object[]) actual.getArray()); }
                finally { actual.free(); }
                assertTrue(rows.next());
                actual = rows.getArray(1);
                assertFalse(rows.wasNull());
                try { assertEquals(0, ((Object[]) actual.getArray()).length); }
                finally { actual.free(); }
                assertTrue(rows.next());
                assertNull(rows.getArray(1));
                assertTrue(rows.wasNull());
                assertFalse(rows.next());
            }
        });
    }

    @Test
    void compositeValueRetainsNamedFieldsAndSqlNulls() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TYPE " + s + ".address_value AS (label text, amount numeric(12,4))");
            execute(c, "CREATE TABLE " + s + ".composites(id int, value " + s + ".address_value)");
            try (var insert = c.prepareStatement("INSERT INTO " + s + ".composites VALUES(1,ROW(?,?)::" + s + ".address_value)")) {
                insert.setString(1, "中文,\"引号\"");
                insert.setBigDecimal(2, new java.math.BigDecimal("123.4500"));
                assertEquals(1, insert.executeUpdate());
            }
            execute(c, "INSERT INTO " + s + ".composites VALUES(2,NULL)");
            try (var statement = c.createStatement(); var rows = statement.executeQuery(
                "SELECT value,(value).label,(value).amount FROM " + s + ".composites ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals("\"" + s + "\".\"address_value\"", rows.getMetaData().getColumnTypeName(1));
                assertNotNull(rows.getObject(1));
                assertEquals("中文,\"引号\"", rows.getString(2));
                assertEquals(new java.math.BigDecimal("123.4500"), rows.getBigDecimal(3));
                assertTrue(rows.next());
                assertNull(rows.getObject(1));
                assertTrue(rows.wasNull());
                assertNull(rows.getString(2));
                assertNull(rows.getBigDecimal(3));
                assertFalse(rows.next());
            }
        });
    }

    @Test
    void dateArithmeticCrossesLeapDayAndYearWithoutLosingMicroseconds() throws Exception {
        inIsolatedSchema((c, s) -> {
            try (var statement = c.createStatement(); var rows = statement.executeQuery(
                "SELECT to_char(TIMESTAMP '2024-02-28 23:59:59.123456' + INTERVAL '1 day',"
                    + "'YYYY-MM-DD HH24:MI:SS.US'),"
                    + "to_char(TIMESTAMP '2023-12-31 23:59:59.999999' + INTERVAL '1 microsecond',"
                    + "'YYYY-MM-DD HH24:MI:SS.US'),"
                    + "to_char(date_trunc('month', TIMESTAMP '2024-02-29 12:34:56'), 'YYYY-MM-DD HH24:MI:SS')")) {
                assertTrue(rows.next());
                assertEquals("2024-02-29 23:59:59.123456", rows.getString(1));
                assertEquals("2024-01-01 00:00:00.000000", rows.getString(2));
                assertEquals("2024-02-01 00:00:00", rows.getString(3));
                assertFalse(rows.next());
            }
        });
    }

    @Test
    void timestampWithZonePreservesInstantAcrossSessionZoneChanges() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".zone_probe(id int, value timestamp with time zone)");
            execute(c, "INSERT INTO " + s + ".zone_probe VALUES (1, TIMESTAMPTZ '2024-02-29 23:30:00.123456+08')");
            java.math.BigDecimal epoch = null;
            for (String zone : List.of("UTC", "Asia/Shanghai")) {
                execute(c, "SET TIME ZONE '" + zone + "'");
                try (var statement = c.createStatement(); var rows = statement.executeQuery(
                    "SELECT to_char(value,'YYYY-MM-DD HH24:MI:SS.US'),extract(epoch FROM value) FROM " + s + ".zone_probe")) {
                    assertTrue(rows.next());
                    assertEquals(zone.equals("UTC") ? "2024-02-29 15:30:00.123456" : "2024-02-29 23:30:00.123456",
                        rows.getString(1));
                    if (epoch == null) epoch = rows.getBigDecimal(2);
                    else assertEquals(0, epoch.compareTo(rows.getBigDecimal(2)));
                    assertFalse(rows.next());
                }
            }
        });
    }

    @Test
    void timestampFormattingPropagatesSqlNullWithoutConvertingItToText() throws Exception {
        inIsolatedSchema((c, s) -> {
            try (var statement = c.createStatement(); var rows = statement.executeQuery(
                "SELECT to_char(NULL::timestamp,'YYYY-MM-DD'),date_trunc('day',NULL::timestamp),"
                    + "extract(epoch FROM NULL::timestamp)")) {
                assertTrue(rows.next());
                assertNull(rows.getString(1));
                assertTrue(rows.wasNull());
                assertNull(rows.getTimestamp(2));
                assertTrue(rows.wasNull());
                assertNull(rows.getBigDecimal(3));
                assertTrue(rows.wasNull());
            }
        });
    }

    @Test
    void productionSequenceBodyRebuildsAscendingCustomStartAndIncrement() throws Exception {
        inIsolatedSchema((c, s) -> assertSequenceBodyRoundtrip(c, s + ".seq_up",
            "START WITH 10 INCREMENT BY 3 MINVALUE 2 MAXVALUE 100", List.of("10", "13", "16")));
    }

    @Test
    void productionSequenceBodyRebuildsDescendingNegativeStartAndBounds() throws Exception {
        inIsolatedSchema((c, s) -> assertSequenceBodyRoundtrip(c, s + ".seq_down",
            "START WITH -2 INCREMENT BY -3 MINVALUE -100 MAXVALUE 0", List.of("-2", "-5", "-8")));
    }

    private static void assertSequenceBodyRoundtrip(Connection c, String sequenceName, String options,
        List<String> expectedValues) throws Exception {
        execute(c, "CREATE SEQUENCE " + sequenceName + " " + options);
        var source = mock(GaussDBDataSource.class);
        var schema = mock(PostgreSchema.class);
        var database = mock(PostgreDatabase.class);
        var context = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreExecutionContext.class);
        var session = mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCSession.class);
        var statement = mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        var result = mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet.class);
        when(schema.getDataSource()).thenReturn(source);
        when(source.getDefaultInstance()).thenReturn(database);
        when(database.isInstanceConnected()).thenReturn(true);
        when(database.getDefaultContext(any(), eq(true))).thenReturn(context);
        when(context.openSession(any(), any(), anyString())).thenReturn(session);
        when(session.prepareStatement(anyString())).thenAnswer(invocation -> {
            var actual = c.prepareStatement(invocation.getArgument(0, String.class));
            actual.setQueryTimeout(15);
            when(statement.executeQuery()).thenAnswer(i -> {
                var rows = actual.executeQuery();
                when(result.next()).thenAnswer(a -> rows.next());
                when(result.getLong(anyString())).thenAnswer(a -> rows.getLong(a.getArgument(0, String.class)));
                when(result.getBoolean(anyString())).thenAnswer(a -> rows.getBoolean(a.getArgument(0, String.class)));
                when(result.wasNull()).thenAnswer(a -> rows.wasNull());
                doAnswer(a -> { rows.close(); return null; }).when(result).close();
                return result;
            });
            doAnswer(i -> { actual.close(); return null; }).when(statement).close();
            return statement;
        });
        var sequence = new org.jkiss.dbeaver.ext.postgresql.model.PostgreSequence(schema) {
            @Override
            public String getFullyQualifiedName(org.jkiss.dbeaver.model.DBPEvaluationContext evaluationContext) {
                return sequenceName;
            }
        };
        var monitor = mock(DBRProgressMonitor.class);
        var info = sequence.getAdditionalInfo(monitor);
        assertTrue(info.getCacheValue() > 0, "Live cache_value must be loaded, not silently lost");
        var ddl = new StringBuilder("CREATE SEQUENCE " + sequenceName);
        sequence.getSequenceBody(monitor, ddl, false);
        verify(result, times(1)).close();
        verify(statement, times(1)).close();
        verify(session, times(1)).close();
        execute(c, "DROP SEQUENCE " + sequenceName);
        execute(c, ddl.toString());
        for (String expected : expectedValues) {
            assertRows(c, "SELECT nextval('" + sequenceName + "')", List.of(List.of(expected)));
        }
        assertRows(c, "SELECT min_value,max_value,increment_by,cache_value FROM " + sequenceName,
            List.of(List.of(Long.toString(info.getMinValue()), Long.toString(info.getMaxValue()),
                Long.toString(info.getIncrementBy()), Long.toString(info.getCacheValue()))));
    }

    @Test
    void productionTableDdlRebuildsUniqueAndOrderedCompositeIndexes() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".indexed_ddl";
            execute(c, "CREATE TABLE " + table + "(id integer,label varchar(30),amount integer) DISTRIBUTE BY HASH(id)");
            execute(c, "CREATE UNIQUE INDEX unique_id_label ON " + table + "(id,label)");
            execute(c, "CREATE INDEX amount_label ON " + table + "(amount DESC,label ASC)");
            String ddl = readProductionTableDdl(c, table);
            assertNotNull(ddl);
            execute(c, "DROP TABLE " + table);
            execute(c, ddl);
            execute(c, "INSERT INTO " + table + " VALUES(1,'a',10),(1,'b',20)");
            assertEquals("23505", assertThrows(java.sql.SQLException.class,
                () -> execute(c, "INSERT INTO " + table + " VALUES(1,'a',30)")).getSQLState());
            var indexedColumns = new java.util.ArrayList<String>();
            try (var rows = c.getMetaData().getIndexInfo(null, s, "indexed_ddl", false, false)) {
                while (rows.next()) {
                    String name = rows.getString("INDEX_NAME");
                    if ("amount_label".equals(name)) {
                        indexedColumns.add(rows.getShort("ORDINAL_POSITION") + ":" + rows.getString("COLUMN_NAME")
                            + ":" + rows.getString("ASC_OR_DESC"));
                    }
                }
            }
            indexedColumns.sort(String::compareTo);
            assertEquals(List.of("1:amount:D", "2:label:A"), indexedColumns);
            assertRows(c, "SELECT id,label,amount FROM " + table + " ORDER BY amount DESC",
                List.of(List.of("1", "b", "20"), List.of("1", "a", "10")));
        });
    }

    @Test
    void productionTableDdlRebuildsHashDefaultsConstraintsAndComments() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".ddl_hash";
            execute(c, "CREATE TABLE " + table + "(id integer PRIMARY KEY, label varchar(80) NOT NULL DEFAULT '中文',"
                + " amount numeric(12,2) CHECK(amount>=0)) DISTRIBUTE BY HASH(id)");
            execute(c, "COMMENT ON TABLE " + table + " IS 'DDL 往返测试'");
            execute(c, "COMMENT ON COLUMN " + table + ".label IS '默认值与长度'");
            String ddl = readProductionTableDdl(c, table);
            assertNotNull(ddl);
            assertTrue(ddl.contains("DISTRIBUTE BY HASH"));
            execute(c, "DROP TABLE " + table);
            execute(c, ddl);
            execute(c, "INSERT INTO " + table + "(id,amount) VALUES(1,12.50)");
            assertRows(c, "SELECT id,label,amount FROM " + table, List.of(List.of("1", "中文", "12.50")));
            assertEquals("23505", assertThrows(java.sql.SQLException.class,
                () -> execute(c, "INSERT INTO " + table + "(id) VALUES(1)")).getSQLState());
            assertEquals("23514", assertThrows(java.sql.SQLException.class,
                () -> execute(c, "INSERT INTO " + table + "(id,amount) VALUES(2,-1)")).getSQLState());
            assertEquals("23502", assertThrows(java.sql.SQLException.class,
                () -> execute(c, "INSERT INTO " + table + "(id,label) VALUES(3,NULL)")).getSQLState());
            assertRows(c, "SELECT obj_description('" + table + "'::regclass),col_description('" + table
                + "'::regclass,2)", List.of(List.of("DDL 往返测试", "默认值与长度")));
        });
    }

    @Test
    void productionTableDdlRebuildsReplicatedQuotedIdentifiers() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".\"Quoted Table\"";
            execute(c, "CREATE TABLE " + table + "(\"标识\" integer, \"Text Value\" varchar(30)) DISTRIBUTE BY REPLICATION");
            String ddl = readProductionTableDdl(c, table);
            assertNotNull(ddl);
            assertTrue(ddl.contains("DISTRIBUTE BY REPLICATION"));
            execute(c, "DROP TABLE " + table);
            execute(c, ddl);
            execute(c, "INSERT INTO " + table + " VALUES(1,'quoted '' value')");
            assertRows(c, "SELECT \"标识\",\"Text Value\" FROM " + table,
                List.of(List.of("1", "quoted ' value")));
            assertRows(c, "SELECT pclocatortype FROM pgxc_class WHERE pcrelid='" + table + "'::regclass",
                List.of(List.of("R")));
        });
    }

    private static String readProductionTableDdl(Connection c, String qualifiedTable) throws Exception {
        long oid;
        try (var query = c.prepareStatement("SELECT ?::regclass::oid")) {
            query.setString(1, qualifiedTable);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                oid = rows.getLong(1);
            }
        }
        var source = mock(GaussDBDataSource.class);
        var database = mock(PostgreDatabase.class);
        var table = mock(PostgreTable.class);
        var context = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreExecutionContext.class);
        var session = mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCSession.class);
        var statement = mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
        var result = mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet.class);
        var infoFactory = org.jkiss.dbeaver.ext.gaussdb.model.GaussDBServerInfo.class.getDeclaredMethod("forTest",
            org.jkiss.dbeaver.ext.gaussdb.model.DBCompatibilityEnum.class, java.util.Set.class, java.util.Set.class);
        infoFactory.setAccessible(true);
        when(source.getServerInfo()).thenReturn((org.jkiss.dbeaver.ext.gaussdb.model.GaussDBServerInfo)
            infoFactory.invoke(null, org.jkiss.dbeaver.ext.gaussdb.model.DBCompatibilityEnum.ORACLE,
                java.util.Set.of(), java.util.Set.of("pg_get_tabledef")));
        when(table.getDataSource()).thenReturn(source);
        when(table.getObjectId()).thenReturn(oid);
        when(source.getDefaultInstance()).thenReturn(database);
        when(database.isInstanceConnected()).thenReturn(true);
        when(database.getDefaultContext(any(), eq(true))).thenReturn(context);
        when(context.openSession(any(), any(), anyString())).thenReturn(session);
        when(session.prepareStatement(anyString())).thenAnswer(invocation -> {
            var actual = c.prepareStatement(invocation.getArgument(0, String.class));
            actual.setQueryTimeout(15);
            doAnswer(i -> { actual.setLong(i.getArgument(0), i.getArgument(1)); return null; })
                .when(statement).setLong(anyInt(), anyLong());
            when(statement.executeQuery()).thenAnswer(i -> {
                var rows = actual.executeQuery();
                when(result.next()).thenAnswer(a -> rows.next());
                when(result.getString(anyInt())).thenAnswer(a -> rows.getString(a.getArgument(0, Integer.class)));
                doAnswer(a -> { rows.close(); return null; }).when(result).close();
                return result;
            });
            doAnswer(i -> { actual.close(); return null; }).when(statement).close();
            return statement;
        });
        var constructor = org.jkiss.dbeaver.ext.gaussdb.model.PostgreServerGaussDB.class.getDeclaredConstructor(
            org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource.class);
        constructor.setAccessible(true);
        var server = constructor.newInstance(source);
        String ddl = server.readTableDDL(mock(DBRProgressMonitor.class), table);
        verify(statement).setLong(1, oid);
        verify(result).close();
        verify(statement).close();
        verify(session).close();
        return ddl;
    }

    @Test
    void compositeHashKeysIncludingNullPreserveRowsAndAggregation() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".hash_rows";
            execute(c, "CREATE TABLE " + table
                + "(id integer, tenant varchar(30), amount numeric(12,2)) DISTRIBUTE BY HASH(id,tenant)");
            try (var insert = c.prepareStatement("INSERT INTO " + table + " VALUES(?,?,?)")) {
                for (int i = 0; i < 40; i++) {
                    if (i % 5 == 0) {
                        insert.setNull(1, java.sql.Types.INTEGER);
                    } else {
                        insert.setInt(1, i);
                    }
                    insert.setString(2, i % 2 == 0 ? "中文租户" : "tenant-'B");
                    insert.setBigDecimal(3, new java.math.BigDecimal("1.25"));
                    assertEquals(1, insert.executeUpdate());
                }
            }
            assertRows(c, "SELECT count(*),count(id),sum(amount) FROM " + table,
                List.of(List.of("40", "32", "50.00")));
            assertRows(c, "SELECT tenant,count(*) FROM " + table + " GROUP BY tenant ORDER BY count(*),tenant COLLATE \"C\"",
                List.of(List.of("tenant-'B", "20"), List.of("中文租户", "20")));
            assertRows(c, "SELECT pclocatortype FROM pgxc_class WHERE pcrelid='" + table + "'::regclass",
                List.of(List.of("H")));
            c.setAutoCommit(false);
            execute(c, "DELETE FROM " + table + " WHERE id IS NULL");
            assertEquals(32, count(c, table));
            c.rollback();
            assertObserverCount(table, 40);
        });
    }

    @Test
    void replicatedLookupJoinDoesNotMultiplyRowsAcrossDataNodes() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".lookup(id integer PRIMARY KEY,label varchar(20)) DISTRIBUTE BY REPLICATION");
            execute(c, "CREATE TABLE " + s + ".facts(id integer,lookup_id integer) DISTRIBUTE BY HASH(id)");
            execute(c, "INSERT INTO " + s + ".lookup VALUES(1,'one'),(2,'two')");
            execute(c, "INSERT INTO " + s + ".facts SELECT i,1+i%2 FROM generate_series(1,100) i");
            assertRows(c, "SELECT l.label,count(*) FROM " + s + ".facts f JOIN " + s
                + ".lookup l ON f.lookup_id=l.id GROUP BY l.label ORDER BY l.label",
                List.of(List.of("one", "50"), List.of("two", "50")));
            assertRows(c, "SELECT pclocatortype FROM pgxc_class WHERE pcrelid='" + s + ".lookup'::regclass",
                List.of(List.of("R")));
            assertObserverCount(s + ".lookup", 2);
        });
    }

    @Test
    void roundRobinDistributionReturnsEveryRowExactlyOnce() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".round_rows";
            try {
                execute(c, "CREATE TABLE " + table + "(id integer,label varchar(20)) DISTRIBUTE BY ROUNDROBIN");
            } catch (java.sql.SQLException error) {
                if ("0A000".equals(error.getSQLState())) {
                    assumeTrue(false, "Server rejects ROUNDROBIN distribution (0A000); not a passing scenario");
                }
                throw error;
            }
            execute(c, "INSERT INTO " + table + " SELECT i,'row-'||i FROM generate_series(1,101) i");
            assertRows(c, "SELECT count(*),count(DISTINCT id),min(id),max(id),sum(id) FROM " + table,
                List.of(List.of("101", "101", "1", "101", "5151")));
            assertRows(c, "SELECT pclocatortype FROM pgxc_class WHERE pcrelid='" + table + "'::regclass",
                List.of(List.of("N")));
        });
    }

    @Test
    void executeDirectOnActualTableNodesReconstructsTheCoordinatorRows() throws Exception {
        inIsolatedSchema((c, s) -> {
            assumeTrue(canExecuteDirect(c), "EXECUTE DIRECT requires a separately authorized monitor/system admin run");
            String table = s + ".direct_rows";
            execute(c, "CREATE TABLE " + table + "(id integer) DISTRIBUTE BY HASH(id)");
            execute(c, "INSERT INTO " + table + " SELECT i FROM generate_series(1,100) i");
            var nodes = new java.util.ArrayList<String>();
            try (var query = c.createStatement(); var rows = query.executeQuery(
                "SELECT n.node_name FROM pgxc_node n JOIN pgxc_class x ON n.oid=ANY(x.nodeoids) "
                    + "WHERE x.pcrelid='" + table + "'::regclass AND n.node_type='D' ORDER BY n.node_name")) {
                while (rows.next()) {
                    nodes.add(rows.getString(1));
                }
            }
            assertFalse(nodes.isEmpty(), "No data nodes found for the test table");
            var actual = new java.util.ArrayList<Integer>();
            for (String node : nodes) {
                String command = "EXECUTE DIRECT ON (\"" + node.replace("\"", "\"\"")
                    + "\") 'SELECT id FROM " + table + "'";
                try (var query = c.createStatement()) {
                    query.setQueryTimeout(15);
                    try (var rows = query.executeQuery(command)) {
                        while (rows.next()) {
                            actual.add(rows.getInt(1));
                        }
                    }
                }
            }
            actual.sort(Integer::compareTo);
            assertEquals(java.util.stream.IntStream.rangeClosed(1, 100).boxed().toList(), actual);
            assertEquals(100, count(c, table));
        });
    }

    @Test
    void ordinaryAccountCannotBypassCoordinatorWithExecuteDirect() throws Exception {
        inIsolatedSchema((c, s) -> {
            assumeTrue(!canExecuteDirect(c), "Permission rejection requires an ordinary account run");
            String node;
            try (var query = c.createStatement(); var rows = query.executeQuery(
                "SELECT node_name FROM pgxc_node WHERE node_type='D' ORDER BY node_name LIMIT 1")) {
                assertTrue(rows.next());
                node = rows.getString(1);
            }
            var error = assertThrows(java.sql.SQLException.class, () -> execute(c,
                "EXECUTE DIRECT ON (\"" + node.replace("\"", "\"\"") + "\") 'SELECT 1'"));
            assertEquals("42501", error.getSQLState());
            assertConnectionUsable(c);
        });
    }

    private static boolean canExecuteDirect(Connection c) throws Exception {
        try (var query = c.createStatement(); var rows = query.executeQuery(
            "SELECT rolsystemadmin OR rolmonitoradmin FROM pg_roles WHERE rolname=current_user")) {
            assertTrue(rows.next());
            return rows.getBoolean(1);
        }
    }

    @Test
    void productionSchemaSearchExecutesNamespaceIdBinding() throws Exception {
        inIsolatedSchema((c, s) -> {
            var rows = searchObjects(c, s, s, true, 10, false,
                org.jkiss.dbeaver.model.impl.struct.RelationalObjectType.TYPE_SCHEMA);
            assertEquals(List.of(s), rows.stream().map(r -> r.getName()).toList());
            assertEquals(PostgreSchema.class, rows.get(0).getObjectClass());
            assertTrue(searchObjects(c, s, "missing_schema", true, 10, false,
                org.jkiss.dbeaver.model.impl.struct.RelationalObjectType.TYPE_SCHEMA).isEmpty());
        });
    }

    @Test
    void productionColumnSearchExcludesSystemDroppedAndIndexAttributes() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer PRIMARY KEY, search_col text, obsolete integer)");
            execute(c, "ALTER TABLE " + s + ".t DROP COLUMN obsolete");
            execute(c, "COMMENT ON COLUMN " + s + ".t.search_col IS 'column_comment_token'");
            var type = org.jkiss.dbeaver.model.impl.struct.RelationalObjectType.TYPE_TABLE_COLUMN;
            var rows = searchObjects(c, s, "%", false, 100, false, type);
            assertEquals(List.of("id", "search_col"), rows.stream().map(r -> r.getName()).sorted().toList());
            assertEquals(List.of("search_col"), searchObjects(c, s, "%column_comment_token%", false, 100, true, type)
                .stream().map(r -> r.getName()).toList());
        });
    }

    @Test
    void productionConstraintAndCompositeTypeSearchFindNamedCatalogObjects() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer CONSTRAINT search_pk PRIMARY KEY, amount integer "
                + "CONSTRAINT search_positive CHECK(amount>0))");
            var constraints = searchObjects(c, s, "search%", true, 10, false,
                org.jkiss.dbeaver.model.impl.struct.RelationalObjectType.TYPE_CONSTRAINT);
            assertEquals(List.of("search_pk", "search_positive"), constraints.stream().map(r -> r.getName()).sorted().toList());
            execute(c, "CREATE TYPE " + s + ".search_type AS (label text)");
            var types = searchObjects(c, s, "%search_type%", false, 10, false,
                org.jkiss.dbeaver.model.impl.struct.RelationalObjectType.TYPE_DATA_TYPE);
            assertEquals(List.of("search_type"), types.stream().map(r -> r.getName()).toList());
        });
    }

    @Test
    void productionSearchKeepsSameNamedTablesInTheirRequestedSchema() throws Exception {
        inIsolatedSchema((c, s) -> inIsolatedSchema((other, otherSchema) -> {
            execute(c, "CREATE TABLE " + s + ".same_name(id integer)");
            execute(other, "CREATE TABLE " + otherSchema + ".same_name(id integer)");
            assertEquals(1, searchTables(c, s, "same_name", true, 10, false).size());
            assertEquals(1, searchTables(c, otherSchema, "same_name", true, 10, false).size());
            execute(c, "DROP TABLE " + s + ".same_name");
            assertTrue(searchTables(c, s, "same_name", true, 10, false).isEmpty());
            assertEquals(1, searchTables(c, otherSchema, "same_name", true, 10, false).size());
        }));
    }

    @Test
    void productionTableSearchUsesRealCatalogCaseCommentsAndLimit() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".\"SearchAlpha\"(id integer)");
            execute(c, "CREATE VIEW " + s + ".searchbeta AS SELECT id FROM " + s + ".\"SearchAlpha\"");
            execute(c, "COMMENT ON VIEW " + s + ".searchbeta IS 'OnlyCommentToken'");
            var matches = searchTables(c, s, "search%", false, 10, false);
            assertEquals(List.of("SearchAlpha", "searchbeta"), matches.stream().map(r -> r.getName()).toList());
            assertEquals("PostgreTable", matches.get(0).getObjectClass().getSimpleName());
            assertEquals("PostgreView", matches.get(1).getObjectClass().getSimpleName());
            assertEquals(List.of("searchbeta"), searchTables(c, s, "search%", true, 10, false)
                .stream().map(r -> r.getName()).toList());
            assertEquals(1, searchTables(c, s, "search%", false, 1, false).size());
            assertTrue(searchTables(c, s, "%OnlyCommentToken%", false, 10, false).isEmpty());
            assertEquals(List.of("searchbeta"), searchTables(c, s, "%OnlyCommentToken%", false, 10, true)
                .stream().map(r -> r.getName()).toList());
            assertTrue(searchTables(c, s, "x'; DROP TABLE anything;--", false, 10, false).isEmpty());
            assertEquals(2, searchTables(c, s, "search%", false, 10, false).size());
        });
    }

    @Test
    void productionRoutineSearchUsesCatalogCaseCommentsAndLimit() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE FUNCTION " + s + ".\"SearchFunction\"() RETURN integer AS BEGIN RETURN 7; END;");
            execute(c, "CREATE PROCEDURE " + s + ".search_procedure() AS BEGIN NULL; END;");
            execute(c, "COMMENT ON FUNCTION " + s + ".\"SearchFunction\"() IS 'routine_comment_token'");
            var type = org.jkiss.dbeaver.model.impl.struct.RelationalObjectType.TYPE_PROCEDURE;
            var matches = searchObjects(c, s, "search%", false, 10, false, type);
            assertEquals(List.of("SearchFunction", "search_procedure"), matches.stream().map(r -> r.getName()).toList());
            assertTrue(matches.stream().allMatch(r -> r.getObjectClass()
                == org.jkiss.dbeaver.ext.postgresql.model.PostgreProcedure.class));
            assertEquals(List.of("search_procedure"), searchObjects(c, s, "search%", true, 10, false, type)
                .stream().map(r -> r.getName()).toList());
            assertEquals(1, searchObjects(c, s, "search%", false, 1, false, type).size());
            assertTrue(searchObjects(c, s, "%routine_comment_token%", false, 10, false, type).isEmpty());
            assertEquals(List.of("SearchFunction"), searchObjects(c, s, "%routine_comment_token%", false, 10, true, type)
                .stream().map(r -> r.getName()).toList());
        });
    }

    @Test
    void productionRoutineSearchKeepsSameNamedFunctionsIsolatedAfterDrop() throws Exception {
        inIsolatedSchema((c, s) -> inIsolatedSchema((other, otherSchema) -> {
            execute(c, "CREATE FUNCTION " + s + ".same_routine() RETURN integer AS BEGIN RETURN 1; END;");
            execute(other, "CREATE FUNCTION " + otherSchema + ".same_routine() RETURN integer AS BEGIN RETURN 2; END;");
            var type = org.jkiss.dbeaver.model.impl.struct.RelationalObjectType.TYPE_PROCEDURE;
            assertEquals(1, searchObjects(c, s, "same_routine", true, 10, false, type).size());
            assertEquals(1, searchObjects(c, otherSchema, "same_routine", true, 10, false, type).size());
            execute(c, "DROP FUNCTION " + s + ".same_routine()");
            assertTrue(searchObjects(c, s, "same_routine", true, 10, false, type).isEmpty());
            assertEquals(1, searchObjects(c, otherSchema, "same_routine", true, 10, false, type).size());
        }));
    }

    @Test
    void productionRoutineDefinitionSearchFindsBodyWithoutNameOrCommentMatch() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE FUNCTION " + s + ".body_probe() RETURN text AS BEGIN RETURN 'UniqueBodyToken'; END;");
            var type = org.jkiss.dbeaver.model.impl.struct.RelationalObjectType.TYPE_PROCEDURE;
            assertTrue(searchObjects(c, s, "%UniqueBodyToken%", true, 10, false, type).isEmpty());
            assertEquals(List.of("body_probe"), searchObjects(c, s, "%UniqueBodyToken%", true, 10, false, type, true)
                .stream().map(r -> r.getName()).toList());
            assertTrue(searchObjects(c, s, "%uniquebodytoken%", true, 10, false, type, true).isEmpty());
            assertEquals(1, searchObjects(c, s, "%uniquebodytoken%", false, 10, false, type, true).size());
        });
    }

    @Test
    void productionRoutineSearchPreservesInputAndOutputSignatureTypes() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE PROCEDURE " + s
                + ".signature_probe(IN p_id numeric, OUT p_value varchar) AS BEGIN p_value := p_id::text; END;");
            var matches = searchObjects(c, s, "signature_probe", true, 10, false,
                org.jkiss.dbeaver.model.impl.struct.RelationalObjectType.TYPE_PROCEDURE);
            assertEquals(1, matches.size());
            assertEquals(s + ".signature_probe(in numeric, out varchar)", matches.get(0).getFullyQualifiedName(
                org.jkiss.dbeaver.model.DBPEvaluationContext.DDL));
        });
    }

    /** Production search algorithm and SQL, with a thin mock JDBC interface bridge to the real driver. */
    private static List<org.jkiss.dbeaver.model.struct.DBSObjectReference> searchTables(
        Connection c, String schemaName, String mask, boolean caseSensitive, int limit, boolean comments
    ) throws Exception {
        return searchObjects(c, schemaName, mask, caseSensitive, limit, comments,
            org.jkiss.dbeaver.model.impl.struct.RelationalObjectType.TYPE_TABLE);
    }

    private static List<org.jkiss.dbeaver.model.struct.DBSObjectReference> searchObjects(
        Connection c, String schemaName, String mask, boolean caseSensitive, int limit, boolean comments,
        org.jkiss.dbeaver.model.struct.DBSObjectType objectType
    ) throws Exception {
        return searchObjects(c, schemaName, mask, caseSensitive, limit, comments, objectType, false);
    }

    private static List<org.jkiss.dbeaver.model.struct.DBSObjectReference> searchObjects(
        Connection c, String schemaName, String mask, boolean caseSensitive, int limit, boolean comments,
        org.jkiss.dbeaver.model.struct.DBSObjectType objectType, boolean definitions
    ) throws Exception {
        long schemaId;
        try (var lookup = c.prepareStatement("SELECT oid FROM pg_namespace WHERE nspname=?")) {
            lookup.setString(1, schemaName);
            try (var rows = lookup.executeQuery()) {
                assertTrue(rows.next());
                schemaId = rows.getLong(1);
            }
        }
        var monitor = mock(DBRProgressMonitor.class);
        var source = mock(GaussDBDataSource.class);
        var sourceContainer = mock(org.jkiss.dbeaver.model.DBPDataSourceContainer.class);
        when(source.getContainer()).thenReturn(sourceContainer);
        when(sourceContainer.getId()).thenReturn("historical-live-search");
        var database = mock(PostgreDatabase.class);
        var schema = mock(PostgreSchema.class);
        var constructor = org.jkiss.dbeaver.ext.gaussdb.model.PostgreServerGaussDB.class
            .getDeclaredConstructor(org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource.class);
        constructor.setAccessible(true);
        when(source.getServerType()).thenReturn(
            (org.jkiss.dbeaver.ext.gaussdb.model.PostgreServerGaussDB) constructor.newInstance(source));
        when(source.getSQLDialect()).thenReturn(new GaussDBDialect());
        when(database.getDataSource()).thenReturn(source);
        when(schema.getDataSource()).thenReturn(source);
        when(schema.getName()).thenReturn(schemaName);
        when(schema.getDatabase()).thenReturn(database);
        when(database.getDataType(eq(monitor), anyLong())).thenAnswer(invocation -> {
            try (var query = c.prepareStatement("SELECT typname FROM pg_catalog.pg_type WHERE oid=?")) {
                query.setLong(1, invocation.getArgument(1, Long.class));
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        return null;
                    }
                    var type = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreDataType.class);
                    var catalog = mock(PostgreSchema.class);
                    when(catalog.isCatalogSchema()).thenReturn(true);
                    when(type.getParentObject()).thenReturn(catalog);
                    when(type.getName()).thenReturn(rows.getString(1));
                    return type;
                }
            }
        });
        when(schema.getObjectId()).thenReturn(schemaId);
        when(database.getSchema(monitor, schemaId)).thenReturn(schema);
        var context = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreExecutionContext.class);
        when(context.getDataSource()).thenReturn(source);
        when(context.getDefaultCatalog()).thenReturn(database);
        var session = mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCSession.class);
        when(session.getDataSource()).thenReturn(source);
        when(session.getProgressMonitor()).thenReturn(monitor);
        when(context.openSession(eq(monitor), any(), anyString())).thenReturn(session);
        when(session.prepareStatement(anyString())).thenAnswer(invocation -> {
            var actual = c.prepareStatement(invocation.getArgument(0, String.class));
            actual.setQueryTimeout(15);
            var statement = mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement.class);
            doAnswer(i -> { actual.setString(i.getArgument(0), i.getArgument(1)); return null; })
                .when(statement).setString(anyInt(), anyString());
            doAnswer(i -> { actual.setLong(i.getArgument(0), i.getArgument(1)); return null; })
                .when(statement).setLong(anyInt(), anyLong());
            when(statement.executeQuery()).thenAnswer(i -> {
                var rows = actual.executeQuery();
                var result = mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet.class);
                when(result.getSession()).thenReturn(session);
                when(result.next()).thenAnswer(a -> rows.next());
                when(result.getString(anyString())).thenAnswer(a -> rows.getString(a.getArgument(0, String.class)));
                when(result.getLong(anyString())).thenAnswer(a -> rows.getLong(a.getArgument(0, String.class)));
                when(result.getObject(anyString())).thenAnswer(a -> rows.getObject(a.getArgument(0, String.class)));
                when(result.getArray(anyString())).thenAnswer(a -> rows.getArray(a.getArgument(0, String.class)));
                when(result.getBoolean(anyString())).thenAnswer(a -> rows.getBoolean(a.getArgument(0, String.class)));
                when(result.wasNull()).thenAnswer(a -> rows.wasNull());
                doAnswer(a -> { rows.close(); return null; }).when(result).close();
                return result;
            });
            doAnswer(i -> { actual.close(); return null; }).when(statement).close();
            return statement;
        });
        var params = new org.jkiss.dbeaver.model.struct.DBSStructureAssistant.ObjectsSearchParams(
            new org.jkiss.dbeaver.model.struct.DBSObjectType[] {objectType}, mask);
        params.setParentObject(schema);
        params.setCaseSensitive(caseSensitive);
        params.setMaxResults(limit);
        params.setSearchInComments(comments);
        params.setSearchInDefinitions(definitions);
        var result = new org.jkiss.dbeaver.ext.postgresql.model.PostgreStructureAssistant(source)
            .findObjectsByMask(monitor, context, params);
        verify(session).close();
        return result;
    }

    @Test
    void productionRoleModelReadsLiveCatalogWithServerVisibilityRules() throws Exception {
        String grantee = System.getenv("GAUSSDB_HISTORY_GRANTEE_ROLE");
        assumeTrue(grantee != null, "Dedicated NOLOGIN role not configured; role catalog test not executed");
        assertTrue(grantee.matches("dbv_hist_grantee_[a-z0-9_]+"));
        inIsolatedSchema((connection, schema) -> {
            var database = mock(PostgreDatabase.class);
            var names = new java.util.HashSet<String>();
            boolean currentRoleFound = false;
            boolean canEnumerateOtherRoles = false;
            try (var statement = connection.prepareStatement(
                "SELECT r.*,rolname=current_user AS is_current,shobj_description(r.oid,'pg_authid') AS description "
                    + "FROM pg_roles r WHERE rolname=current_user OR rolname=?")) {
                statement.setString(1, grantee);
                statement.setQueryTimeout(15);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        var role = new org.jkiss.dbeaver.ext.postgresql.model.PostgreRole(database, rows);
                        names.add(role.getName());
                        if (rows.getBoolean("is_current")) {
                            currentRoleFound = true;
                            canEnumerateOtherRoles = rows.getBoolean("rolcreaterole") || rows.getBoolean("rolsystemadmin");
                        }
                        assertEquals(rows.getLong("oid"), role.getObjectId());
                        assertTrue(role.isPersisted());
                        assertEquals(rows.getBoolean("rolsuper"), role.isSuperUser());
                        assertEquals(rows.getBoolean("rolinherit"), role.isInherit());
                        assertEquals(rows.getBoolean("rolcreaterole"), role.isCreateRole());
                        assertEquals(rows.getBoolean("rolcreatedb"), role.isCreateDatabase());
                        assertEquals(rows.getBoolean("rolreplication"), role.isReplication());
                        assertEquals(rows.getInt("rolconnlimit"), role.getConnLimit());
                        assertEquals(!grantee.equals(role.getName()), role.isCanLogin());
                        assertEquals(role.isCanLogin(), role.isUser());
                        var expiry = rows.getTimestamp("rolvaliduntil");
                        assertEquals(expiry == null ? null : expiry.toLocalDateTime(), role.getValidUntil());
                    }
                }
            }
            assertTrue(currentRoleFound);
            // GaussDB pg_roles filters other roles unless CREATEROLE or SYSADMIN is set.
            assertEquals(canEnumerateOtherRoles ? 2 : 1, names.size());
            assertEquals(canEnumerateOtherRoles, names.contains(grantee));
        });
    }

    @Test
    void productionDefaultPrivilegesApplyOnlyToFutureObjectsAndRevocationIsNotRetroactive() throws Exception {
        String grantee = System.getenv("GAUSSDB_HISTORY_GRANTEE_ROLE");
        assumeTrue(grantee != null, "Dedicated NOLOGIN grantee role not configured; default privilege test not executed");
        assertTrue(grantee.matches("dbv_hist_grantee_[a-z0-9_=\" ]+"), "Only a dedicated test role is allowed");
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".before_grant(id integer)");
            var source = mock(GaussDBDataSource.class);
            when(source.getSQLDialect()).thenReturn(new GaussDBDialect());
            when(source.getSupportedPrivilegeTypes()).thenReturn(new PostgrePrivilegeType[] {
                PostgrePrivilegeType.SELECT, PostgrePrivilegeType.INSERT
            });
            var schema = mock(PostgreSchema.class);
            when(schema.getName()).thenReturn(s);
            when(schema.getDataSource()).thenReturn(source);
            var permission = mock(PostgreDefaultPrivilege.class);
            when(permission.getOwner()).thenReturn(schema);
            when(permission.getUnderKind()).thenReturn(PostgrePrivilegeGrant.Kind.TABLE);
            var reference = new PostgreRoleReference(mock(PostgreDatabase.class), grantee, null);
            when(permission.getGrantee()).thenReturn(reference);
            var targetType = mock(PostgreTable.class);
            for (boolean grant : new boolean[] {true, false}) {
                var command = new PostgreCommandGrantPrivilege(schema, grant, targetType, permission,
                    new PostgrePrivilegeType[] {PostgrePrivilegeType.SELECT});
                for (var action : command.getPersistActions(mock(DBRProgressMonitor.class),
                    mock(DBCExecutionContext.class), java.util.Map.of())) {
                    execute(c, action.getScript());
                }
                execute(c, "CREATE TABLE " + s + (grant ? ".after_grant" : ".after_revoke") + "(id integer)");
                // A schema-scoped default must not modify old tables, and its removal
                // must not revoke permissions already materialized on existing tables.
                List<String> tables = grant ? List.of("before_grant", "after_grant")
                    : List.of("before_grant", "after_grant", "after_revoke");
                try (var statement = c.prepareStatement("SELECT has_table_privilege(?,?,?)")) {
                    for (String name : tables) {
                        for (String privilege : List.of("SELECT", "INSERT", "SELECT WITH GRANT OPTION")) {
                            statement.setString(1, grantee);
                            statement.setString(2, s + "." + name);
                            statement.setString(3, privilege);
                            try (var result = statement.executeQuery()) {
                                assertTrue(result.next());
                                assertEquals(name.equals("after_grant") && privilege.equals("SELECT"),
                                    result.getBoolean(1), name + ":" + privilege + " grant=" + grant);
                            }
                        }
                    }
                }
            }
        });
    }

    @Test
    void productionGrantCommandsPreservePerPrivilegeGrantabilityInDatabase() throws Exception {
        String grantee = System.getenv("GAUSSDB_HISTORY_GRANTEE_ROLE");
        assumeTrue(grantee != null, "Dedicated NOLOGIN grantee role not configured; permission test not executed");
        assertTrue(grantee.matches("dbv_hist_grantee_[a-z0-9_=\" ]+"), "Only a dedicated test role is allowed");
        inIsolatedSchema((c, s) -> {
            // GaussDB filters pg_roles for ordinary users. The administrator must provision
            // the NOLOGIN fixture; a missing role will fail the actual GRANT below.
            execute(c, "CREATE TABLE " + s + ".t(id integer, restricted_value text)");
            var source = mock(org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDataSource.class);
            when(source.getSQLDialect()).thenReturn(new org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDialect());
            when(source.getSupportedPrivilegeTypes()).thenReturn(new org.jkiss.dbeaver.ext.postgresql.model.PostgrePrivilegeType[] {
                org.jkiss.dbeaver.ext.postgresql.model.PostgrePrivilegeType.SELECT,
                org.jkiss.dbeaver.ext.postgresql.model.PostgrePrivilegeType.INSERT
            });
            var role = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreRole.class);
            when(role.getDataSource()).thenReturn(source);
            when(role.getName()).thenReturn(grantee);
            var table = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreTable.class);
            var database = mock(PostgreDatabase.class);
            var server = new org.jkiss.dbeaver.ext.gaussdb.model.PostgreServerGaussDB(source) {};
            when(source.getServerType()).thenReturn(server);
            when(database.getDataSource()).thenReturn(source);
            when(database.getName()).thenReturn(c.getCatalog());
            var schema = mock(PostgreSchema.class);
            when(schema.getName()).thenReturn(s);
            when(table.getDatabase()).thenReturn(database);
            when(table.getDataSource()).thenReturn(source);
            when(table.getSchema()).thenReturn(schema);
            when(table.getName()).thenReturn("t");
            when(table.getFullyQualifiedName(org.jkiss.dbeaver.model.DBPEvaluationContext.DDL)).thenReturn(s + ".t");
            var privilege = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreRolePrivilege.class);
            when(privilege.getFullObjectName()).thenReturn(s + ".t");
            when(privilege.getKind()).thenReturn(org.jkiss.dbeaver.ext.postgresql.model.PostgrePrivilegeGrant.Kind.TABLE);
            when(privilege.getPermission(org.jkiss.dbeaver.ext.postgresql.model.PostgrePrivilegeType.SELECT))
                .thenReturn(org.jkiss.dbeaver.ext.postgresql.model.PostgrePrivilege.WITH_GRANT_OPTION);
            for (boolean grant : new boolean[] {true, false}) {
                var command = new org.jkiss.dbeaver.ext.postgresql.edit.PostgreCommandGrantPrivilege(
                    role, grant, table, privilege, null);
                var actions = command.getPersistActions(mock(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor.class),
                    mock(org.jkiss.dbeaver.model.exec.DBCExecutionContext.class), java.util.Map.of());
                for (var action : actions) {
                    execute(c, action.getScript());
                }
                try (var aclStatement = c.prepareStatement("SELECT relacl FROM pg_class WHERE oid=?::regclass")) {
                    aclStatement.setString(1, s + ".t");
                    try (var result = aclStatement.executeQuery()) {
                        assertTrue(result.next());
                        var acl = result.getArray(1);
                        assertNotNull(acl);
                        try {
                            var parsed = org.jkiss.dbeaver.ext.postgresql.PostgreUtils.extractPermissionsFromACL(
                                mock(DBRProgressMonitor.class), table, acl, false);
                            var matches = parsed.stream()
                                .map(p -> (org.jkiss.dbeaver.ext.postgresql.model.PostgreObjectPrivilege) p)
                                .filter(p -> p.getGrantee().getRoleName().equals(grantee)).toList();
                            assertEquals(grant ? 1 : 0, matches.size(), "ACL grantee after grant=" + grant);
                            if (grant) {
                                assertEquals(3, matches.get(0).getPermission(PostgrePrivilegeType.SELECT));
                                assertEquals(1, matches.get(0).getPermission(PostgrePrivilegeType.INSERT));
                                assertEquals(0, matches.get(0).getPermission(PostgrePrivilegeType.UPDATE));
                            }
                        } finally {
                            acl.free();
                        }
                    }
                }
                try (var statement = c.prepareStatement("SELECT has_table_privilege(?,?,?)")) {
                    for (String permission : List.of("SELECT", "INSERT", "SELECT WITH GRANT OPTION", "INSERT WITH GRANT OPTION", "UPDATE")) {
                        statement.setString(1, grantee);
                        statement.setString(2, s + ".t");
                        statement.setString(3, permission);
                        try (var result = statement.executeQuery()) {
                            assertTrue(result.next());
                            assertEquals(grant && !permission.equals("INSERT WITH GRANT OPTION") && !permission.equals("UPDATE"),
                                result.getBoolean(1), permission + " after grant=" + grant);
                        }
                    }
                }
            }
            when(source.getSupportedPrivilegeTypes()).thenReturn(new org.jkiss.dbeaver.ext.postgresql.model.PostgrePrivilegeType[] {
                org.jkiss.dbeaver.ext.postgresql.model.PostgrePrivilegeType.SELECT,
                org.jkiss.dbeaver.ext.postgresql.model.PostgrePrivilegeType.INSERT,
                org.jkiss.dbeaver.ext.postgresql.model.PostgrePrivilegeType.UPDATE
            });
            var column = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreTableColumn.class);
            when(column.getDataSource()).thenReturn(source);
            when(column.getName()).thenReturn("id");
            when(column.getTable()).thenReturn(table);
            var columnPrivilege = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreObjectPrivilege.class);
            var reference = new org.jkiss.dbeaver.ext.postgresql.model.PostgreRoleReference(
                mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreDatabase.class), grantee, null);
            when(columnPrivilege.getGrantee()).thenReturn(reference);
            for (boolean grant : new boolean[] {true, false}) {
                var command = new org.jkiss.dbeaver.ext.postgresql.edit.PostgreCommandGrantPrivilege(
                    column, grant, column, columnPrivilege, new org.jkiss.dbeaver.ext.postgresql.model.PostgrePrivilegeType[] {
                        org.jkiss.dbeaver.ext.postgresql.model.PostgrePrivilegeType.SELECT,
                        org.jkiss.dbeaver.ext.postgresql.model.PostgrePrivilegeType.UPDATE
                    });
                for (var action : command.getPersistActions(mock(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor.class),
                    mock(org.jkiss.dbeaver.model.exec.DBCExecutionContext.class), java.util.Map.of())) {
                    execute(c, action.getScript());
                }
                try (var statement = c.prepareStatement("SELECT has_column_privilege(?,?,?,?)")) {
                    for (String columnName : List.of("id", "restricted_value")) {
                        for (String permission : List.of("SELECT", "UPDATE", "INSERT")) {
                            statement.setString(1, grantee);
                            statement.setString(2, s + ".t");
                            statement.setString(3, columnName);
                            statement.setString(4, permission);
                            try (var result = statement.executeQuery()) {
                                assertTrue(result.next());
                                assertEquals(grant && columnName.equals("id") && !permission.equals("INSERT"),
                                    result.getBoolean(1), columnName + ":" + permission + " grant=" + grant);
                            }
                        }
                    }
                }
            }
        });
    }

    @Test
    void csvImporterValuesSurviveVendorJdbcInsertionAndCommit() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer PRIMARY KEY, value text)");
            c.setAutoCommit(false);
            importCsvToJdbc(c, s + ".t", "id,value\n1,\"中文,引号\"\"值\"\n2,\"line1\nline2\"\n3\n");
            assertObserverCount(s + ".t", 0);
            c.commit();
            assertObserverCount(s + ".t", 3);
            assertRows(c, "SELECT id,value FROM " + s + ".t ORDER BY id",
                java.util.Arrays.asList(List.of("1", "中文,引号\"值"), List.of("2", "line1\nline2"),
                    java.util.Arrays.asList("3", null)));
        });
    }

    @Test
    void malformedCsvAllowsExplicitRollbackOfAlreadyInsertedRows() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer PRIMARY KEY, value text)");
            c.setAutoCommit(false);
            var error = assertThrows(org.jkiss.dbeaver.DBException.class,
                () -> importCsvToJdbc(c, s + ".t", "id,value\n1,valid\n2,\"unfinished\n"));
            assertInstanceOf(java.io.IOException.class, error.getCause());
            assertEquals(1, count(c, s + ".t"));
            assertObserverCount(s + ".t", 0);
            c.rollback();
            assertObserverCount(s + ".t", 0);
        });
    }

    @Test
    void duplicateCsvKeyPropagatesSqlstateAndAllowsExplicitRollback() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer PRIMARY KEY, value text)");
            c.setAutoCommit(false);
            var error = assertThrows(org.jkiss.dbeaver.model.exec.DBCException.class,
                () -> importCsvToJdbc(c, s + ".t", "id,value\n1,first\n1,duplicate\n"));
            var sqlError = assertInstanceOf(java.sql.SQLException.class, error.getCause());
            assertEquals("23505", sqlError.getSQLState());
            c.rollback();
            assertObserverCount(s + ".t", 0);
            assertEquals(0, count(c, s + ".t"));
        });
    }

    /** Real importer + JDBC fixture sink, not the production database-transfer consumer or wizard. */
    private static void importCsvToJdbc(Connection connection, String table, String csv) throws Exception {
        // The headless platform lazily initializes its query manager on first access.
        assertNotNull(org.jkiss.dbeaver.runtime.DBWorkbench.getPlatform());
        var source = mock(org.jkiss.dbeaver.tools.transfer.stream.StreamEntityMapping.class);
        when(source.getStreamColumns()).thenReturn(List.of(
            new org.jkiss.dbeaver.tools.transfer.stream.StreamDataImporterColumnInfo(
                source, 0, "id", "VARCHAR", 100, org.jkiss.dbeaver.model.DBPDataKind.STRING),
            new org.jkiss.dbeaver.tools.transfer.stream.StreamDataImporterColumnInfo(
                source, 1, "value", "VARCHAR", 1000, org.jkiss.dbeaver.model.DBPDataKind.STRING)));
        var site = mock(org.jkiss.dbeaver.tools.transfer.stream.IStreamDataImporterSite.class);
        when(site.getSourceObject()).thenReturn(source);
        when(site.getSettings()).thenReturn(mock(org.jkiss.dbeaver.tools.transfer.stream.StreamProducerSettings.class));
        when(site.getProcessorProperties()).thenReturn(java.util.Map.of(
            "header", "top", "delimiter", ",", "quoteChar", "\"", "encoding", "UTF-8", "trimWhitespaces", true));
        var importer = new org.jkiss.dbeaver.tools.transfer.stream.importer.DataImporterCSV();
        importer.init(site);
        var monitor = mock(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor.class);
        var dataSource = mock(org.jkiss.dbeaver.model.DBPDataSource.class, RETURNS_DEEP_STUBS);
        var sink = mock(org.jkiss.dbeaver.tools.transfer.IDataTransferConsumer.class);
        try (var insert = connection.prepareStatement("INSERT INTO " + table + " VALUES(?::integer,?)")) {
            insert.setQueryTimeout(15);
            doAnswer(invocation -> {
                org.jkiss.dbeaver.model.exec.DBCResultSet result = invocation.getArgument(1);
                try {
                    insert.setString(1, (String) result.getAttributeValue(0));
                    insert.setString(2, (String) result.getAttributeValue(1));
                    insert.executeUpdate();
                } catch (java.sql.SQLException error) {
                    throw new org.jkiss.dbeaver.model.exec.DBCException("JDBC fixture sink failed", error);
                }
                return null;
            }).when(sink).fetchRow(any(), any());
            try (var input = new java.io.ByteArrayInputStream(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                importer.runImport(monitor, dataSource, input, sink);
            } catch (Exception | Error error) {
                try {
                    verify(sink).fetchEnd(any(), any());
                    verify(sink).close();
                } catch (AssertionError cleanupFailure) {
                    cleanupFailure.addSuppressed(error);
                    throw cleanupFailure;
                }
                throw error;
            }
            verify(sink).fetchEnd(any(), any());
            verify(sink).close();
        }
    }

    @Test
    void serverWarningIsAvailableAndCanBeClearedWithoutClosingConnection() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE PROCEDURE " + s + ".warn_probe() AS BEGIN RAISE WARNING 'dbv_history_warning'; END;");
            try (var statement = c.createStatement()) {
                statement.setQueryTimeout(15);
                statement.execute("CALL " + s + ".warn_probe()");
                boolean found = false;
                for (var warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning()) {
                    if (warning.getMessage().contains("dbv_history_warning")) {
                        found = true;
                        assertNotNull(warning.getSQLState());
                    }
                }
                assertTrue(found, "Server warning must be exposed by Statement.getWarnings");
                statement.clearWarnings();
                assertNull(statement.getWarnings());
                try (var result = statement.executeQuery("SELECT 9")) {
                    assertTrue(result.next());
                    assertEquals(9, result.getInt(1));
                }
            }
        });
    }

    @Test
    void resultMetadataRetainsAliasesPrecisionScaleAndColumnOrder() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer, amount numeric(18,4), label varchar(37))");
            execute(c, "INSERT INTO " + s + ".t VALUES(1,123.4500,'中文')");
            try (var statement = c.createStatement()) {
                statement.setQueryTimeout(15);
                try (var result = statement.executeQuery("SELECT amount AS \"金额\",label AS \"Display Label\",id FROM " + s + ".t")) {
                    var metadata = result.getMetaData();
                    assertEquals(3, metadata.getColumnCount());
                    assertEquals("金额", metadata.getColumnLabel(1));
                    assertEquals("Display Label", metadata.getColumnLabel(2));
                    assertEquals("id", metadata.getColumnLabel(3));
                    assertEquals(java.sql.Types.NUMERIC, metadata.getColumnType(1));
                    assertEquals(18, metadata.getPrecision(1));
                    assertEquals(4, metadata.getScale(1));
                    assertEquals(37, metadata.getPrecision(2));
                    assertTrue(result.next());
                    assertEquals(new java.math.BigDecimal("123.4500"), result.getBigDecimal(1));
                    assertEquals("中文", result.getString(2));
                    assertEquals(1, result.getInt(3));
                }
            }
        });
    }

    @Test
    void closedStatementAndResultRejectUseButConnectionRemainsUsable() throws Exception {
        inIsolatedSchema((c, s) -> {
            var statement = c.createStatement();
            statement.setQueryTimeout(15);
            var result = statement.executeQuery("SELECT 1");
            try {
                assertTrue(result.next());
                result.close();
                assertTrue(result.isClosed());
                assertThrows(java.sql.SQLException.class, () -> result.getInt(1));
                statement.close();
                assertThrows(java.sql.SQLException.class, () -> statement.executeQuery("SELECT 2"));
                assertFalse(c.isClosed());
                assertTrue(c.isValid(5));
                assertRows(c, "SELECT 3", List.of(List.of("3")));
            } finally {
                result.close();
                statement.close();
            }
        });
    }

    @Test
    void preparedDataContainingSqlDoesNotExecuteAsStatements() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer, value text)");
            String payload = "'); DROP TABLE " + s + ".t; -- 中文\n\\quoted";
            try (var insert = c.prepareStatement("INSERT INTO " + s + ".t VALUES(?,?)")) {
                insert.setQueryTimeout(15);
                insert.setInt(1, 1);
                insert.setString(2, payload);
                assertEquals(1, insert.executeUpdate());
            }
            assertRows(c, "SELECT id,value FROM " + s + ".t", List.of(List.of("1", payload)));
        });
    }

    @Test
    void mergeUpdatesMatchesInsertsMissingRowsAndRollsBack() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".target(id integer PRIMARY KEY, amount numeric(18,2)) DISTRIBUTE BY HASH(id)");
            execute(c, "CREATE TABLE " + s + ".source(id integer, amount numeric(18,2)) DISTRIBUTE BY HASH(id)");
            execute(c, "INSERT INTO " + s + ".target VALUES(1,10.25),(2,20.50)");
            execute(c, "INSERT INTO " + s + ".source VALUES(1,15.75),(3,30.25)");
            c.setAutoCommit(false);
            execute(c, "MERGE INTO " + s + ".target t USING " + s + ".source x ON(t.id=x.id) "
                + "WHEN MATCHED THEN UPDATE SET amount=x.amount "
                + "WHEN NOT MATCHED THEN INSERT(id,amount) VALUES(x.id,x.amount)");
            assertRows(c, "SELECT id,amount FROM " + s + ".target ORDER BY id",
                List.of(List.of("1", "15.75"), List.of("2", "20.50"), List.of("3", "30.25")));
            c.rollback();
            assertRows(c, "SELECT id,amount FROM " + s + ".target ORDER BY id",
                List.of(List.of("1", "10.25"), List.of("2", "20.50")));
        });
    }

    @Test
    void duplicateKeyUpdatePreservesKeyAndUpdatesOnlyConflictingRow() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer PRIMARY KEY, label varchar(50)) DISTRIBUTE BY HASH(id)");
            execute(c, "INSERT INTO " + s + ".t VALUES(1,'before'),(2,'unchanged')");
            execute(c, "INSERT INTO " + s + ".t VALUES(1,'ignored') ON DUPLICATE KEY UPDATE label='after'");
            assertRows(c, "SELECT id,label FROM " + s + ".t ORDER BY id",
                List.of(List.of("1", "after"), List.of("2", "unchanged")));
        });
    }

    @Test
    void correlatedExistsAndNotExistsRespectNullsAndAliases() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".accounts(id integer, label varchar(50)) DISTRIBUTE BY HASH(id)");
            execute(c, "CREATE TABLE " + s + ".entries(account_id integer) DISTRIBUTE BY HASH(account_id)");
            execute(c, "INSERT INTO " + s + ".accounts VALUES(1,'一'),(2,'二'),(3,'三')");
            execute(c, "INSERT INTO " + s + ".entries VALUES(1),(1),(3),(NULL)");
            assertRows(c, "SELECT a.id,a.label FROM " + s + ".accounts a WHERE EXISTS "
                + "(SELECT 1 FROM " + s + ".entries e WHERE e.account_id=a.id) ORDER BY a.id",
                List.of(List.of("1", "一"), List.of("3", "三")));
            assertRows(c, "SELECT a.id FROM " + s + ".accounts a WHERE NOT EXISTS "
                + "(SELECT 1 FROM " + s + ".entries e WHERE e.account_id=a.id)", List.of(List.of("2")));
            assertRows(c, "SELECT id FROM " + s + ".accounts WHERE id NOT IN (SELECT account_id FROM " + s + ".entries)",
                List.of());
        });
    }

    @Test
    void joinGroupingHavingAndQuotedAliasesReturnExpectedRows() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".a(id integer, label varchar(50)) DISTRIBUTE BY HASH(id)");
            execute(c, "CREATE TABLE " + s + ".b(id integer, amount integer) DISTRIBUTE BY HASH(id)");
            execute(c, "INSERT INTO " + s + ".a VALUES(1,'first'),(2,'second'),(3,'empty')");
            execute(c, "INSERT INTO " + s + ".b VALUES(1,10),(1,20),(2,5)");
            assertRows(c, "SELECT a.label AS \"Account Label\",coalesce(sum(b.amount),0) AS \"Total\" "
                + "FROM " + s + ".a a LEFT JOIN " + s + ".b b ON a.id=b.id "
                + "GROUP BY a.id,a.label HAVING coalesce(sum(b.amount),0)>=10 OR count(b.id)=0 ORDER BY a.id",
                List.of(List.of("first", "30"), List.of("empty", "0")));
        });
    }

    @Test
    void stringFunctionsPreserveUnicodeAndLiteralQuotes() throws Exception {
        inIsolatedSchema((c, s) -> assertRows(c,
            "SELECT substr('甲乙丙',2,2), length('甲乙丙'), replace('O''Brien','''','-'), "
                + "trim('  保留  '), upper('aBc'), lower('XyZ'), concat('甲','乙')",
            List.of(List.of("乙丙", "3", "O-Brien", "保留", "ABC", "xyz", "甲乙"))));
    }

    @Test
    void numericFunctionsReturnExactDecimalResults() throws Exception {
        inIsolatedSchema((c, s) -> {
            try (var statement = c.createStatement()) {
                statement.setQueryTimeout(15);
                try (var r = statement.executeQuery("SELECT abs(-7),ceil(1.2),floor(-1.2),round(12.345::numeric,2),"
                    + "mod(17,5),power(2::numeric,10)")) {
                    assertTrue(r.next());
                    String[] expected = {"7", "2", "-2", "12.35", "2", "1024"};
                    for (int i = 0; i < expected.length; i++) {
                        assertEquals(0, new java.math.BigDecimal(expected[i]).compareTo(r.getBigDecimal(i + 1)));
                    }
                    assertFalse(r.next());
                }
            }
        });
    }

    private static void assertRows(Connection c, String sql, List<List<String>> expected) throws Exception {
        try (var statement = c.createStatement()) {
            statement.setQueryTimeout(15);
            try (var r = statement.executeQuery(sql)) {
                var actual = new java.util.ArrayList<List<String>>();
                while (r.next()) {
                    var row = new java.util.ArrayList<String>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                        row.add(r.getString(i));
                    }
                    actual.add(row);
                }
                assertEquals(expected, actual);
            }
        }
    }

    private PostgreProcedureParameter parameter(String type, DBSProcedureParameterKind kind) {
        var p = mock(PostgreProcedureParameter.class);
        when(p.getFullTypeName()).thenReturn(type);
        when(p.getParameterKind()).thenReturn(kind);
        return p;
    }

    private static List<String> invoke(Connection connection, String name, GaussDBDebugArguments.Plan plan) throws Exception {
        try (var statement = connection.prepareStatement("CALL " + name + '(' + plan.sql() + ')')) {
            statement.setQueryTimeout(15);
            for (int i = 0; i < plan.values().size(); i++) {
                if (plan.values().get(i) == null) {
                    statement.setNull(i + 1, java.sql.Types.NULL);
                } else {
                    statement.setString(i + 1, plan.values().get(i));
                }
            }
            assertTrue(statement.execute(), "Expected output row from procedure");
            try (var result = statement.getResultSet()) {
                assertTrue(result.next());
                var values = new java.util.ArrayList<String>();
                for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                    values.add(result.getString(i));
                }
                assertFalse(result.next());
                return values;
            }
        }
    }

    @Test
    void integerArrayRetainsNullElementsAndOrder() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer, v integer[])");
            java.sql.Array array = c.createArrayOf("int4", new Integer[]{1, null, -2, Integer.MAX_VALUE});
            try (var insert = c.prepareStatement("INSERT INTO " + s + ".t VALUES(1,?)")) {
                insert.setQueryTimeout(15);
                insert.setArray(1, array);
                assertEquals(1, insert.executeUpdate());
            } finally {
                array.free();
            }
            try (var statement = c.createStatement(); var result = statement.executeQuery("SELECT v FROM " + s + ".t")) {
                assertTrue(result.next());
                var returned = result.getArray(1);
                try {
                    assertArrayEquals(new Integer[]{1, null, -2, Integer.MAX_VALUE}, (Object[]) returned.getArray());
                } finally {
                    returned.free();
                }
            }
        });
    }

    @Test
    void jsonParametersPreserveUnicodeAndNestedValues() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer, v json)");
            try (var insert = c.prepareStatement("INSERT INTO " + s + ".t VALUES(1,?::json)")) {
                insert.setQueryTimeout(15);
                insert.setString(1, "{\"中文\":\"值'\",\"items\":[1,null,{\"enabled\":true}]}");
                assertEquals(1, insert.executeUpdate());
            }
            try (var statement = c.createStatement(); var result = statement.executeQuery(
                "SELECT v->>'中文',v->'items'->>0,v->'items'->2->>'enabled' FROM " + s + ".t")) {
                assertTrue(result.next());
                assertEquals("值'", result.getString(1));
                assertEquals("1", result.getString(2));
                assertEquals("true", result.getString(3));
            }
        });
    }

    @Test
    void xmlValuePreservesEscapedTextThroughJdbc() throws Exception {
        inIsolatedSchema((c, s) -> {
            String xml = "<root><value>中文&amp;&lt;</value></root>";
            try (var statement = c.prepareStatement("SELECT ?::xml")) {
                statement.setQueryTimeout(15);
                statement.setString(1, xml);
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    var returned = result.getSQLXML(1);
                    try {
                        assertEquals(xml, returned.getString());
                    } finally {
                        returned.free();
                    }
                }
            }
        });
    }

    @Test
    void booleanNullIsNotConfusedWithFalseByJdbc() throws Exception {
        inIsolatedSchema((c, s) -> {
            try (var statement = c.createStatement(); var result = statement.executeQuery(
                "SELECT true,false,NULL::boolean")) {
                assertTrue(result.next());
                assertTrue(result.getBoolean(1));
                assertFalse(result.wasNull());
                assertFalse(result.getBoolean(2));
                assertFalse(result.wasNull());
                assertFalse(result.getBoolean(3));
                assertTrue(result.wasNull());
                assertNull(result.getObject(3));
            }
        });
    }

    @Test
    void leapDateAndTimeMicrosecondsRemainExact() throws Exception {
        inIsolatedSchema((c, s) -> {
            try (var statement = c.createStatement(); var result = statement.executeQuery(
                "SELECT to_date('2024-02-29','YYYY-MM-DD'),TIME '23:59:59.123456',"
                    + "EXTRACT(MICROSECONDS FROM TIME '23:59:59.123456')")) {
                assertTrue(result.next());
                assertEquals(java.sql.Date.valueOf("2024-02-29"), result.getDate(1));
                assertEquals("23:59:59.123456", result.getString(2));
                assertEquals(0, new java.math.BigDecimal("59123456").compareTo(result.getBigDecimal(3)));
            }
        });
    }

    @Test
    void windowFunctionsPreservePartitionOrderAndRankTies() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer, grp integer, amount integer)");
            execute(c, "INSERT INTO " + s + ".t VALUES(1,1,10),(2,1,10),(3,1,20),(4,2,5)");
            try (var statement = c.createStatement(); var result = statement.executeQuery(
                "SELECT id,row_number() OVER(PARTITION BY grp ORDER BY amount,id),"
                + "rank() OVER(PARTITION BY grp ORDER BY amount),dense_rank() OVER(PARTITION BY grp ORDER BY amount)"
                + " FROM " + s + ".t ORDER BY id")) {
                int[][] expected = {{1,1,1,1},{2,2,1,1},{3,3,3,2},{4,1,1,1}};
                for (int[] row : expected) {
                    assertTrue(result.next());
                    for (int i = 0; i < row.length; i++) assertEquals(row[i], result.getInt(i + 1));
                }
                assertFalse(result.next());
            }
        });
    }

    @Test
    void aggregatesDistinguishNullsFromRowsAndPreserveDecimalPrecision() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer, amount numeric(20,4))");
            execute(c, "INSERT INTO " + s + ".t VALUES(1,0.1000),(2,0.2000),(3,NULL)");
            try (var statement = c.createStatement(); var result = statement.executeQuery(
                "SELECT count(*),count(amount),sum(amount),avg(amount),min(amount),max(amount) FROM " + s + ".t")) {
                assertTrue(result.next());
                assertEquals(3, result.getInt(1));
                assertEquals(2, result.getInt(2));
                String[] expected = {"0.3", "0.15", "0.1", "0.2"};
                for (int i = 0; i < expected.length; i++) {
                    assertEquals(0, new java.math.BigDecimal(expected[i]).compareTo(result.getBigDecimal(i + 3)));
                }
                assertFalse(result.next());
            }
        });
    }

    @Test
    void compositePrimaryKeyMetadataPreservesColumnOrder() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(a integer,b integer,v varchar(20), CONSTRAINT pk_hist PRIMARY KEY(a,b))");
            var keys = new java.util.TreeMap<Integer, String>();
            try (var result = c.getMetaData().getPrimaryKeys(null, s, "t")) {
                while (result.next()) {
                    assertEquals("pk_hist", result.getString("PK_NAME"));
                    assertNull(keys.put(result.getInt("KEY_SEQ"), result.getString("COLUMN_NAME")));
                }
            }
            assertEquals(java.util.Map.of(1, "a", 2, "b"), keys);
            execute(c, "INSERT INTO " + s + ".t VALUES(1,1,'a'),(1,2,'b')");
            assertEquals("23505", assertThrows(java.sql.SQLException.class,
                () -> execute(c, "INSERT INTO " + s + ".t VALUES(1,1,'duplicate')")).getSQLState());
            assertEquals(2, count(c, s + ".t"));
        });
    }

    @Test
    void indexMetadataTracksCreationAndDeletion() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer, value varchar(20))");
            execute(c, "CREATE INDEX ix_hist ON " + s + ".t(value)");
            boolean found = false;
            try (var indexes = c.getMetaData().getIndexInfo(null, s, "t", false, false)) {
                while (indexes.next()) {
                    if ("ix_hist".equals(indexes.getString("INDEX_NAME"))) {
                        assertEquals("value", indexes.getString("COLUMN_NAME"));
                        assertTrue(indexes.getBoolean("NON_UNIQUE"));
                        found = true;
                    }
                }
            }
            assertTrue(found);
            execute(c, "DROP INDEX " + s + ".ix_hist");
            try (var indexes = c.getMetaData().getIndexInfo(null, s, "t", false, false)) {
                while (indexes.next()) assertNotEquals("ix_hist", indexes.getString("INDEX_NAME"));
            }
        });
    }

    @Test
    void productionTableDdlPreservesExpressionAndPartialIndexAfterRename() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".expression_indexed";
            execute(c, "CREATE TABLE " + table + "(id integer,label varchar(30),active boolean) DISTRIBUTE BY HASH(id)");
            execute(c, "CREATE INDEX ix_expression ON " + table + "(lower(label)) WHERE active");
            execute(c, "ALTER INDEX " + s + ".ix_expression RENAME TO \"Index 中文\"");
            String ddl = readProductionTableDdl(c, table);
            assertNotNull(ddl);
            execute(c, "DROP TABLE " + table);
            execute(c, ddl);
            try (var statement = c.prepareStatement(
                "SELECT pg_get_indexdef(i.indexrelid),pg_get_expr(i.indexprs,i.indrelid),"
                    + "pg_get_expr(i.indpred,i.indrelid) FROM pg_index i JOIN pg_class x ON x.oid=i.indexrelid "
                    + "JOIN pg_namespace n ON n.oid=x.relnamespace WHERE n.nspname=? AND x.relname=?")) {
                statement.setString(1, s);
                statement.setString(2, "Index 中文");
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertTrue(result.getString(1).contains("\"Index 中文\""));
                    assertTrue(result.getString(2).contains("lower("));
                    assertTrue(result.getString(2).contains("label"));
                    assertEquals("active", result.getString(3));
                    assertFalse(result.next());
                }
            }
            execute(c, "INSERT INTO " + table + " VALUES(1,'Alpha',true),(2,'ALPHA',false),(3,'Beta',true)");
            assertRows(c, "SELECT id FROM " + table + " WHERE active AND lower(label)='alpha'",
                List.of(List.of("1")));
            boolean found = false;
            try (var indexes = c.getMetaData().getIndexInfo(null, s, "expression_indexed", false, false)) {
                while (indexes.next()) {
                    assertNotEquals("ix_expression", indexes.getString("INDEX_NAME"));
                    if ("Index 中文".equals(indexes.getString("INDEX_NAME"))) {
                        found = true;
                        assertTrue(indexes.getBoolean("NON_UNIQUE"));
                    }
                }
            }
            assertTrue(found);
            execute(c, "DROP INDEX " + s + ".\"Index 中文\"");
            assertEquals(3, count(c, table));
            try (var indexes = c.getMetaData().getIndexInfo(null, s, "expression_indexed", false, false)) {
                while (indexes.next()) assertNotEquals("Index 中文", indexes.getString("INDEX_NAME"));
            }
        });
    }

    @Test
    void compositeForeignKeyMetadataAndCascadeWhenServerSupportsForeignKeys() throws Exception {
        assertCompositeForeignKey(System.getenv("GAUSSDB_HISTORY_CONNECTION"), " DISTRIBUTE BY REPLICATION");
    }

    @Test
    void centralizedCompositeForeignKeyMetadataAndCascade() throws Exception {
        assertCompositeForeignKey(System.getenv("GAUSSDB_HISTORY_CENTRAL_CONNECTION"), "");
    }

    private void assertCompositeForeignKey(String config, String distribution) throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".fk_parent(a integer,b integer,PRIMARY KEY(a,b))" + distribution);
            try {
                execute(c, "CREATE TABLE " + s + ".fk_child(id integer,a integer,b integer,CONSTRAINT fk_pair "
                    + "FOREIGN KEY(a,b) REFERENCES " + s + ".fk_parent(a,b) ON UPDATE CASCADE ON DELETE CASCADE) "
                    + distribution);
            } catch (java.sql.SQLException e) {
                if ("0A000".equals(e.getSQLState())) {
                    assumeTrue(false, "Server rejects foreign keys with SQLSTATE 0A000; positive FK path not verified");
                }
                throw e;
            }
            String childDdl = readProductionTableDdl(c, s + ".fk_child");
            assertNotNull(childDdl);
            assertTrue(childDdl.toUpperCase(java.util.Locale.ROOT).contains("FOREIGN KEY"));
            execute(c, "DROP TABLE " + s + ".fk_child");
            execute(c, childDdl);
            var columns = new java.util.ArrayList<String>();
            try (var keys = c.getMetaData().getImportedKeys(null, s, "fk_child")) {
                while (keys.next()) {
                    assertEquals("fk_pair", keys.getString("FK_NAME"));
                    assertEquals(s, keys.getString("PKTABLE_SCHEM"));
                    assertEquals("fk_parent", keys.getString("PKTABLE_NAME"));
                    assertEquals(java.sql.DatabaseMetaData.importedKeyCascade, keys.getShort("UPDATE_RULE"));
                    assertEquals(java.sql.DatabaseMetaData.importedKeyCascade, keys.getShort("DELETE_RULE"));
                    columns.add(keys.getShort("KEY_SEQ") + ":" + keys.getString("FKCOLUMN_NAME") + ":"
                        + keys.getString("PKCOLUMN_NAME"));
                }
            }
            columns.sort(String::compareTo);
            assertEquals(List.of("1:a:a", "2:b:b"), columns);
            execute(c, "INSERT INTO " + s + ".fk_parent VALUES(1,2)");
            execute(c, "INSERT INTO " + s + ".fk_child VALUES(10,1,2)");
            assertEquals("23503", assertThrows(java.sql.SQLException.class,
                () -> execute(c, "INSERT INTO " + s + ".fk_child VALUES(11,8,9)")).getSQLState());
            execute(c, "UPDATE " + s + ".fk_parent SET a=3 WHERE a=1");
            assertRows(c, "SELECT a,b FROM " + s + ".fk_child", List.of(List.of("3", "2")));
            execute(c, "DELETE FROM " + s + ".fk_parent WHERE a=3");
            assertEquals(0, count(c, s + ".fk_child"));
        }, java.util.Map.of(), config);
    }

    @Test
    void checkConstraintRejectsBadDataWithoutChangingExistingRows() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer, amount numeric CHECK(amount>=0))");
            execute(c, "INSERT INTO " + s + ".t VALUES(1,0),(2,1.25),(3,NULL)");
            var error = assertThrows(java.sql.SQLException.class,
                () -> execute(c, "INSERT INTO " + s + ".t VALUES(4,-0.01)"));
            assertEquals("23514", error.getSQLState());
            assertEquals(3, count(c, s + ".t"));
        });
    }

    @Test
    void viewRenameUpdatesMetadataAndPreservesQueryResults() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer)");
            execute(c, "INSERT INTO " + s + ".t VALUES(1),(2)");
            execute(c, "CREATE VIEW " + s + ".v_old AS SELECT id FROM " + s + ".t");
            execute(c, "ALTER VIEW " + s + ".v_old RENAME TO v_new");
            try (var old = c.getMetaData().getTables(null, s, "v_old", new String[]{"VIEW"})) {
                assertFalse(old.next());
            }
            try (var renamed = c.getMetaData().getTables(null, s, "v_new", new String[]{"VIEW"})) {
                assertTrue(renamed.next());
                assertEquals("v_new", renamed.getString("TABLE_NAME"));
                assertFalse(renamed.next());
            }
            assertEquals(2, count(c, s + ".v_new"));
        });
    }

    @Test
    void dependentViewPreventsUnsafeDropAndCascadeRemovesOnlyDependents() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer)");
            execute(c, "CREATE TABLE " + s + ".unrelated(id integer)");
            execute(c, "CREATE VIEW " + s + ".v AS SELECT id FROM " + s + ".t");
            assertEquals("2BP01", assertThrows(java.sql.SQLException.class,
                () -> execute(c, "DROP TABLE " + s + ".t")).getSQLState());
            execute(c, "DROP TABLE " + s + ".t CASCADE");
            try (var result = c.getMetaData().getTables(null, s, "v", new String[]{"VIEW"})) {
                assertFalse(result.next());
            }
            assertEquals(0, count(c, s + ".unrelated"));
        });
    }

    @Test
    void sequenceIncrementAffectsReturnedValues() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE SEQUENCE " + s + ".seq START WITH 10 INCREMENT BY 3");
            try (var statement = c.createStatement()) {
                statement.setQueryTimeout(15);
                for (long expected : new long[]{10, 13}) {
                    try (var result = statement.executeQuery("SELECT nextval('" + s + ".seq')")) {
                        assertTrue(result.next());
                        assertEquals(expected, result.getLong(1));
                    }
                }
            }
        });
    }

    @Test
    void sequenceRestartRequiresServerSupport() throws Exception {
        assertSequenceRestart(System.getenv("GAUSSDB_HISTORY_CONNECTION"));
    }

    @Test
    void centralizedSequenceRestartRequiresServerSupport() throws Exception {
        assertSequenceRestart(System.getenv("GAUSSDB_HISTORY_CENTRAL_CONNECTION"));
    }

    private void assertSequenceRestart(String config) throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE SEQUENCE " + s + ".seq START WITH 10");
            try {
                execute(c, "ALTER SEQUENCE " + s + ".seq RESTART WITH 100");
            } catch (java.sql.SQLException e) {
                assumeTrue(!"0A000".equals(e.getSQLState()),
                    "Server rejects ALTER SEQUENCE RESTART; restart is NOT validated. SQLSTATE 0A000");
                throw e;
            }
            try (var statement = c.createStatement()) {
                statement.setQueryTimeout(15);
                try (var result = statement.executeQuery("SELECT nextval('" + s + ".seq')")) {
                    assertTrue(result.next());
                    assertEquals(100, result.getLong(1));
                }
            }
        }, java.util.Map.of(), config);
    }

    @Test
    void queryTimeoutReportsCancellationAndConnectionRemainsUsable() throws Exception {
        inIsolatedSchema((c, s) -> {
            try (var statement = c.createStatement()) {
                statement.setQueryTimeout(1);
                long started = System.nanoTime();
                var error = assertThrows(java.sql.SQLException.class,
                    () -> statement.executeQuery("SELECT pg_sleep(8)"));
                assertEquals("57014", error.getSQLState());
                assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started).toSeconds() < 6,
                    "Timeout must interrupt the query, not wait for normal completion");
            }
            assertConnectionUsable(c);
        });
    }

    @Test
    void explicitStatementCancelInterruptsOnlyItsOwnQuery() throws Exception {
        inIsolatedSchema((c, s) -> {
            var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            var cancelFailure = new java.util.concurrent.atomic.AtomicReference<Exception>();
            try (var statement = c.createStatement()) {
                statement.setQueryTimeout(15);
                long started = System.nanoTime();
                var cancellation = scheduler.scheduleAtFixedRate(() -> {
                    try {
                        statement.cancel();
                    } catch (Exception e) {
                        cancelFailure.compareAndSet(null, e);
                    }
                }, 500, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
                try {
                    var error = assertThrows(java.sql.SQLException.class,
                        () -> statement.executeQuery("SELECT pg_sleep(10)"));
                    assertEquals("57014", error.getSQLState());
                    assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started).toSeconds() < 8,
                        "Explicit cancel must finish before the query timeout fallback");
                    assertNull(cancelFailure.get());
                } finally {
                    cancellation.cancel(false);
                    scheduler.shutdown();
                    assertTrue(scheduler.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
                }
            } finally {
                scheduler.shutdownNow();
            }
            assertConnectionUsable(c);
        });
    }

    @Test
    void statementCloseClosesResultButNotConnection() throws Exception {
        inIsolatedSchema((c, s) -> {
            var statement = c.createStatement();
            var result = statement.executeQuery("SELECT 42");
            try {
                assertTrue(result.next());
                assertEquals(42, result.getInt(1));
            } finally {
                statement.close();
            }
            assertTrue(statement.isClosed());
            assertTrue(result.isClosed());
            assertFalse(c.isClosed());
            assertConnectionUsable(c);
        });
    }

    @Test
    void missingObjectErrorRetainsStateAndAutocommitConnectionRecovers() throws Exception {
        inIsolatedSchema((c, s) -> {
            var error = assertThrows(java.sql.SQLException.class,
                () -> execute(c, "SELECT * FROM " + s + ".definitely_missing"));
            assertEquals("42P01", error.getSQLState());
            assertConnectionUsable(c);
        });
    }

    @Test
    void maxRowsLimitsResultsWithoutChangingStoredData() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer)");
            execute(c, "INSERT INTO " + s + ".t VALUES(1),(2),(3),(4),(5)");
            try (var statement = c.createStatement()) {
                statement.setQueryTimeout(15);
                statement.setMaxRows(2);
                try (var result = statement.executeQuery("SELECT id FROM " + s + ".t ORDER BY id")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                    assertTrue(result.next());
                    assertEquals(2, result.getInt(1));
                    assertFalse(result.next());
                }
            }
            assertEquals(5, count(c, s + ".t"));
        });
    }

    @Test
    void vendorDriverRejectsUnrelatedUrlWithoutOpeningConnection() throws Exception {
        String jar = System.getenv("GAUSSDB_HISTORY_JDBC");
        assumeTrue(jar != null, "Vendor driver is not configured");
        var p = new Properties();
        String config = System.getenv("GAUSSDB_HISTORY_CONNECTION");
        assumeTrue(config != null, "Driver class configuration is unavailable");
        try (var input = Files.newInputStream(Path.of(config))) {
            p.load(input);
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()},
            ClassLoader.getPlatformClassLoader())) {
            var driver = (Driver) loader.loadClass(p.getProperty("driverClass")).getConstructor().newInstance();
            assertTrue(driver.acceptsURL(p.getProperty("url")));
            assertFalse(driver.acceptsURL("jdbc:unrelated:test"));
            assertNull(driver.connect("jdbc:unrelated:test", new Properties()));
        }
    }

    private static void assertConnectionUsable(Connection c) throws Exception {
        try (var statement = c.createStatement()) {
            statement.setQueryTimeout(5);
            try (var result = statement.executeQuery("SELECT 42")) {
                assertTrue(result.next());
                assertEquals(42, result.getInt(1));
                assertFalse(result.next());
            }
        }
    }

    @Test
    void realAggregatePlanIsParsedByTheProductionXmlPlanner() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer, value integer)");
            execute(c, "INSERT INTO " + s + ".t VALUES(1,10),(1,20),(2,30)");
            assertRealPlan(c, "SELECT id,sum(value) FROM " + s + ".t GROUP BY id ORDER BY id");
        });
    }

    @Test
    void realJoinPlanPreservesChildOrderAndParentRelationships() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".a(id integer)");
            execute(c, "CREATE TABLE " + s + ".b(id integer)");
            execute(c, "INSERT INTO " + s + ".a VALUES(1),(2),(3)");
            execute(c, "INSERT INTO " + s + ".b VALUES(2),(3),(4)");
            assertRealPlan(c, "SELECT a.id FROM " + s + ".a a JOIN " + s + ".b b ON a.id=b.id WHERE a.id>1");
        });
    }

    @Test
    void realUnionPlanPreservesNodeProperties() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer)");
            execute(c, "INSERT INTO " + s + ".t VALUES(1),(2),(3)");
            assertRealPlan(c, "SELECT id FROM " + s + ".t UNION ALL SELECT id+1 FROM " + s + ".t");
        });
    }

    @Test
    void realRecursivePlanPreservesWorkTableAndCteNodes() throws Exception {
        inIsolatedSchema((c, s) -> assertRealPlan(c,
            "WITH RECURSIVE numbers(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM numbers WHERE n<5) SELECT sum(n) FROM numbers",
            "Recursive Union", "WorkTable Scan", "CTE Scan"));
    }

    @Test
    void realWindowPlanPreservesSortAndWindowNodes() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".window_rows(id integer, category integer)");
            execute(c, "INSERT INTO " + s + ".window_rows VALUES(1,1),(2,1),(3,2)");
            assertRealPlan(c, "SELECT id,row_number() OVER(PARTITION BY category ORDER BY id) FROM " + s + ".window_rows",
                "WindowAgg", "Sort");
        });
    }

    @Test
    void realIndexPlanPreservesIndexScanUnderDistributedParent() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".indexed_rows(id integer PRIMARY KEY, label text)");
            execute(c, "INSERT INTO " + s + ".indexed_rows VALUES(1,'one'),(2,'two')");
            execute(c, "SET enable_seqscan=off");
            execute(c, "SET enable_indexonlyscan=off");
            execute(c, "SET enable_bitmapscan=off");
            assertRealPlan(c, "SELECT label FROM " + s + ".indexed_rows WHERE id=1", "Index Scan");
        });
    }

    private static void assertRealPlan(Connection c, String query, String... requiredNodeTypes) throws Exception {
        assertRealPlan(c, query, false, requiredNodeTypes);
    }

    @Test
    void realAnalyzePlanPreservesActualRowsTimingAndLoops() throws Exception {
        inIsolatedSchema((c, s) -> assertRealPlan(c,
            "WITH RECURSIVE numbers(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM numbers WHERE n<5) SELECT sum(n) FROM numbers",
            true, "Recursive Union", "WorkTable Scan"));
    }

    private static void assertRealPlan(Connection c, String query, boolean analyze, String... requiredNodeTypes) throws Exception {
        // Dedicated fixture session only: expose the operator tree instead of an opaque shipped query.
        execute(c, "SET enable_fast_query_shipping = off");
        var configuration = new org.jkiss.dbeaver.model.exec.plan.DBCQueryPlannerConfiguration();
        if (analyze) {
            configuration.getParameters().put("ANALYZE", true);
            configuration.getParameters().put("TIMING", true);
        }
        var plan = new org.jkiss.dbeaver.ext.postgresql.model.plan.PostgreExecutionPlan(false, false, query, configuration);
        String xml;
        try (var statement = c.createStatement()) {
            statement.setQueryTimeout(15);
            try (var result = statement.executeQuery(plan.getPlanQueryString())) {
                assertTrue(result.next());
                xml = result.getString(1);
                assertFalse(result.next());
            }
        }
        // Bridge the real server payload into the same production parser used by DBeaver.
        // Only the DBeaver session adapter is mocked, not the XML or plan nodes.
        var session = mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCSession.class);
        var statement = mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement.class);
        var rows = mock(org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet.class);
        var sqlXml = mock(java.sql.SQLXML.class);
        when(session.createStatement()).thenReturn(statement);
        when(statement.executeQuery(plan.getPlanQueryString())).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getSQLXML(1)).thenReturn(sqlXml);
        when(sqlXml.getString()).thenReturn(xml);
        when(sqlXml.getBinaryStream()).thenReturn(new java.io.ByteArrayInputStream(
            xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        plan.explain(session);
        assertEquals(query, plan.getQueryString());
        assertEquals(xml, plan.getPlanSourceData());
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
            xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var expectedPlans = document.getElementsByTagName("Plan");
        if (analyze) {
            assertTrue(document.getElementsByTagName("Actual-Rows").getLength() > 0);
            assertTrue(document.getElementsByTagName("Actual-Total-Time").getLength() > 0);
            assertTrue(document.getElementsByTagName("Actual-Loops").getLength() > 0);
        }
        var typeElements = document.getElementsByTagName("Node-Type");
        var actualTypes = new java.util.HashSet<String>();
        for (int i = 0; i < typeElements.getLength(); i++) {
            actualTypes.add(typeElements.item(i).getTextContent());
        }
        for (String required : requiredNodeTypes) {
            assertTrue(actualTypes.contains(required), () -> "Missing required operator " + required + ": " + actualTypes);
        }
        assertTrue(expectedPlans.getLength() > 1, "Fixture must exercise a real parent/child plan, not an empty Result");
        var roots = plan.getPlanNodes(java.util.Map.of());
        assertEquals(1, roots.size());
        assertNull(roots.getFirst().getParent());
        assertEquals(expectedPlans.getLength(), assertPlanNode(roots.getFirst(),
            (org.w3c.dom.Element) expectedPlans.item(0)));
        verify(rows).close();
        verify(statement).close();
    }

    private static int assertPlanNode(org.jkiss.dbeaver.model.exec.plan.DBCPlanNode node,
        org.w3c.dom.Element expected) {
        String type = null;
        String cost = null;
        String planRows = null;
        String actualRows = null;
        String actualTime = null;
        String actualLoops = null;
        var children = new java.util.ArrayList<org.w3c.dom.Element>();
        for (var child = expected.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof org.w3c.dom.Element element) {
                if ("Node-Type".equals(element.getTagName())) type = element.getTextContent();
                if ("Total-Cost".equals(element.getTagName())) cost = element.getTextContent();
                if ("Plan-Rows".equals(element.getTagName())) planRows = element.getTextContent();
                if ("Actual-Rows".equals(element.getTagName())) actualRows = element.getTextContent();
                if ("Actual-Total-Time".equals(element.getTagName())) actualTime = element.getTextContent();
                if ("Actual-Loops".equals(element.getTagName())) actualLoops = element.getTextContent();
                if ("Plans".equals(element.getTagName())) {
                    for (var nested = element.getFirstChild(); nested != null; nested = nested.getNextSibling()) {
                        if (nested instanceof org.w3c.dom.Element e && "Plan".equals(e.getTagName())) children.add(e);
                    }
                }
            }
        }
        assertNotNull(type);
        var pgNode = (org.jkiss.dbeaver.ext.postgresql.model.plan.PostgrePlanNodeBase<?>) node;
        assertEquals(type, pgNode.getNodeType());
        if (cost != null) assertEquals(Double.parseDouble(cost), pgNode.getNodeCost().doubleValue());
        String displayedRows = actualRows == null ? planRows : actualRows;
        if (displayedRows != null) {
            assertEquals(Long.parseLong(displayedRows), pgNode.getNodeRowCount().longValue());
            assertEquals(displayedRows, pgNode.getActualRows());
        }
        if (actualTime != null) {
            assertEquals(actualTime, pgNode.getTotalTime());
            assertEquals(Double.parseDouble(actualTime), pgNode.getNodeDuration().doubleValue());
        }
        if (actualLoops != null) {
            assertEquals(actualLoops, pgNode.getPropertyValue(null, "Actual-Loops"));
        }
        var actualChildren = new java.util.ArrayList<>(node.getNested());
        assertEquals(children.size(), actualChildren.size());
        int count = 1;
        for (int i = 0; i < children.size(); i++) {
            assertSame(node, actualChildren.get(i).getParent());
            count += assertPlanNode(actualChildren.get(i), children.get(i));
        }
        return count;
    }

    @Test
    void trailingDefaultAfterOutUsesServerExpression() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE PROCEDURE " + s + ".p(y OUT integer, x IN integer DEFAULT 6 * 7) AS BEGIN y := x; END;");
            var input = parameter("integer", DBSProcedureParameterKind.IN);
            when(input.getDefaultValue()).thenReturn("6 * 7");
            var parameters = List.of(parameter("integer", DBSProcedureParameterKind.OUT), input);
            assertEquals(List.of("42"), invoke(c, s + ".p",
                GaussDBDebugArguments.buildProcedure(parameters, List.of("ignored"), List.of("DEFAULT"))));
            assertEquals(List.of("9"), invoke(c, s + ".p",
                GaussDBDebugArguments.buildProcedure(parameters, List.of("9"), List.of("VALUE"))));
        });
    }

    @Test
    void sameRoutineNameInDifferentSchemasUsesQualifiedTarget() throws Exception {
        inIsolatedSchema((c, s) -> {
            // Nested isolation keeps both schemas owned and cleaned by the fixture.
            inIsolatedSchema((other, t) -> {
                execute(c, "CREATE PROCEDURE " + s + ".p(y OUT integer) AS BEGIN y := 11; END;");
                execute(other, "CREATE PROCEDURE " + t + ".p(y OUT integer) AS BEGIN y := 22; END;");
                var plan = GaussDBDebugArguments.buildProcedure(
                    List.of(parameter("integer", DBSProcedureParameterKind.OUT)), List.of(), List.of());
                execute(c, "SET search_path TO " + t + ",public");
                assertEquals(List.of("11"), invoke(c, s + ".p", plan));
                assertEquals(List.of("22"), invoke(c, t + ".p", plan));
            });
        });
    }

    @Test
    void searchPathOrderResolvesShadowedTablesWithoutChangingQualifiedAccess() throws Exception {
        inIsolatedSchema((c, first) -> inIsolatedSchema((other, second) -> {
            execute(c, "CREATE TABLE " + first + ".shadowed(id integer)");
            execute(other, "CREATE TABLE " + second + ".shadowed(id integer)");
            execute(c, "INSERT INTO " + first + ".shadowed VALUES(11)");
            execute(other, "INSERT INTO " + second + ".shadowed VALUES(22)");
            execute(c, "SET search_path TO " + first + "_absent," + first + "," + second);
            assertRows(c, "SELECT current_schema()", List.of(List.of(first)));
            assertRows(c, "SELECT id FROM shadowed", List.of(List.of("11")));
            execute(c, "SET search_path TO " + second + "," + first);
            assertRows(c, "SELECT current_schema()", List.of(List.of(second)));
            assertRows(c, "SELECT id FROM shadowed", List.of(List.of("22")));
            assertRows(c, "SELECT id FROM " + first + ".shadowed", List.of(List.of("11")));
            execute(c, "SET search_path TO " + first + "_absent");
            assertEquals("42P01", assertThrows(java.sql.SQLException.class,
                () -> execute(c, "SELECT id FROM shadowed")).getSQLState());
            assertRows(c, "SELECT id FROM " + second + ".shadowed", List.of(List.of("22")));
        }));
    }

    @Test
    void localSearchPathRestoresAfterCommitAndRollback() throws Exception {
        inIsolatedSchema((c, first) -> inIsolatedSchema((other, second) -> {
            execute(c, "SET search_path TO " + first);
            c.setAutoCommit(false);
            try {
                execute(c, "SET LOCAL search_path TO " + second);
                assertRows(c, "SELECT current_schema()", List.of(List.of(second)));
                c.rollback();
                assertRows(c, "SELECT current_schema()", List.of(List.of(first)));
                execute(c, "SET LOCAL search_path TO " + second);
                assertRows(c, "SELECT current_schema()", List.of(List.of(second)));
                c.commit();
                assertRows(c, "SELECT current_schema()", List.of(List.of(first)));
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
        }));
    }

    @Test
    void failedStatementCanBeRecoveredAtSavepointAndCommitted() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".t";
            execute(c, "CREATE TABLE " + table + "(id integer PRIMARY KEY)");
            c.setAutoCommit(false);
            execute(c, "INSERT INTO " + table + " VALUES(1)");
            var point = c.setSavepoint();
            var error = assertThrows(java.sql.SQLException.class,
                () -> execute(c, "INSERT INTO " + table + " VALUES(1)"));
            assertEquals("23505", error.getSQLState());
            c.rollback(point);
            execute(c, "INSERT INTO " + table + " VALUES(2)");
            c.commit();
            assertObserverCount(table, 2);
        });
    }

    @Test
    void preparedBatchCountsAndRollbackMatchPersistedRows() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".t";
            execute(c, "CREATE TABLE " + table + "(id integer, value varchar(40))");
            c.setAutoCommit(false);
            try (var insert = c.prepareStatement("INSERT INTO " + table + " VALUES(?,?)")) {
                insert.setQueryTimeout(15);
                for (int i = 0; i < 4; i++) {
                    insert.setInt(1, i);
                    insert.setString(2, "批量'" + i);
                    insert.addBatch();
                }
                int[] updates = insert.executeBatch();
                assertEquals(4, updates.length);
                // The vendor driver can aggregate a rewritten batch as [4,0,0,0].
                // DBeaver sums these counts; verify that total AND every stored row below.
                assertTrue(java.util.Arrays.stream(updates).allMatch(update -> update >= 0),
                    "Expected known successful counts: " + java.util.Arrays.toString(updates));
                assertEquals(4, java.util.Arrays.stream(updates).sum());
            }
            assertEquals(4, count(c, table));
            try (var select = c.createStatement()) {
                select.setQueryTimeout(15);
                try (var rows = select.executeQuery("SELECT id,value FROM " + table + " ORDER BY id")) {
                    for (int i = 0; i < 4; i++) {
                        assertTrue(rows.next());
                        assertEquals(i, rows.getInt(1));
                        assertEquals("批量'" + i, rows.getString(2));
                    }
                    assertFalse(rows.next());
                }
            }
            assertObserverCount(table, 0);
            c.rollback();
            assertObserverCount(table, 0);
        });
    }

    @Test
    void fetchSizeDoesNotLimitOrDuplicateRows() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer)");
            execute(c, "INSERT INTO " + s + ".t VALUES(1),(2),(3),(4),(5),(6),(7)");
            c.setAutoCommit(false);
            try (var statement = c.prepareStatement("SELECT id FROM " + s + ".t ORDER BY id")) {
                statement.setQueryTimeout(15);
                statement.setFetchSize(2);
                try (var result = statement.executeQuery()) {
                    int expected = 1;
                    while (result.next()) {
                        assertEquals(expected++, result.getInt(1));
                    }
                    assertEquals(8, expected);
                }
            }
        });
    }

    @Test
    void failedBatchRetainsSqlstateRollsBackAndCanReusePreparedStatement() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".failed_batch";
            execute(c, "CREATE TABLE " + table + "(id integer PRIMARY KEY, label text)");
            c.setAutoCommit(false);
            try (var insert = c.prepareStatement("INSERT INTO " + table + " VALUES(?,?)")) {
                insert.setQueryTimeout(15);
                for (int id : new int[]{1, 1, 2}) {
                    insert.setInt(1, id);
                    insert.setString(2, "批量" + id);
                    insert.addBatch();
                }
                var error = assertThrows(java.sql.BatchUpdateException.class, insert::executeBatch);
                assertEquals("23505", error.getSQLState());
                int[] counts = error.getUpdateCounts();
                assertTrue(counts.length <= 3);
                assertTrue(java.util.Arrays.stream(counts).allMatch(value -> value >= 0
                    || value == java.sql.Statement.SUCCESS_NO_INFO || value == java.sql.Statement.EXECUTE_FAILED));
                assertTrue(counts.length < 3 || java.util.Arrays.stream(counts)
                    .anyMatch(value -> value == java.sql.Statement.EXECUTE_FAILED), "Failed batch cannot report all entries successful");
                assertObserverCount(table, 0);
                c.rollback();
                assertEquals(0, count(c, table));
                assertObserverCount(table, 0);
                insert.clearBatch();
                insert.setInt(1, 9);
                insert.setString(2, "恢复成功");
                insert.addBatch();
                assertArrayEquals(new int[]{1}, insert.executeBatch());
                c.commit();
                assertObserverCount(table, 1);
                try (var query = c.createStatement(); var rows = query.executeQuery("SELECT id,label FROM " + table)) {
                    assertTrue(rows.next());
                    assertEquals(9, rows.getInt(1));
                    assertEquals("恢复成功", rows.getString(2));
                    assertFalse(rows.next());
                }
            }
        });
    }

    @Test
    void binaryAndTimestampPreservePayloadAndFraction() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(payload bytea, ts timestamp(6))");
            byte[] bytes = {0, 1, 39, 59, 92, 127, (byte) 128, (byte) 255};
            var timestamp = java.sql.Timestamp.valueOf("2024-02-29 23:59:59.123456");
            try (var insert = c.prepareStatement("INSERT INTO " + s + ".t VALUES(?,?)")) {
                insert.setQueryTimeout(15);
                insert.setBytes(1, bytes);
                insert.setTimestamp(2, timestamp);
                assertEquals(1, insert.executeUpdate());
            }
            try (var statement = c.createStatement()) {
                statement.setQueryTimeout(15);
                try (var result = statement.executeQuery("SELECT payload,ts FROM " + s + ".t")) {
                    assertTrue(result.next());
                    assertArrayEquals(bytes, result.getBytes(1));
                    assertEquals(timestamp, result.getTimestamp(2));
                    assertFalse(result.next());
                }
            }
        });
    }

    @Test
    void columnRenameAndDropAreReflectedByFreshMetadata() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer, old_name varchar(40), removable integer)");
            execute(c, "ALTER TABLE " + s + ".t RENAME COLUMN old_name TO new_name");
            // Preserve the first column, which a distributed server can choose as its distribution key.
            execute(c, "ALTER TABLE " + s + ".t DROP COLUMN removable");
            try (var columns = c.getMetaData().getColumns(null, s, "t", "%")) {
                assertTrue(columns.next());
                assertEquals("id", columns.getString("COLUMN_NAME"));
                assertTrue(columns.next());
                assertEquals("new_name", columns.getString("COLUMN_NAME"));
                assertEquals(40, columns.getInt("COLUMN_SIZE"));
                assertFalse(columns.next());
            }
        });
    }

    @Test
    void inoutBindingReturnsModifiedValue() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE PROCEDURE " + s + ".p(x INOUT integer) AS BEGIN x := x + 7; END;");
            var plan = GaussDBDebugArguments.buildProcedure(
                List.of(parameter("integer", DBSProcedureParameterKind.INOUT)), List.of("5"), List.of());
            assertEquals(List.of("12"), invoke(c, s + ".p", plan));
        });
    }

    @Test
    void outOnlyProcedureReturnsValueWithoutUserInput() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE PROCEDURE " + s + ".p(x OUT integer) AS BEGIN x := 42; END;");
            var plan = GaussDBDebugArguments.buildProcedure(
                List.of(parameter("integer", DBSProcedureParameterKind.OUT)), List.of(), List.of());
            assertEquals(List.of("42"), invoke(c, s + ".p", plan));
        });
    }

    @Test
    void outBeforeAndAfterInputKeepsPositionalAssociation() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE PROCEDURE " + s + ".p(a OUT integer, x IN integer, b OUT varchar) "
                + "AS BEGIN a := x + 1; b := '值=' || x; END;");
            var plan = GaussDBDebugArguments.buildProcedure(List.of(parameter("integer", DBSProcedureParameterKind.OUT),
                parameter("integer", DBSProcedureParameterKind.IN), parameter("varchar", DBSProcedureParameterKind.OUT)),
                List.of("9"), List.of());
            assertEquals(List.of("10", "值=9"), invoke(c, s + ".p", plan));
        });
    }

    @Test
    void mixedInoutAndOutDoNotShiftJdbcBindings() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE PROCEDURE " + s + ".p(x INOUT integer, y OUT integer, z IN integer) "
                + "AS BEGIN x := x + z; y := z * 2; END;");
            var plan = GaussDBDebugArguments.buildProcedure(List.of(parameter("integer", DBSProcedureParameterKind.INOUT),
                parameter("integer", DBSProcedureParameterKind.OUT), parameter("integer", DBSProcedureParameterKind.IN)),
                List.of("3", "8"), List.of());
            assertEquals(List.of("11", "16"), invoke(c, s + ".p", plan));
        });
    }

    @Test
    void explicitSqlNullIsNotTheTextNull() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE PROCEDURE " + s + ".p(x IN varchar, y OUT varchar) "
                + "AS BEGIN IF x IS NULL THEN y := 'SQL_NULL'; ELSE y := x; END IF; END;");
            var parameters = List.of(parameter("varchar", DBSProcedureParameterKind.IN), parameter("varchar", DBSProcedureParameterKind.OUT));
            assertEquals(List.of("SQL_NULL"), invoke(c, s + ".p",
                GaussDBDebugArguments.buildProcedure(parameters, List.of("ignored"), List.of("NULL"))));
            assertEquals(List.of("NULL"), invoke(c, s + ".p",
                GaussDBDebugArguments.buildProcedure(parameters, List.of("NULL"), List.of("VALUE"))));
        });
    }

    @Test
    void quotedRoutineAndUntrustedTextRemainData() throws Exception {
        inIsolatedSchema((c, s) -> {
            String name = s + ".\"Mixed Case过程\"";
            execute(c, "CREATE PROCEDURE " + name + "(x IN varchar, y OUT varchar) AS BEGIN y := x; END;");
            String value = "中文'; DROP SCHEMA other CASCADE; --";
            var plan = GaussDBDebugArguments.buildProcedure(List.of(parameter("varchar", DBSProcedureParameterKind.IN),
                parameter("varchar", DBSProcedureParameterKind.OUT)), List.of(value), List.of());
            assertEquals(List.of(value), invoke(c, name, plan));
        });
    }

    @Test
    void overloadedProceduresResolveByExplicitInputType() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE PROCEDURE " + s + ".p(x IN integer, y OUT varchar) AS BEGIN y := 'integer'; END;");
            try {
                execute(c, "CREATE PROCEDURE " + s + ".p(x IN varchar, y OUT varchar) AS BEGIN y := 'varchar'; END;");
            } catch (java.sql.SQLException e) {
                assumeTrue(!"42723".equals(e.getSQLState()),
                    "Server rejects standalone procedure overload (42723); overload resolution was NOT tested");
                throw e;
            }
            for (String type : List.of("integer", "varchar")) {
                var plan = GaussDBDebugArguments.buildProcedure(List.of(parameter(type, DBSProcedureParameterKind.IN),
                    parameter("varchar", DBSProcedureParameterKind.OUT)), List.of("1"), List.of());
                assertEquals(List.of(type), invoke(c, s + ".p", plan));
            }
        });
    }

    @Test
    void functionInvocationStillUsesInputOnlyPlan() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE FUNCTION " + s + ".f(x integer) RETURN integer AS BEGIN RETURN x + 1; END;");
            var plan = GaussDBDebugArguments.build(List.of(parameter("integer", DBSProcedureParameterKind.IN)),
                List.of("19"), List.of());
            try (var statement = c.prepareStatement("SELECT " + s + ".f(" + plan.sql() + ")")) {
                statement.setQueryTimeout(15);
                statement.setString(1, plan.values().getFirst());
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(20, result.getInt(1));
                }
            }
        });
    }

    @Test
    void vendorCatalogVectorsAndArraysUseProductionUnwrapping() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE FUNCTION " + s
                + ".vector_probe(x numeric, y integer) RETURN numeric AS BEGIN RETURN x + y; END;");
            var source = mock(org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDataSource.class);
            var constructor = org.jkiss.dbeaver.ext.gaussdb.model.PostgreServerGaussDB.class
                .getDeclaredConstructor(org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource.class);
            constructor.setAccessible(true);
            var server = (org.jkiss.dbeaver.ext.gaussdb.model.PostgreServerGaussDB) constructor.newInstance(source);
            when(source.getServerType()).thenReturn(server);
            try (var query = c.prepareStatement("SELECT proargtypes FROM pg_proc p JOIN pg_namespace n"
                + " ON n.oid=p.pronamespace WHERE n.nspname=? AND p.proname='vector_probe'")) {
                query.setString(1, s);
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    Object vector = rows.getObject(1);
                    assertTrue(server.isPGObject(vector), "Vendor catalog PGobject must be recognized");
                    assertArrayEquals(new long[]{1700, 23},
                        org.jkiss.dbeaver.ext.postgresql.PostgreUtils.getIdVector(vector, source));
                    assertArrayEquals(new int[]{1700, 23},
                        org.jkiss.dbeaver.ext.postgresql.PostgreUtils.getIntVector(vector, source));
                }
            }
            try (var query = c.createStatement(); var rows = query.executeQuery("SELECT ARRAY[23,1700]::bigint[]")) {
                assertTrue(rows.next());
                var array = rows.getArray(1);
                try {
                    assertTrue(server.isPGArray(array), "Vendor PgArray must be recognized");
                    assertArrayEquals(new long[]{23, 1700},
                        org.jkiss.dbeaver.ext.postgresql.PostgreUtils.getIdVector(array, source));
                } finally {
                    array.free();
                }
            }
            assertFalse(server.isPGObject(new Object()));
            assertFalse(server.isPGArray(new Object()));
        });
    }
    @Test
    void generatedInsertRoundTripsDistributedValues() throws Exception {
        assertGeneratedInsertRoundTrip(System.getenv("GAUSSDB_HISTORY_CONNECTION"));
    }

    @Test
    void generatedInsertRoundTripsCentralizedValues() throws Exception {
        assertGeneratedInsertRoundTrip(System.getenv("GAUSSDB_HISTORY_CENTRAL_CONNECTION"));
    }

    @Test
    void xlsxPreservesVendorJdbcValuesFromDistributedDatabase() throws Exception {
        assertXlsxJdbcRoundTrip(System.getenv("GAUSSDB_HISTORY_CONNECTION"));
    }

    @Test
    void xlsxPreservesVendorJdbcValuesFromCentralizedDatabase() throws Exception {
        assertXlsxJdbcRoundTrip(System.getenv("GAUSSDB_HISTORY_CENTRAL_CONNECTION"));
    }

    private void assertXlsxJdbcRoundTrip(String config) throws Exception {
        inIsolatedSchema((connection, schema) -> {
            String table = schema + ".xlsx_values";
            execute(connection, "CREATE TABLE " + table + "(n numeric(38,18), b bigint, v text, z integer)");
            var decimal = new java.math.BigDecimal("12345678901234567890.123456789012345678");
            String text = "中文𠀀'引号\n=1+1";
            try (var insert = connection.prepareStatement("INSERT INTO " + table + " VALUES(?,?,?,?)")) {
                insert.setBigDecimal(1, decimal);
                insert.setLong(2, Long.MIN_VALUE);
                insert.setString(3, text);
                insert.setNull(4, java.sql.Types.INTEGER);
                assertEquals(1, insert.executeUpdate());
            }
            var output = new java.io.ByteArrayOutputStream();
            var site = mock(org.jkiss.dbeaver.tools.transfer.stream.IStreamDataExporterSite.class);
            when(site.getProperties()).thenReturn(org.jkiss.dbeaver.data.office.export.DataExporterXLSX.getDefaultProperties());
            when(site.getOutputStream()).thenReturn(output);
            when(site.getSource()).thenReturn(mock(org.jkiss.dbeaver.model.DBPNamedObject.class));
            when(site.getExportFormat()).thenReturn(org.jkiss.dbeaver.model.data.DBDDisplayFormat.NATIVE);
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT n,b,v,z FROM " + table)) {
                assertTrue(rows.next());
                Object[] values = new Object[4];
                var bindings = new org.jkiss.dbeaver.model.data.DBDAttributeBinding[4];
                for (int i = 0; i < 4; i++) {
                    values[i] = rows.getObject(i + 1);
                    bindings[i] = mock(org.jkiss.dbeaver.model.data.DBDAttributeBinding.class);
                    when(bindings[i].getName()).thenReturn(rows.getMetaData().getColumnName(i + 1));
                    when(bindings[i].getDataKind()).thenReturn(i == 2
                        ? org.jkiss.dbeaver.model.DBPDataKind.STRING : org.jkiss.dbeaver.model.DBPDataKind.NUMERIC);
                    when(bindings[i].getValueHandler()).thenReturn(
                        org.jkiss.dbeaver.model.impl.jdbc.data.handlers.JDBCStringValueHandler.INSTANCE);
                }
                assertEquals(decimal, values[0]);
                assertEquals(Long.MIN_VALUE, values[1]);
                assertEquals(text, values[2]);
                assertNull(values[3]);
                assertFalse(rows.next());
                when(site.getAttributes()).thenReturn(bindings);
                var exporter = new org.jkiss.dbeaver.data.office.export.DataExporterXLSX();
                exporter.init(site);
                try {
                    exporter.exportHeader(mock(org.jkiss.dbeaver.model.exec.DBCSession.class));
                    exporter.exportRow(null, null, values);
                    exporter.exportFooter(new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor());
                } finally {
                    exporter.dispose();
                }
            }
            try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(output.toByteArray()))) {
                var sheet = workbook.getSheetAt(0);
                assertEquals(2, sheet.getPhysicalNumberOfRows());
                assertEquals(4, sheet.getRow(1).getPhysicalNumberOfCells());
                String[] expected = {decimal.toString(), Long.toString(Long.MIN_VALUE), text, ""};
                String[] names = {"n", "b", "v", "z"};
                for (int i = 0; i < 4; i++) {
                    assertEquals(names[i], sheet.getRow(0).getCell(i).getStringCellValue());
                    assertEquals(org.apache.poi.ss.usermodel.CellType.STRING, sheet.getRow(1).getCell(i).getCellType());
                    assertEquals(expected[i], sheet.getRow(1).getCell(i).getStringCellValue());
                }
            }
        }, java.util.Map.of(), config);
    }

    private void assertGeneratedInsertRoundTrip(String config) throws Exception {
        assertGeneratedInsertRoundTrip(config, false);
    }

    @Test
    void generatedKeylessDeleteExecutesAndRollsBackDistributed() throws Exception {
        assertGeneratedInsertRoundTrip(System.getenv("GAUSSDB_HISTORY_CONNECTION"), true);
    }

    @Test
    void generatedKeylessDeleteExecutesAndRollsBackCentralized() throws Exception {
        assertGeneratedInsertRoundTrip(System.getenv("GAUSSDB_HISTORY_CENTRAL_CONNECTION"), true);
    }

    private void assertGeneratedInsertRoundTrip(String config, boolean verifyDelete) throws Exception {
        assertGeneratedInsertRoundTrip(config, verifyDelete, false);
    }

    @Test
    void generatedUpdateExecutesAndRollsBackDistributed() throws Exception {
        assertGeneratedInsertRoundTrip(System.getenv("GAUSSDB_HISTORY_CONNECTION"), false, true);
    }

    @Test
    void generatedUpdateExecutesAndRollsBackCentralized() throws Exception {
        assertGeneratedInsertRoundTrip(System.getenv("GAUSSDB_HISTORY_CENTRAL_CONNECTION"), false, true);
    }

    private void assertGeneratedInsertRoundTrip(String config, boolean verifyDelete, boolean verifyUpdate) throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "SET search_path TO " + s);
            execute(c, "CREATE TABLE \"订单 表\" (\"first col\" text, \"second col\" text)");
            execute(c, "CREATE TABLE reference_rows(id text, value text)");
            var source = mock(org.jkiss.dbeaver.model.DBPDataSource.class,
                withSettings().extraInterfaces(org.jkiss.dbeaver.model.data.DBDValueHandlerProvider.class));
            var container = mock(org.jkiss.dbeaver.model.DBPDataSourceContainer.class);
            when(source.getContainer()).thenReturn(container);
            when(source.getSQLDialect()).thenReturn(new GaussDBDialect());
            when(container.getPreferenceStore()).thenReturn(mock(org.jkiss.dbeaver.model.preferences.DBPPreferenceStore.class));
            when(((org.jkiss.dbeaver.model.data.DBDValueHandlerProvider) source).getValueHandler(any(), any(), any()))
                .thenReturn(org.jkiss.dbeaver.model.impl.jdbc.data.handlers.JDBCStringValueHandler.INSTANCE);
            var entity = mock(org.jkiss.dbeaver.model.struct.DBSEntity.class);
            when(entity.getDataSource()).thenReturn(source);
            when(entity.getName()).thenReturn("订单 表");
            var bindings = new org.jkiss.dbeaver.model.data.DBDAttributeBinding[2];
            for (int i = 0; i < bindings.length; i++) {
                var binding = mock(org.jkiss.dbeaver.model.data.DBDAttributeBinding.class);
                bindings[i] = binding;
                when(binding.getDataSource()).thenReturn(source);
                when(binding.getName()).thenReturn(i == 0 ? "first col" : "second col");
                when(binding.getDataKind()).thenReturn(org.jkiss.dbeaver.model.DBPDataKind.STRING);
                when(binding.getAttribute()).thenReturn(binding);
                when(binding.getFullyQualifiedName(any())).thenCallRealMethod();
                when(binding.getFullyQualifiedName(any(), any())).thenCallRealMethod();
                when(binding.matches(binding, true)).thenReturn(true);
            }
            var provider = mock(org.jkiss.dbeaver.model.data.DBDResultSetDataProvider.class);
            when(provider.getSingleSource()).thenReturn(entity);
            when(provider.getAttributes()).thenReturn(bindings);
            when(provider.getVisibleAttributes()).thenReturn(List.of(bindings[1], bindings[0]));
            var implementation = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.model.sql")
                .loadClass("org.jkiss.dbeaver.model.sql.generator.resultset.SQLGeneratorInsertFromData");
            var generator = implementation.getConstructor().newInstance();
            implementation.getMethod("setFullyQualifiedNames", boolean.class).invoke(generator, false);
            implementation.getMethod("setCompactSQL", boolean.class).invoke(generator, true);
            var generate = implementation.getDeclaredMethod("generateSQL", DBRProgressMonitor.class,
                StringBuilder.class, org.jkiss.dbeaver.model.data.DBDResultSetDataProvider.class);
            generate.setAccessible(true);
            String[] values = {null, "", "NULL", "O'Reilly", "中文数据", "line1\nline2", "x'); DROP TABLE reference_rows; --"};
            for (int i = 0; i < values.length; i++) {
                String id = Integer.toString(i);
                var row = mock(org.jkiss.dbeaver.model.data.DBDValueRow.class);
                doReturn(List.of(row)).when(provider).getSelectedRows();
                when(provider.getCellValue(bindings[0], row)).thenReturn(id);
                when(provider.getCellValue(bindings[1], row)).thenReturn(values[i]);
                var sql = new StringBuilder();
                generate.invoke(generator, new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(), sql, provider);
                assertTrue(sql.toString().startsWith("INSERT INTO \"订单 表\" (\"second col\", \"first col\") VALUES("));
                execute(c, sql.toString());
                try (var insert = c.prepareStatement("INSERT INTO reference_rows VALUES (?, ?)")) {
                    insert.setString(1, id);
                    insert.setString(2, values[i]);
                    assertEquals(1, insert.executeUpdate());
                }
            }
            try (var statement = c.createStatement(); var rows = statement.executeQuery(
                "SELECT a.\"first col\", a.\"second col\", b.value FROM \"订单 表\" a "
                    + "JOIN reference_rows b ON a.\"first col\"=b.id ORDER BY a.\"first col\"")) {
                for (int i = 0; i < values.length; i++) {
                    assertTrue(rows.next());
                    assertEquals(Integer.toString(i), rows.getString(1));
                    assertEquals(rows.getString(3), rows.getString(2), "Generated literal must match bound JDBC value");
                    if (!"".equals(values[i])) {
                        assertEquals(values[i], rows.getString(2));
                    }
                }
                assertFalse(rows.next());
            }
            assertEquals(values.length, count(c, "\"订单 表\""));
            assertEquals(values.length, count(c, "reference_rows"));
            if (verifyUpdate) {
                var updateClass = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.model.sql")
                    .loadClass("org.jkiss.dbeaver.model.sql.generator.resultset.SQLGeneratorUpdateFromData");
                var updateGenerator = updateClass.getConstructor().newInstance();
                updateClass.getMethod("initGenerator", List.class).invoke(updateGenerator, List.of(provider));
                updateClass.getMethod("setFullyQualifiedNames", boolean.class).invoke(updateGenerator, false);
                updateClass.getMethod("setCompactSQL", boolean.class).invoke(updateGenerator, true);
                var generateUpdate = updateClass.getDeclaredMethod("generateSQL", DBRProgressMonitor.class,
                    StringBuilder.class, org.jkiss.dbeaver.model.data.DBDResultSetDataProvider.class);
                generateUpdate.setAccessible(true);
                var identifier = mock(org.jkiss.dbeaver.model.data.DBDRowIdentifier.class);
                when(identifier.getAttributes()).thenReturn(List.of(bindings[0]));
                when(provider.getDefaultRowIdentifier()).thenReturn(identifier);
                String[] expected = new String[values.length];
                // Capture the server's empty-string normalization, already compared to bound JDBC above.
                try (var statement = c.createStatement(); var rows = statement.executeQuery(
                    "SELECT value FROM reference_rows ORDER BY id")) {
                    for (int i = 0; i < expected.length; i++) {
                        assertTrue(rows.next());
                        expected[i] = rows.getString(1);
                    }
                    assertFalse(rows.next());
                }
                for (String value : new String[] {null, "NULL", "O'Reilly", "中文\n第二行", "x'); DROP TABLE reference_rows; --"}) {
                    var row = mock(org.jkiss.dbeaver.model.data.DBDValueRow.class);
                    doReturn(List.of(row)).when(provider).getSelectedRows();
                    when(provider.getCellValue(bindings[0], row)).thenReturn("4");
                    when(provider.getCellValue(bindings[1], row)).thenReturn(value);
                    var sql = new StringBuilder();
                    generateUpdate.invoke(updateGenerator, new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(), sql, provider);
                    c.setAutoCommit(false);
                    try (var statement = c.createStatement()) {
                        assertEquals(1, statement.executeUpdate(sql.toString()));
                    }
                    var changed = expected.clone();
                    changed[4] = value;
                    assertGeneratedTextRows(c, changed);
                    c.rollback();
                    assertGeneratedTextRows(c, expected);
                    try (var statement = c.createStatement()) {
                        assertEquals(1, statement.executeUpdate(sql.toString()));
                    }
                    c.commit();
                    c.setAutoCommit(true);
                    expected = changed;
                    assertGeneratedTextRows(c, expected);
                    assertEquals(values.length, count(c, "reference_rows"));
                }
            }
            if (verifyDelete) {
                var deleteClass = org.eclipse.core.runtime.Platform.getBundle("org.jkiss.dbeaver.model.sql")
                    .loadClass("org.jkiss.dbeaver.model.sql.generator.resultset.SQLGeneratorDeleteFromData");
                var deleteGenerator = deleteClass.getConstructor().newInstance();
                deleteClass.getMethod("initGenerator", List.class).invoke(deleteGenerator, List.of(provider));
                deleteClass.getMethod("setFullyQualifiedNames", boolean.class).invoke(deleteGenerator, false);
                deleteClass.getMethod("setCompactSQL", boolean.class).invoke(deleteGenerator, true);
                var generateDelete = deleteClass.getDeclaredMethod("generateSQL", DBRProgressMonitor.class,
                    StringBuilder.class, org.jkiss.dbeaver.model.data.DBDResultSetDataProvider.class);
                generateDelete.setAccessible(true);
                // No key: identical visible values intentionally match both duplicate rows.
                execute(c, "INSERT INTO \"订单 表\" SELECT * FROM \"订单 表\" WHERE \"first col\"='3'");
                assertEquals(8, count(c, "\"订单 表\""));
                int remaining = 8;
                for (int index : new int[] {3, 0, 6}) {
                    var row = mock(org.jkiss.dbeaver.model.data.DBDValueRow.class);
                    doReturn(List.of(row)).when(provider).getSelectedRows();
                    when(provider.getCellValue(bindings[0], row)).thenReturn(Integer.toString(index));
                    when(provider.getCellValue(bindings[1], row)).thenReturn(values[index]);
                    var sql = new StringBuilder();
                    generateDelete.invoke(deleteGenerator, new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(), sql, provider);
                    if (index == 0) {
                        assertTrue(sql.toString().contains("\"second col\" IS NULL"));
                    }
                    int affected = index == 3 ? 2 : 1;
                    c.setAutoCommit(false);
                    try (var statement = c.createStatement()) {
                        assertEquals(affected, statement.executeUpdate(sql.toString()));
                    }
                    assertEquals(remaining - affected, count(c, "\"订单 表\""));
                    c.rollback();
                    assertEquals(remaining, count(c, "\"订单 表\""));
                    try (var statement = c.createStatement()) {
                        assertEquals(affected, statement.executeUpdate(sql.toString()));
                    }
                    c.commit();
                    c.setAutoCommit(true);
                    remaining -= affected;
                    assertEquals(remaining, count(c, "\"订单 表\""));
                    assertEquals(values.length, count(c, "reference_rows"));
                }
                try (var statement = c.createStatement(); var rows = statement.executeQuery(
                    "SELECT \"first col\" FROM \"订单 表\" ORDER BY \"first col\"")) {
                    for (String expected : List.of("1", "2", "4", "5")) {
                        assertTrue(rows.next());
                        assertEquals(expected, rows.getString(1));
                    }
                    assertFalse(rows.next());
                }
            }
        }, java.util.Map.of(), config);
    }

    private void assertGeneratedTextRows(Connection connection, String[] expected) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT \"first col\", \"second col\" FROM \"订单 表\" ORDER BY \"first col\"")) {
            for (int i = 0; i < expected.length; i++) {
                assertTrue(rows.next());
                assertEquals(Integer.toString(i), rows.getString(1));
                assertEquals(expected[i], rows.getString(2), "Unexpected value at row " + i);
            }
            assertFalse(rows.next());
        }
    }

    @Test
    void extractedEscapeStringsExecuteDistributed() throws Exception {
        assertExtractedEscapeStrings(System.getenv("GAUSSDB_HISTORY_CONNECTION"));
    }

    @Test
    void extractedEscapeStringsExecuteCentralized() throws Exception {
        assertExtractedEscapeStrings(System.getenv("GAUSSDB_HISTORY_CENTRAL_CONNECTION"));
    }

    private void assertExtractedEscapeStrings(String config) throws Exception {
        inIsolatedSchema((connection, schema) -> {
            String[][] cases = {
                {"E'a;b'", "a;b"}, {"E'a'';b'", "a';b"}, {"E'it\\'s;still text'", "it's;still text"},
                {"E'-- ; not comment'", "-- ; not comment"}, {"E'/* ; END; */'", "/* ; END; */"},
                {"E'line1\\nline2;中文'", "line1\nline2;中文"}, {"E'line1\nline2;中文'", "line1\nline2;中文"}
            };
            var dialect = new GaussDBDialect();
            var source = mock(org.jkiss.dbeaver.model.DBPDataSource.class);
            var container = mock(org.jkiss.dbeaver.model.DBPDataSourceContainer.class);
            var preferences = mock(org.jkiss.dbeaver.model.preferences.DBPPreferenceStore.class);
            when(source.getContainer()).thenReturn(container);
            when(source.getSQLDialect()).thenReturn(dialect);
            when(container.getPreferenceStore()).thenReturn(preferences);
            when(container.getActualConnectionConfiguration())
                .thenReturn(new org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration());
            when(preferences.getBoolean(org.jkiss.dbeaver.ModelPreferences.QUERY_REMOVE_TRAILING_DELIMITER)).thenReturn(true);
            for (String[] test : cases) {
                String query = "SELECT " + test[0];
                String script = "SELECT 0;\r\n" + query + ";\nSELECT 2;";
                var context = org.jkiss.dbeaver.model.sql.parser.SQLScriptParser
                    .prepareSqlParserContext(source, dialect, preferences, script);
                var queries = org.jkiss.dbeaver.model.sql.parser.SQLScriptParser
                    .extractScriptQueries(context, 0, script.length(), false, false, false);
                assertEquals(3, queries.size());
                String[] expected = {"0", test[1], "2"};
                for (int i = 0; i < queries.size(); i++) {
                    try (var statement = connection.createStatement(); var rows = statement.executeQuery(queries.get(i).getText())) {
                        assertTrue(rows.next());
                        assertEquals(expected[i], rows.getString(1));
                        assertFalse(rows.next());
                    }
                }
            }
        }, java.util.Map.of(), config);
    }

    @FunctionalInterface
    private interface Scenario {
        void run(Connection connection, String schema) throws Exception;
    }

    private void inIsolatedSchema(Scenario scenario) throws Exception {
        inIsolatedSchema(scenario, java.util.Map.of());
    }

    private void inIsolatedSchema(Scenario scenario, java.util.Map<String, String> driverOptions) throws Exception {
        inIsolatedSchema(scenario, driverOptions, System.getenv("GAUSSDB_HISTORY_CONNECTION"));
    }

    private void inIsolatedSchema(Scenario scenario, java.util.Map<String, String> driverOptions, String config) throws Exception {
        assumeTrue(config != null, "Live connection not configured; not a passing database test");
        assertEquals("YES", System.getenv("GAUSSDB_HISTORY_ALLOW_DDL"), "Explicit isolated test database consent required");
        String jar = System.getenv("GAUSSDB_HISTORY_JDBC");
        assertNotNull(jar, "Vendor JDBC jar required");
        var properties = new Properties();
        try (var input = Files.newInputStream(Path.of(config))) {
            properties.load(input);
        }
        String url = properties.getProperty("url");
        String driverClass = properties.getProperty("driverClass");
        assertNotNull(url);
        assertNotNull(driverClass);
        properties.remove("url");
        properties.remove("driverClass");
        properties.setProperty("socketTimeout", "20");
        properties.setProperty("connectTimeout", "10");
        properties.putAll(driverOptions);
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()},
            ClassLoader.getPlatformClassLoader())) {
            var driver = (Driver) loader.loadClass(driverClass).getConstructor().newInstance();
            try (Connection connection = driver.connect(url, properties)) {
                assertNotNull(connection, "Driver did not accept URL");
                connection.setAutoCommit(true);
                String schema = "dbv_hist_" + UUID.randomUUID().toString().replace("-", "");
                execute(connection, "CREATE SCHEMA " + schema);
                Throwable scenarioFailure = null;
                try {
                    scenario.run(connection, schema);
                } catch (Exception | AssertionError failure) {
                    scenarioFailure = failure;
                    throw failure;
                } finally {
                    try {
                        if (connection.isClosed()) {
                            withIndependentConnection(cleanup -> execute(cleanup, "DROP SCHEMA " + schema + " CASCADE"),
                                null, java.util.Map.of(), config);
                        } else {
                            if (!connection.getAutoCommit()) {
                                connection.rollback();
                                connection.setAutoCommit(true);
                            }
                            // Only the random schema successfully created by this test is removed.
                            execute(connection, "DROP SCHEMA " + schema + " CASCADE");
                        }
                    } catch (Exception cleanupFailure) {
                        if (scenarioFailure == null) {
                            throw cleanupFailure;
                        }
                        scenarioFailure.addSuppressed(cleanupFailure);
                    }
                }
            }
        }
    }

    @Test
    void viewRenamePreservesDefinitionDataAndProductionSearchIdentity() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".base_rows(id integer, label text)");
            execute(c, "INSERT INTO " + s + ".base_rows VALUES(1,'中文'),(2,'second')");
            execute(c, "CREATE VIEW " + s + ".old_view AS SELECT id,label FROM " + s + ".base_rows WHERE id=1");
            execute(c, "ALTER VIEW " + s + ".old_view RENAME TO \"Renamed View\"");
            assertTrue(searchTables(c, s, "old_view", true, 10, false).isEmpty());
            var found = searchTables(c, s, "Renamed View", true, 10, false);
            assertEquals(1, found.size());
            assertEquals("PostgreView", found.get(0).getObjectClass().getSimpleName());
            try (var query = c.createStatement(); var rows = query.executeQuery("SELECT id,label FROM " + s + ".\"Renamed View\"")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertEquals("中文", rows.getString(2));
                assertFalse(rows.next());
            }
            assertEquals(2, count(c, s + ".base_rows"));
        });
    }

    @Test
    void productionViewManagerWrapsCreateLiteralAndExecutesItsGeneratedDdl() throws Exception {
        inIsolatedSchema((c, s) -> {
            var source = mock(GaussDBDataSource.class);
            when(source.getSQLDialect()).thenReturn(new GaussDBDialect());
            var view = mock(org.jkiss.dbeaver.ext.postgresql.model.PostgreView.class);
            when(view.getDataSource()).thenReturn(source);
            when(view.getTableTypeName()).thenReturn("VIEW");
            when(view.getFullyQualifiedName(org.jkiss.dbeaver.model.DBPEvaluationContext.DDL)).thenReturn(s + ".created_view");
            when(view.getObjectDefinitionText(any(), anyMap())).thenReturn("/* create marker */ SELECT 'create'::text AS label");
            var actions = new java.util.ArrayList<org.jkiss.dbeaver.model.edit.DBEPersistAction>();
            var manager = new org.jkiss.dbeaver.ext.postgresql.edit.PostgreViewManager() {
                void build() throws Exception {
                    createOrReplaceViewQuery(new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor(), actions, view, java.util.Map.of());
                }
            };
            manager.build();
            assertEquals(1, actions.size());
            execute(c, actions.get(0).getScript());
            try (var query = c.createStatement(); var rows = query.executeQuery("SELECT label FROM " + s + ".created_view")) {
                assertTrue(rows.next());
                assertEquals("create", rows.getString(1));
                assertFalse(rows.next());
            }
            assertEquals(1, searchTables(c, s, "created_view", true, 10, false).size());
        });
    }

    @Test
    void viewDependencyRejectsRestrictAndCascadeRemovesOnlyDependentViews() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".base_rows(id integer)");
            execute(c, "INSERT INTO " + s + ".base_rows VALUES(7)");
            execute(c, "CREATE VIEW " + s + ".first_view AS SELECT id FROM " + s + ".base_rows");
            execute(c, "CREATE VIEW " + s + ".dependent_view AS SELECT id FROM " + s + ".first_view");
            execute(c, "CREATE VIEW " + s + ".independent_view AS SELECT id FROM " + s + ".base_rows");
            var error = assertThrows(java.sql.SQLException.class, () -> execute(c, "DROP VIEW " + s + ".first_view RESTRICT"));
            assertEquals("2BP01", error.getSQLState());
            assertEquals(1, count(c, s + ".dependent_view"));
            execute(c, "DROP VIEW " + s + ".first_view CASCADE");
            assertTrue(searchTables(c, s, "first_view", true, 10, false).isEmpty());
            assertTrue(searchTables(c, s, "dependent_view", true, 10, false).isEmpty());
            assertEquals(1, count(c, s + ".independent_view"));
            assertEquals(1, count(c, s + ".base_rows"));
        });
    }

    @Test
    void invalidViewReplacementCanRollbackToSavepointWithoutLosingOriginalDefinition() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".base_rows(id integer)");
            execute(c, "CREATE VIEW " + s + ".stable_view AS SELECT id FROM " + s + ".base_rows");
            c.setAutoCommit(false);
            execute(c, "INSERT INTO " + s + ".base_rows VALUES(8)");
            var checkpoint = c.setSavepoint("before_view_change");
            var error = assertThrows(java.sql.SQLException.class, () -> execute(c,
                "CREATE OR REPLACE VIEW " + s + ".stable_view AS SELECT missing_column FROM " + s + ".base_rows"));
            assertEquals("42703", error.getSQLState());
            c.rollback(checkpoint);
            assertEquals(1, count(c, s + ".stable_view"));
            c.commit();
            assertObserverCount(s + ".stable_view", 1);
        });
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            statement.execute(sql);
        }
    }

    private static int count(Connection c, String table) throws Exception {
        try (var statement = c.createStatement()) {
            statement.setQueryTimeout(15);
            try (var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static void assertObserverCount(String table, int expected) throws Exception {
        withIndependentConnection(observer -> assertEquals(expected, count(observer, table)));
    }

    @FunctionalInterface
    private interface ConnectionScenario {
        void run(Connection connection) throws Exception;
    }

    private static void withIndependentConnection(ConnectionScenario scenario) throws Exception {
        withIndependentConnection(scenario, null);
    }

    private static void withIndependentConnection(ConnectionScenario scenario, String database) throws Exception {
        withIndependentConnection(scenario, database, java.util.Map.of());
    }

    private static void withIndependentConnection(ConnectionScenario scenario, String database,
        java.util.Map<String, String> overrides) throws Exception {
        withIndependentConnection(scenario, database, overrides, System.getenv("GAUSSDB_HISTORY_CONNECTION"));
    }

    private static void withIndependentConnection(ConnectionScenario scenario, String database,
        java.util.Map<String, String> overrides, String config) throws Exception {
        var p = new Properties();
        try (var input = Files.newInputStream(Path.of(config))) {
            p.load(input);
        }
        p.setProperty("socketTimeout", "20");
        p.setProperty("connectTimeout", "10");
        p.putAll(overrides);
        String url = p.getProperty("url");
        if (database != null) {
            assertTrue(database.matches("[a-z0-9_]+"), "Dedicated test database name required");
            var uri = java.net.URI.create(url.substring("jdbc:".length()));
            url = "jdbc:" + new java.net.URI(uri.getScheme(), uri.getAuthority(), "/" + database,
                uri.getQuery(), uri.getFragment()).toASCIIString();
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(System.getenv("GAUSSDB_HISTORY_JDBC")).toUri().toURL()},
            ClassLoader.getPlatformClassLoader())) {
            var driver = (Driver) loader.loadClass(p.getProperty("driverClass")).getConstructor().newInstance();
            try (var observer = driver.connect(url, p)) {
                assertNotNull(observer);
                scenario.run(observer);
            }
        }
    }

    @Test
    void strictTlsRejectsTrustedCertificateWithWrongHostname() throws Exception {
        String certificate = System.getenv("GAUSSDB_HISTORY_TLS_WRONG_HOST_CA");
        assumeTrue(certificate != null, "Dedicated trusted but wrong-host server certificate required");
        assertTrue(Files.isRegularFile(Path.of(certificate)));
        inIsolatedSchema((c, s) -> {
            withIndependentConnection(connection -> assertRows(connection, "SELECT 1", List.of(List.of("1"))),
                null, java.util.Map.of("sslmode", "verify-ca", "sslrootcert", certificate));
            var failure = assertThrows(java.sql.SQLException.class, () ->
                withIndependentConnection(connection -> fail("Wrong hostname must not connect in verify-full mode"),
                    null, java.util.Map.of("sslmode", "verify-full", "sslrootcert", certificate)));
            assertTrue(failure.getSQLState() != null && failure.getSQLState().startsWith("08"));
            assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("hostname"),
                "Expected hostname verification rejection");
            withIndependentConnection(connection -> assertRows(connection, "SELECT 2", List.of(List.of("2"))),
                null, java.util.Map.of("sslmode", "verify-ca", "sslrootcert", certificate));
        });
    }

    @Test
    void strictTlsRejectsExistingUnrelatedCertificateWithoutDowngrade() throws Exception {
        String trusted = System.getenv("GAUSSDB_HISTORY_TLS_CA");
        String unrelated = System.getenv("GAUSSDB_HISTORY_TLS_UNRELATED_CA");
        assumeTrue(trusted != null && unrelated != null, "Dedicated trusted and unrelated TLS certificates required");
        assertTrue(Files.isRegularFile(Path.of(trusted)));
        assertTrue(Files.isRegularFile(Path.of(unrelated)));
        assertFalse(java.util.Arrays.equals(Files.readAllBytes(Path.of(trusted)), Files.readAllBytes(Path.of(unrelated))));
        inIsolatedSchema((c, s) -> {
            withIndependentConnection(connection -> assertRows(connection, "SELECT 1", List.of(List.of("1"))),
                null, java.util.Map.of("sslmode", "verify-full", "sslrootcert", trusted));
            var failure = assertThrows(java.sql.SQLException.class, () ->
                withIndependentConnection(connection -> fail("Untrusted server must not connect"),
                    null, java.util.Map.of("sslmode", "verify-full", "sslrootcert", unrelated)));
            assertTrue(failure.getSQLState() != null && failure.getSQLState().startsWith("08"));
            boolean handshakeFailure = false;
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                handshakeFailure |= cause instanceof javax.net.ssl.SSLHandshakeException;
            }
            assertTrue(handshakeFailure, "Expected certificate handshake rejection, not an unrelated connection failure");
            withIndependentConnection(connection -> assertRows(connection, "SELECT 2", List.of(List.of("2"))),
                null, java.util.Map.of("sslmode", "verify-full", "sslrootcert", trusted));
        });
    }

    @Test
    void strictTlsConnectsWithTrustedCertificateAndRejectsMissingTrustFile() throws Exception {
        String certificate = System.getenv("GAUSSDB_HISTORY_TLS_CA");
        assumeTrue(certificate != null && Files.isRegularFile(Path.of(certificate)), "Dedicated TLS CA required");
        inIsolatedSchema((c, s) -> {
            try (var statement = c.createStatement(); var rows = statement.executeQuery("SHOW ssl")) {
                assertTrue(rows.next());
                assertEquals("on", rows.getString(1));
            }
            withIndependentConnection(connection -> {
                assertTrue(connection.isValid(5));
                assertRows(connection, "SELECT '中文 TLS', 42", List.of(List.of("中文 TLS", "42")));
            }, null, java.util.Map.of("sslmode", "verify-full", "sslrootcert", certificate));
            var failure = assertThrows(java.sql.SQLException.class, () ->
                withIndependentConnection(connection -> fail("Missing trust must not fall back to plaintext"),
                    null, java.util.Map.of("sslmode", "verify-full", "sslrootcert", certificate + ".missing")));
            assertTrue(failure.getSQLState() != null && failure.getSQLState().startsWith("08"));
            assertRows(c, "SELECT 1", List.of(List.of("1")));
        });
    }

    @Test
    void requiredSslDoesNotSilentlyDowngradeWhenServerSslIsDisabled() throws Exception {
        inIsolatedSchema((c, s) -> {
            try (var statement = c.createStatement(); var rows = statement.executeQuery("SHOW ssl")) {
                assertTrue(rows.next());
                assumeTrue("off".equalsIgnoreCase(rows.getString(1)), "Requires the SSL-disabled test endpoint");
            }
            var failure = assertThrows(java.sql.SQLException.class, () ->
                withIndependentConnection(connection -> fail("Required SSL must not connect without TLS"),
                    null, java.util.Map.of("sslmode", "require")));
            assertEquals("08004", failure.getSQLState());
            // 'prefer' explicitly permits plaintext fallback; it is not proof of encryption.
            withIndependentConnection(connection -> {
                assertTrue(connection.isValid(5));
                assertRows(connection, "SELECT 1", List.of(List.of("1")));
            }, null, java.util.Map.of("sslmode", "prefer"));
            assertRows(c, "SELECT 1", List.of(List.of("1")));
        });
    }

    @Test
    void closingConnectionRollsBackUncommittedRowsAndRejectsFurtherStatements() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".close_audit";
            execute(c, "CREATE TABLE " + table + "(id integer)");
            withIndependentConnection(writer -> {
                writer.setAutoCommit(false);
                execute(writer, "INSERT INTO " + table + " VALUES(1)");
                assertEquals(1, count(writer, table));
                assertEquals(0, count(c, table));
                writer.close();
                assertTrue(writer.isClosed());
                assertThrows(java.sql.SQLException.class, writer::createStatement);
                writer.close();
            });
            assertEquals(0, count(c, table));
            assertObserverCount(table, 0);
            assertConnectionUsable(c);
        });
    }

    @Test
    void terminatedOwnSessionRollsBackAndFreshConnectionCanWrite() throws Exception {
        inIsolatedSchema((controller, schema) -> {
            String table = schema + ".terminated_audit";
            execute(controller, "CREATE TABLE " + table + "(id integer PRIMARY KEY, note text)");
            withIndependentConnection(writer -> {
                long writerPid;
                try (var statement = writer.createStatement();
                     var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                    assertTrue(result.next());
                    writerPid = result.getLong(1);
                }
                writer.setAutoCommit(false);
                execute(writer, "INSERT INTO " + table + " VALUES(1,'uncommitted')");
                assertEquals(1, count(writer, table));
                assertEquals(0, count(controller, table));
                // Only terminate the independent connection created by this test.
                try (var statement = controller.prepareStatement("SELECT pg_terminate_backend(?)")) {
                    statement.setLong(1, writerPid);
                    statement.setQueryTimeout(15);
                    try (var result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertTrue(result.getBoolean(1), "Server must acknowledge termination");
                    }
                }
                var failure = assertThrows(java.sql.SQLException.class,
                    () -> execute(writer, "SELECT 1"));
                assertTrue("57P01".equals(failure.getSQLState()) ||
                    (failure.getSQLState() != null && failure.getSQLState().startsWith("08")),
                    "Expected backend termination/connection SQLSTATE, got " + failure.getSQLState());
            });
            assertObserverCount(table, 0);
            withIndependentConnection(reconnected -> {
                execute(reconnected, "INSERT INTO " + table + " VALUES(2,'reconnected')");
                assertRows(reconnected, "SELECT id,note FROM " + table,
                    List.of(List.of("2", "reconnected")));
            });
            assertRows(controller, "SELECT id,note FROM " + table,
                List.of(List.of("2", "reconnected")));
        });
    }

    @Test
    void enablingAutocommitCommitsPendingRowsButLaterManualRollbackRemainsIsolated() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".mode_audit";
            execute(c, "CREATE TABLE " + table + "(id integer)");
            c.setAutoCommit(false);
            execute(c, "INSERT INTO " + table + " VALUES(1)");
            assertObserverCount(table, 0);
            c.setAutoCommit(true);
            assertTrue(c.getAutoCommit());
            assertObserverCount(table, 1);
            c.setAutoCommit(true);
            assertObserverCount(table, 1);
            c.setAutoCommit(false);
            execute(c, "INSERT INTO " + table + " VALUES(2)");
            assertEquals(2, count(c, table));
            assertObserverCount(table, 1);
            c.rollback();
            assertRows(c, "SELECT id FROM " + table, List.of(List.of("1")));
            assertObserverCount(table, 1);
        });
    }

    @Test
    void readOnlyTransactionRejectsWritesAndNormalConnectionRemainsWritable() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".readonly_audit";
            execute(c, "CREATE TABLE " + table + "(id integer)");
            withIndependentConnection(reader -> {
                reader.setReadOnly(true);
                reader.setAutoCommit(false);
                assertEquals(0, count(reader, table));
                var error = assertThrows(java.sql.SQLException.class,
                    () -> execute(reader, "INSERT INTO " + table + " VALUES(1)"));
                assertEquals("25006", error.getSQLState());
                reader.rollback();
                reader.setReadOnly(false);
                execute(reader, "INSERT INTO " + table + " VALUES(2)");
                reader.commit();
            });
            assertRows(c, "SELECT id FROM " + table, List.of(List.of("2")));
        });
    }

    @Test
    void commitAndRollbackAreObservedFromAnIndependentConnection() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".audit_rows";
            execute(c, "CREATE TABLE " + table + "(id integer)");
            c.setAutoCommit(false);
            execute(c, "INSERT INTO " + table + " VALUES(1)");
            assertEquals(1, count(c, table));
            assertObserverCount(table, 0);
            c.rollback();
            assertObserverCount(table, 0);
            execute(c, "INSERT INTO " + table + " VALUES(2)");
            c.commit();
            assertObserverCount(table, 1);
        });
    }

    @Test
    void savepointRollbackPreservesEarlierChanges() throws Exception {
        inIsolatedSchema((c, s) -> {
            String table = s + ".audit_rows";
            execute(c, "CREATE TABLE " + table + "(id integer)");
            c.setAutoCommit(false);
            execute(c, "INSERT INTO " + table + " VALUES(1)");
            var savepoint = c.setSavepoint();
            execute(c, "INSERT INTO " + table + " VALUES(2)");
            c.rollback(savepoint);
            assertEquals(1, count(c, table));
            c.commit();
            assertObserverCount(table, 1);
        });
    }

    @Test
    void constraintFailurePreservesSqlstateAndConnectionCanBeReused() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".t(id integer PRIMARY KEY, v integer NOT NULL)");
            execute(c, "INSERT INTO " + s + ".t VALUES(1,2)");
            var duplicate = assertThrows(java.sql.SQLException.class, () -> execute(c, "INSERT INTO " + s + ".t VALUES(1,3)"));
            assertEquals("23505", duplicate.getSQLState());
            var notNull = assertThrows(java.sql.SQLException.class, () -> execute(c, "INSERT INTO " + s + ".t VALUES(2,NULL)"));
            assertEquals("23502", notNull.getSQLState());
            assertEquals(1, count(c, s + ".t"));
        });
    }

    @Test
    void quotedTableMetadataMatchesNamesTypesAndNullability() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".\"Mixed Table\"(\"中文列\" numeric(20,4) NOT NULL, id integer)");
            try (var result = c.getMetaData().getColumns(null, s, "Mixed Table", "%")) {
                assertTrue(result.next());
                assertEquals("中文列", result.getString("COLUMN_NAME"));
                assertEquals(java.sql.Types.NUMERIC, result.getInt("DATA_TYPE"));
                assertEquals(20, result.getInt("COLUMN_SIZE"));
                assertEquals(4, result.getInt("DECIMAL_DIGITS"));
                assertEquals(java.sql.DatabaseMetaData.columnNoNulls, result.getInt("NULLABLE"));
                assertTrue(result.next());
                assertEquals("id", result.getString("COLUMN_NAME"));
                assertFalse(result.next());
            }
        });
    }

    @Test
    void viewColumnMetadataRetainsAliasesPrecisionAndOrderAfterRename() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE TABLE " + s + ".source_data(amount numeric(20,4), label varchar(40), id integer)");
            execute(c, "CREATE VIEW " + s + ".original_view AS SELECT label AS \"中文 Name\","
                + " amount AS \"Amount Value\", id AS \"Identifier\" FROM " + s + ".source_data");
            execute(c, "ALTER VIEW " + s + ".original_view RENAME TO renamed_view");
            try (var old = c.getMetaData().getColumns(null, s, "original_view", "%")) {
                assertFalse(old.next());
            }
            try (var columns = c.getMetaData().getColumns(null, s, "renamed_view", "%")) {
                assertTrue(columns.next());
                assertEquals("中文 Name", columns.getString("COLUMN_NAME"));
                assertEquals(1, columns.getInt("ORDINAL_POSITION"));
                assertEquals(java.sql.Types.VARCHAR, columns.getInt("DATA_TYPE"));
                assertEquals(40, columns.getInt("COLUMN_SIZE"));
                assertTrue(columns.next());
                assertEquals("Amount Value", columns.getString("COLUMN_NAME"));
                assertEquals(2, columns.getInt("ORDINAL_POSITION"));
                assertEquals(java.sql.Types.NUMERIC, columns.getInt("DATA_TYPE"));
                assertEquals(20, columns.getInt("COLUMN_SIZE"));
                assertEquals(4, columns.getInt("DECIMAL_DIGITS"));
                assertTrue(columns.next());
                assertEquals("Identifier", columns.getString("COLUMN_NAME"));
                assertEquals(3, columns.getInt("ORDINAL_POSITION"));
                assertEquals(java.sql.Types.INTEGER, columns.getInt("DATA_TYPE"));
                assertFalse(columns.next());
            }
            execute(c, "DROP VIEW " + s + ".renamed_view");
            try (var removed = c.getMetaData().getColumns(null, s, "renamed_view", "%")) {
                assertFalse(removed.next());
            }
        });
    }

    @Test
    void viewAndSequenceRoundTripAndDropRefreshMetadata() throws Exception {
        inIsolatedSchema((c, s) -> {
            execute(c, "CREATE SEQUENCE " + s + ".seq START WITH 7");
            execute(c, "CREATE TABLE " + s + ".t(id integer)");
            execute(c, "INSERT INTO " + s + ".t VALUES(nextval('" + s + ".seq'))");
            execute(c, "CREATE VIEW " + s + ".v AS SELECT id FROM " + s + ".t");
            try (var statement = c.createStatement()) {
                statement.setQueryTimeout(15);
                try (var result = statement.executeQuery("SELECT id FROM " + s + ".v")) {
                    assertTrue(result.next());
                    assertEquals(7, result.getInt(1));
                }
            }
            try (var result = c.getMetaData().getTables(null, s, "v", new String[]{"VIEW"})) {
                assertTrue(result.next());
            }
            execute(c, "DROP VIEW " + s + ".v");
            try (var result = c.getMetaData().getTables(null, s, "v", new String[]{"VIEW"})) {
                assertFalse(result.next());
            }
        });
    }

    @Test
    void productionArgumentsForProcedureWithOutResolveAndReturnOutput() throws Exception {
        inIsolatedSchema((connection, schema) -> {
            execute(connection, "CREATE PROCEDURE " + schema + ".p(p_id IN numeric, p_result OUT varchar) "
                + "AS BEGIN p_result := 'id=' || p_id; END;");
            var input = mock(PostgreProcedureParameter.class);
            when(input.getFullTypeName()).thenReturn("numeric");
            when(input.getParameterKind()).thenReturn(org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameterKind.IN);
            var output = mock(PostgreProcedureParameter.class);
            when(output.getFullTypeName()).thenReturn("varchar");
            when(output.getParameterKind()).thenReturn(org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameterKind.OUT);
            var plan = GaussDBDebugArguments.buildProcedure(List.of(input, output), List.of("111"), List.of("VALUE"));
            // Use the production argument builder, including positional OUT placeholders.
            try (var statement = connection.prepareStatement("CALL " + schema + ".p(" + plan.sql() + ")")) {
                statement.setQueryTimeout(15);
                statement.setString(1, plan.values().getFirst());
                assertTrue(statement.execute(), "CALL must expose its OUT result");
                try (var result = statement.getResultSet()) {
                    assertTrue(result.next());
                    assertEquals("id=111", result.getString(1));
                }
            }
        });
    }

    @Test
    void typedOutPlaceholderReturnsTheExpectedOutput() throws Exception {
        inIsolatedSchema((connection, schema) -> {
            execute(connection, "CREATE PROCEDURE " + schema + ".p(p_id IN numeric, p_result OUT varchar) "
                + "AS BEGIN p_result := 'id=' || p_id; END;");
            try (var statement = connection.prepareStatement("CALL " + schema + ".p(?::numeric,NULL::varchar)")) {
                statement.setQueryTimeout(15);
                statement.setString(1, "111");
                assertTrue(statement.execute());
                try (var result = statement.getResultSet()) {
                    assertTrue(result.next());
                    assertEquals("id=111", result.getString(1));
                }
            }
        });
    }

    @Test
    void numericUnicodeNullAndBigintRoundTripThroughVendorJdbc() throws Exception {
        inIsolatedSchema((connection, schema) -> {
            execute(connection, "CREATE TABLE " + schema + ".t(n numeric(38,18), b bigint, v varchar(100), z integer)");
            var decimal = new java.math.BigDecimal("12345678901234567890.123456789012345678");
            try (var insert = connection.prepareStatement("INSERT INTO " + schema + ".t VALUES(?,?,?,?)")) {
                insert.setQueryTimeout(15);
                insert.setBigDecimal(1, decimal);
                insert.setLong(2, Long.MIN_VALUE);
                insert.setString(3, "中文'分号;反斜杠\\");
                insert.setNull(4, java.sql.Types.INTEGER);
                assertEquals(1, insert.executeUpdate());
            }
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(15);
                try (var result = statement.executeQuery("SELECT n,b,v,z FROM " + schema + ".t")) {
                    assertTrue(result.next());
                    assertEquals(0, decimal.compareTo(result.getBigDecimal(1)));
                    assertEquals(Long.MIN_VALUE, result.getLong(2));
                    assertEquals("中文'分号;反斜杠\\", result.getString(3));
                    assertNull(result.getObject(4));
                    assertFalse(result.next());
                }
            }
        });
    }

    @Test
    void simpleProtocolGb18030PreservesChineseSupplementaryCharactersAndNull() throws Exception {
        inIsolatedSchema((connection, schema) -> {
            String table = schema + ".encoded_rows";
            execute(connection, "CREATE TABLE " + table + "(id integer, value text)");
            String original;
            try (var query = connection.createStatement(); var rows = query.executeQuery("SHOW client_encoding")) {
                assertTrue(rows.next());
                original = rows.getString(1);
            }
            try {
                execute(connection, "SET client_encoding TO 'GB18030'");
                try (var query = connection.createStatement(); var rows = query.executeQuery("SHOW client_encoding")) {
                    assertTrue(rows.next());
                    assertEquals("GB18030", rows.getString(1));
                }
                String[] values = {"中文银行", "扩展汉字𠀀", "单引号'与反斜杠\\", null};
                try (var insert = connection.prepareStatement("INSERT INTO " + table + " VALUES(?,?)")) {
                    for (int i = 0; i < values.length; i++) {
                        insert.setInt(1, i);
                        insert.setString(2, values[i]);
                        assertEquals(1, insert.executeUpdate());
                    }
                }
                try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT value FROM " + table + " ORDER BY id")) {
                    for (String value : values) {
                        assertTrue(rows.next());
                        assertEquals(value, rows.getString(1));
                        assertEquals(value == null, rows.wasNull());
                    }
                    assertFalse(rows.next());
                }
                withIndependentConnection(observer -> {
                    try (var query = observer.createStatement(); var rows = query.executeQuery("SELECT value FROM " + table + " ORDER BY id")) {
                        for (String value : values) {
                            assertTrue(rows.next());
                            assertEquals(value, rows.getString(1));
                        }
                        assertFalse(rows.next());
                    }
                });
            } finally {
                if (!connection.isClosed()) {
                    execute(connection, "SET client_encoding TO '" + original.replace("'", "''") + "'");
                }
            }
        }, java.util.Map.of("allowEncodingChanges", "true", "preferQueryMode", "simple"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0", "1", "5"})
    void extendedUtf8ParametersSurvivePrepareThresholdOnDistributed(String threshold) throws Exception {
        extendedUtf8Parameters(threshold, System.getenv("GAUSSDB_HISTORY_CONNECTION"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0", "1", "5"})
    void extendedUtf8ParametersSurvivePrepareThresholdOnCentralized(String threshold) throws Exception {
        extendedUtf8Parameters(threshold, System.getenv("GAUSSDB_HISTORY_CENTRAL_CONNECTION"));
    }

    private void extendedUtf8Parameters(String threshold, String config) throws Exception {
        inIsolatedSchema((connection, schema) -> {
            String table = schema + ".utf8_parameters";
            execute(connection, "CREATE TABLE " + table + "(id integer, value text)");
            String[] values = {"中文银行", "扩展汉字𠀀", "单引号'分号;反斜杠\\", null};
            try (var insert = connection.prepareStatement("INSERT INTO " + table + " VALUES(?,?)")) {
                for (int i = 0; i < 8; i++) {
                    insert.setInt(1, i);
                    insert.setString(2, values[i % values.length]);
                    assertEquals(1, insert.executeUpdate());
                }
            }
            try (var query = connection.prepareStatement("SELECT value FROM " + table + " WHERE id=?")) {
                for (int i = 0; i < 8; i++) {
                    query.setInt(1, i);
                    try (var result = query.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals(values[i % values.length], result.getString(1));
                        assertEquals(values[i % values.length] == null, result.wasNull());
                        assertFalse(result.next());
                    }
                }
            }
            try (var query = connection.createStatement(); var result = query.executeQuery("SHOW client_encoding")) {
                assertTrue(result.next());
                assertEquals("UTF8", result.getString(1));
            }
            withIndependentConnection(observer -> {
                try (var query = observer.createStatement();
                    var result = query.executeQuery("SELECT id,value FROM " + table + " ORDER BY id")) {
                    for (int i = 0; i < 8; i++) {
                        assertTrue(result.next());
                        assertEquals(i, result.getInt(1));
                        assertEquals(values[i % values.length], result.getString(2));
                    }
                    assertFalse(result.next());
                }
            }, null, java.util.Map.of(), config);
        }, java.util.Map.of("preferQueryMode", "extended", "prepareThreshold", threshold), config);
    }

    @Test
    void driverRejectsEncodingChangeWhenExplicitlyDisallowed() throws Exception {
        inIsolatedSchema((connection, schema) -> {
            var error = assertThrows(java.sql.SQLException.class,
                () -> execute(connection, "SET client_encoding TO 'GB18030'"));
            assertEquals("08006", error.getSQLState());
            assertTrue(connection.isClosed());
        }, java.util.Map.of("allowEncodingChanges", "false"));
    }

    @Test
    void utf8JdbcRoundTripsGb18030DatabaseWithoutChangingClientEncoding() throws Exception {
        String database = System.getenv("GAUSSDB_HISTORY_ENCODING_DATABASE");
        assumeTrue(database != null, "Dedicated GB18030 database not configured; not a passing encoding test");
        assertEquals("YES", System.getenv("GAUSSDB_HISTORY_ALLOW_DDL"));
        withIndependentConnection(connection -> {
            try (var query = connection.createStatement(); var rows = query.executeQuery(
                "SELECT current_database(),pg_encoding_to_char(encoding),current_setting('client_encoding')"
                    + " FROM pg_database WHERE datname=current_database()")) {
                assertTrue(rows.next());
                assertEquals(database, rows.getString(1));
                assertEquals("GB18030", rows.getString(2));
                assertEquals("UTF8", rows.getString(3));
            }
            String schema = "dbv_hist_" + UUID.randomUUID().toString().replace("-", "");
            execute(connection, "CREATE SCHEMA " + schema);
            try {
                execute(connection, "CREATE TABLE " + schema + ".encoded_rows(id integer, value text)");
                String[] values = {"中文银行", "扩展汉字𠀀", "单引号'与反斜杠\\", null};
                try (var insert = connection.prepareStatement("INSERT INTO " + schema + ".encoded_rows VALUES(?,?)")) {
                    for (int i = 0; i < values.length; i++) {
                        insert.setInt(1, i);
                        insert.setString(2, values[i]);
                        assertEquals(1, insert.executeUpdate());
                    }
                }
                withIndependentConnection(observer -> {
                    try (var query = observer.createStatement(); var rows = query.executeQuery(
                        "SELECT value FROM " + schema + ".encoded_rows ORDER BY id")) {
                        for (String value : values) {
                            assertTrue(rows.next());
                            assertEquals(value, rows.getString(1));
                            assertEquals(value == null, rows.wasNull());
                        }
                        assertFalse(rows.next());
                    }
                }, database);
            } finally {
                execute(connection, "DROP SCHEMA " + schema + " CASCADE");
            }
        }, database);
    }
}
