/*
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
package io.trino.plugin.blackhole;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Iterables;
import io.airlift.units.Duration;
import io.trino.Session;
import io.trino.metadata.QualifiedObjectName;
import io.trino.testing.MaterializedResult;
import io.trino.testing.MaterializedRow;
import io.trino.testing.QueryRunner;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static io.airlift.testing.Assertions.assertGreaterThan;
import static io.trino.plugin.blackhole.BlackHoleConnector.FIELD_LENGTH_PROPERTY;
import static io.trino.plugin.blackhole.BlackHoleConnector.PAGES_PER_SPLIT_PROPERTY;
import static io.trino.plugin.blackhole.BlackHoleConnector.PAGE_PROCESSING_DELAY;
import static io.trino.plugin.blackhole.BlackHoleConnector.ROWS_PER_PAGE_PROPERTY;
import static io.trino.plugin.blackhole.BlackHoleConnector.SPLIT_COUNT_PROPERTY;
import static io.trino.plugin.blackhole.BlackHoleQueryRunner.createQueryRunner;
import static io.trino.testing.TestingNames.randomNameSuffix;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;

@Test(singleThreaded = true)
public class TestBlackHoleSmoke
{
    private QueryRunner queryRunner;

    @BeforeClass
    public void setUp()
            throws Exception
    {
        queryRunner = createQueryRunner();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        assertThatNoBlackHoleTableIsCreated();
        queryRunner.close();
        queryRunner = null;
    }

    @Test
    public void testCreateSchema()
    {
        assertEquals(queryRunner.execute("SHOW SCHEMAS FROM blackhole").getRowCount(), 2);
        assertThatQueryReturnsValue("CREATE TABLE test_schema as SELECT * FROM tpch.tiny.nation", 25L);

        queryRunner.execute("CREATE SCHEMA blackhole.test");
        assertEquals(queryRunner.execute("SHOW SCHEMAS FROM blackhole").getRowCount(), 3);
        assertThatQueryReturnsValue("CREATE TABLE test.test_schema as SELECT * FROM tpch.tiny.region", 5L);

        assertThatQueryDoesNotReturnValues("DROP TABLE test_schema");
        assertThatQueryDoesNotReturnValues("DROP TABLE test.test_schema");
    }

    @Test
    public void createTableWhenTableIsAlreadyCreated()
    {
        String createTableSql = "CREATE TABLE nation as SELECT * FROM tpch.tiny.nation";
        queryRunner.execute(createTableSql);
        assertThatThrownBy(() -> queryRunner.execute(createTableSql))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("line 1:1: Destination table 'blackhole.default.nation' already exists");
        assertThatQueryDoesNotReturnValues("DROP TABLE nation");
    }

    @Test
    public void blackHoleConnectorUsage()
    {
        assertThatQueryReturnsValue("CREATE TABLE nation as SELECT * FROM tpch.tiny.nation", 25L);

        List<QualifiedObjectName> tableNames = listBlackHoleTables();
        assertEquals(tableNames.size(), 1, "Expected only one table.");
        assertEquals(tableNames.get(0).getObjectName(), "nation", "Expected 'nation' table.");

        assertThatQueryReturnsValue("INSERT INTO nation SELECT * FROM tpch.tiny.nation", 25L);

        assertThatQueryReturnsValue("INSERT INTO nation SELECT * FROM tpch.tiny.nation", 25L);

        assertThatQueryReturnsValue("SELECT count(*) FROM nation", 0L);

        assertThatQueryDoesNotReturnValues("DROP TABLE nation");
    }

    @Test
    public void notAllPropertiesSetForDataGeneration()
    {
        assertThatThrownBy(() -> queryRunner.execute(
                format("CREATE TABLE nation WITH ( %s = 3, %s = 1 ) as SELECT * FROM tpch.tiny.nation",
                        ROWS_PER_PAGE_PROPERTY,
                        SPLIT_COUNT_PROPERTY)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("All properties [split_count, pages_per_split, rows_per_page] must be set if any are set");
    }

    @Test
    public void createTableWithDistribution()
    {
        assertThatQueryReturnsValue(
                "CREATE TABLE distributed_test WITH ( distributed_on = array['orderkey'] ) AS SELECT * FROM tpch.tiny.orders",
                15000L);
        assertThatQueryDoesNotReturnValues("DROP TABLE distributed_test");
    }

    @Test
    public void testCreateTableInNotExistSchema()
    {
        int tablesBeforeCreate = listBlackHoleTables().size();

        String createTableSql = "CREATE TABLE schema1.test_table (x date)";
        assertThatThrownBy(() -> queryRunner.execute(createTableSql))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Schema schema1 not found");

        int tablesAfterCreate = listBlackHoleTables().size();
        assertEquals(tablesBeforeCreate, tablesAfterCreate);
    }

    @Test
    public void dataGenerationUsage()
    {
        Session session = testSessionBuilder()
                .setCatalog("blackhole")
                .setSchema("default")
                .build();

        assertThatQueryReturnsValue(
                format("CREATE TABLE nation WITH ( %s = 3, %s = 2, %s = 1 ) as SELECT * FROM tpch.tiny.nation",
                        ROWS_PER_PAGE_PROPERTY,
                        PAGES_PER_SPLIT_PROPERTY,
                        SPLIT_COUNT_PROPERTY),
                25L,
                session);
        assertThatQueryReturnsValue("SELECT count(*) FROM nation", 6L, session);
        assertThatQueryReturnsValue("INSERT INTO nation SELECT * FROM tpch.tiny.nation", 25L, session);
        assertThatQueryReturnsValue("SELECT count(*) FROM nation", 6L, session);

        MaterializedResult rows = queryRunner.execute(session, "SELECT * FROM nation LIMIT 1");
        assertEquals(rows.getRowCount(), 1);
        MaterializedRow row = Iterables.getOnlyElement(rows);
        assertEquals(row.getFieldCount(), 4);
        assertEquals(row.getField(0), 0L);
        assertEquals(row.getField(1), "****************");
        assertEquals(row.getField(2), 0L);
        assertEquals(row.getField(3), "****************");

        assertThatQueryDoesNotReturnValues("DROP TABLE nation");
    }

    @Test
    public void testMaterializedView()
    {
        String viewName = "test_materialized_view_" + randomNameSuffix();
        queryRunner.execute("CREATE MATERIALIZED VIEW " + viewName + " AS SELECT * FROM tpch.tiny.nation");

        try {
            // reading
            MaterializedResult rows = queryRunner.execute("SELECT * FROM " + viewName);
            assertEquals(rows.getRowCount(), 25);

            // listing
            assertThat(queryRunner.execute("SHOW TABLES").getOnlyColumnAsSet())
                    .contains(viewName);
        }
        finally {
            assertThatQueryDoesNotReturnValues("DROP MATERIALIZED VIEW " + viewName);
        }
    }

    @Test
    public void testCommentMaterializedViewColumn()
    {
        String viewName = "test_materialized_view_" + randomNameSuffix();
        try {
            queryRunner.execute("CREATE MATERIALIZED VIEW " + viewName + " AS SELECT * FROM tpch.tiny.nation");

            // comment set
            queryRunner.execute("COMMENT ON COLUMN " + viewName + ".regionkey IS 'new region key comment'");
            assertThat(getColumnComment(viewName, "regionkey")).isEqualTo("new region key comment");

            // comment updated
            queryRunner.execute("COMMENT ON COLUMN " + viewName + ".regionkey IS 'updated region key comment'");
            assertThat(getColumnComment(viewName, "regionkey")).isEqualTo("updated region key comment");

            // comment set to empty
            queryRunner.execute("COMMENT ON COLUMN " + viewName + ".regionkey IS ''");
            assertThat(getColumnComment(viewName, "regionkey")).isEqualTo("");

            // comment deleted
            queryRunner.execute("COMMENT ON COLUMN " + viewName + ".regionkey IS NULL");
            assertThat(getColumnComment(viewName, "regionkey")).isEqualTo(null);
        }
        finally {
            assertThatQueryDoesNotReturnValues("DROP MATERIALIZED VIEW " + viewName);
        }
    }

    private String getColumnComment(String tableName, String columnName)
    {
        return (String) queryRunner.execute(format("SELECT comment FROM information_schema.columns WHERE table_schema = '%s' AND table_name = '%s' AND column_name = '%s'", "default", tableName, columnName)).getOnlyValue();
    }

    @Test
    public void fieldLength()
    {
        Session session = testSessionBuilder()
                .setCatalog("blackhole")
                .setSchema("default")
                .build();

        assertThatQueryReturnsValue(
                format("CREATE TABLE nation WITH ( %s = 8, %s = 1, %s = 1, %s = 1 ) AS " +
                                "SELECT nationkey, name, regionkey, comment, 'abc' short_varchar FROM tpch.tiny.nation",
                        FIELD_LENGTH_PROPERTY,
                        ROWS_PER_PAGE_PROPERTY,
                        PAGES_PER_SPLIT_PROPERTY,
                        SPLIT_COUNT_PROPERTY),
                25L,
                session);

        MaterializedResult rows = queryRunner.execute(session, "SELECT * FROM nation");
        assertEquals(rows.getRowCount(), 1);
        MaterializedRow row = Iterables.getOnlyElement(rows);
        assertEquals(row.getFieldCount(), 5);
        assertEquals(row.getField(0), 0L);
        assertEquals(row.getField(1), "********");
        assertEquals(row.getField(2), 0L);
        assertEquals(row.getField(3), "********");
        assertEquals(row.getField(4), "***"); // this one is shorter due to column type being VARCHAR(3)

        assertThatQueryDoesNotReturnValues("DROP TABLE nation");
    }

    @Test
    public void testInsertAllTypes()
    {
        createBlackholeAllTypesTable();
        assertThatQueryReturnsValue(
                "INSERT INTO blackhole_all_types VALUES (" +
                        "'abc', " +
                        "BIGINT '1', " +
                        "INTEGER '2', " +
                        "SMALLINT '3', " +
                        "TINYINT '4', " +
                        "REAL '5.1', " +
                        "DOUBLE '5.2', " +
                        "true, " +
                        "DATE '2014-01-02', " +
                        "TIMESTAMP '2014-01-02 12:12', " +
                        "cast('bar' as varbinary), " +
                        "DECIMAL '3.14', " +
                        "DECIMAL '1234567890.123456789')", 1L);
        dropBlackholeAllTypesTable();
    }

    @Test
    public void testSelectAllTypes()
    {
        createBlackholeAllTypesTable();
        MaterializedResult rows = queryRunner.execute("SELECT * FROM blackhole_all_types");
        assertEquals(rows.getRowCount(), 1);
        MaterializedRow row = Iterables.getOnlyElement(rows);
        assertEquals(row.getFieldCount(), 13);
        assertEquals(row.getField(0), "**********");
        assertEquals(row.getField(1), 0L);
        assertEquals(row.getField(2), 0);
        assertEquals(row.getField(3), (short) 0);
        assertEquals(row.getField(4), (byte) 0);
        assertEquals(row.getField(5), 0.0f);
        assertEquals(row.getField(6), 0.0);
        assertEquals(row.getField(7), false);
        assertEquals(row.getField(8), LocalDate.ofEpochDay(0));
        assertEquals(row.getField(9), LocalDateTime.of(1970, 1, 1, 0, 0, 0));
        assertEquals(row.getField(10), "****************".getBytes(UTF_8));
        assertEquals(row.getField(11), new BigDecimal("0.00"));
        assertEquals(row.getField(12), new BigDecimal("00000000000000000000.0000000000"));
        dropBlackholeAllTypesTable();
    }

    @Test
    public void testSelectWithUnenforcedConstraint()
    {
        createBlackholeAllTypesTable();
        MaterializedResult rows = queryRunner.execute("SELECT * FROM blackhole_all_types where _bigint > 10");
        assertEquals(rows.getRowCount(), 0);
        dropBlackholeAllTypesTable();
    }

    private void createBlackholeAllTypesTable()
    {
        assertThatQueryDoesNotReturnValues(
                format("CREATE TABLE blackhole_all_types (" +
                                "  _varchar VARCHAR(10)" +
                                ", _bigint BIGINT" +
                                ", _integer INTEGER" +
                                ", _smallint SMALLINT" +
                                ", _tinyint TINYINT" +
                                ", _real REAL" +
                                ", _double DOUBLE" +
                                ", _boolean BOOLEAN" +
                                ", _date DATE" +
                                ", _timestamp TIMESTAMP" +
                                ", _varbinary VARBINARY" +
                                ", _decimal_short DECIMAL(3,2)" +
                                ", _decimal_long DECIMAL(30,10)" +
                                ") WITH ( %s = 1, %s = 1, %s = 1 ) ",
                        ROWS_PER_PAGE_PROPERTY,
                        PAGES_PER_SPLIT_PROPERTY,
                        SPLIT_COUNT_PROPERTY));
    }

    private void dropBlackholeAllTypesTable()
    {
        assertThatQueryDoesNotReturnValues("DROP TABLE IF EXISTS blackhole_all_types");
    }

    @Test
    public void pageProcessingDelay()
    {
        Session session = testSessionBuilder()
                .setCatalog("blackhole")
                .setSchema("default")
                .build();

        Duration pageProcessingDelay = new Duration(1, SECONDS);

        assertThatQueryReturnsValue(
                format("CREATE TABLE nation WITH ( %s = 8, %s = 1, %s = 1, %s = 1, %s = '%s' ) AS " +
                                "SELECT * FROM tpch.tiny.nation",
                        FIELD_LENGTH_PROPERTY,
                        ROWS_PER_PAGE_PROPERTY,
                        PAGES_PER_SPLIT_PROPERTY,
                        SPLIT_COUNT_PROPERTY,
                        PAGE_PROCESSING_DELAY,
                        pageProcessingDelay),
                25L,
                session);

        Stopwatch stopwatch = Stopwatch.createStarted();

        assertEquals(queryRunner.execute(session, "SELECT * FROM nation").getRowCount(), 1);
        queryRunner.execute(session, "INSERT INTO nation SELECT CAST(null AS BIGINT), CAST(null AS VARCHAR(25)), CAST(null AS BIGINT), CAST(null AS VARCHAR(152))");

        stopwatch.stop();
        assertGreaterThan(stopwatch.elapsed(MILLISECONDS), pageProcessingDelay.toMillis());

        assertThatQueryDoesNotReturnValues("DROP TABLE nation");
    }

    private void assertThatNoBlackHoleTableIsCreated()
    {
        assertEquals(listBlackHoleTables().size(), 0, "No blackhole tables expected");
    }

    private List<QualifiedObjectName> listBlackHoleTables()
    {
        return queryRunner.listTables(queryRunner.getDefaultSession(), "blackhole", "default");
    }

    private void assertThatQueryReturnsValue(String sql, Object expected)
    {
        assertThatQueryReturnsValue(sql, expected, null);
    }

    private void assertThatQueryReturnsValue(String sql, Object expected, Session session)
    {
        MaterializedResult rows = session == null ? queryRunner.execute(sql) : queryRunner.execute(session, sql);
        MaterializedRow materializedRow = Iterables.getOnlyElement(rows);
        int fieldCount = materializedRow.getFieldCount();
        assertEquals(fieldCount, 1, format("Expected only one column, but got '%d'", fieldCount));
        Object value = materializedRow.getField(0);
        assertEquals(value, expected);
        assertEquals(Iterables.getOnlyElement(rows).getFieldCount(), 1);
    }

    private void assertThatQueryDoesNotReturnValues(String sql)
    {
        assertThatQueryDoesNotReturnValues(queryRunner.getDefaultSession(), sql);
    }

    private void assertThatQueryDoesNotReturnValues(Session session, @Language("SQL") String sql)
    {
        MaterializedResult rows = session == null ? queryRunner.execute(sql) : queryRunner.execute(session, sql);
        assertEquals(rows.getRowCount(), 0);
    }
}
