/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.jkiss.dbeaver.ext.postgresql.model.PostgreProcedureParameter;
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
class GaussDBHistoricalJdbcLiveTest {
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
        });
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

    private static void assertRealPlan(Connection c, String query) throws Exception {
        // Dedicated fixture session only: expose the operator tree instead of an opaque shipped query.
        execute(c, "SET enable_fast_query_shipping = off");
        var plan = new org.jkiss.dbeaver.ext.postgresql.model.plan.PostgreExecutionPlan(false, false, query,
            new org.jkiss.dbeaver.model.exec.plan.DBCQueryPlannerConfiguration());
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
        var children = new java.util.ArrayList<org.w3c.dom.Element>();
        for (var child = expected.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof org.w3c.dom.Element element) {
                if ("Node-Type".equals(element.getTagName())) type = element.getTextContent();
                if ("Total-Cost".equals(element.getTagName())) cost = element.getTextContent();
                if ("Plan-Rows".equals(element.getTagName())) planRows = element.getTextContent();
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
        if (planRows != null) assertEquals(Long.parseLong(planRows), pgNode.getNodeRowCount().longValue());
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
    @FunctionalInterface
    private interface Scenario {
        void run(Connection connection, String schema) throws Exception;
    }

    private void inIsolatedSchema(Scenario scenario) throws Exception {
        String config = System.getenv("GAUSSDB_HISTORY_CONNECTION");
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
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()},
            ClassLoader.getPlatformClassLoader())) {
            var driver = (Driver) loader.loadClass(driverClass).getConstructor().newInstance();
            try (Connection connection = driver.connect(url, properties)) {
                assertNotNull(connection, "Driver did not accept URL");
                connection.setAutoCommit(true);
                String schema = "dbv_hist_" + UUID.randomUUID().toString().replace("-", "");
                execute(connection, "CREATE SCHEMA " + schema);
                try {
                    scenario.run(connection, schema);
                } finally {
                    if (!connection.getAutoCommit()) {
                        connection.rollback();
                        connection.setAutoCommit(true);
                    }
                    // Only a randomly named schema successfully created by this test is removed.
                    execute(connection, "DROP SCHEMA " + schema + " CASCADE");
                }
            }
        }
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
        var p = new Properties();
        try (var input = Files.newInputStream(Path.of(System.getenv("GAUSSDB_HISTORY_CONNECTION")))) {
            p.load(input);
        }
        p.setProperty("socketTimeout", "20");
        p.setProperty("connectTimeout", "10");
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(System.getenv("GAUSSDB_HISTORY_JDBC")).toUri().toURL()},
            ClassLoader.getPlatformClassLoader())) {
            var driver = (Driver) loader.loadClass(p.getProperty("driverClass")).getConstructor().newInstance();
            try (var observer = driver.connect(p.getProperty("url"), p)) {
                assertNotNull(observer);
                assertEquals(expected, count(observer, table));
            }
        }
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
}
