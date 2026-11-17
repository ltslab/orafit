package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;

/**
 * Ora2Pg stores an Oracle DATE as timestamp(0): that column is a DATE here, so its arithmetic and
 * its result metadata follow Oracle's DATE rules. A timestamp(6) column stays a TIMESTAMP.
 */
final class Ora2PgDateColumnPostgresTest {
    @BeforeAll
    static void createTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS o2p_dates");
            statement.execute(
                    "CREATE TABLE o2p_dates (id integer PRIMARY KEY, d timestamp(0), d2"
                            + " timestamp(0), t timestamp(6))");
            statement.execute(
                    "INSERT INTO o2p_dates VALUES (1, '2024-01-31 13:45:10', '2024-03-01"
                            + " 13:45:10', '2024-01-31 13:45:10.123456')");
        }
    }

    @AfterAll
    static void dropTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS o2p_dates");
        }
    }

    private interface Check {
        void accept(ResultSetMetaData metadata, ResultSet result) throws Exception;
    }

    private static void query(String expression, Check check) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery("SELECT " + expression + " FROM o2p_dates")) {
            result.next();
            check.accept(result.getMetaData(), result);
        }
    }

    private static void assertDate(String expression, String expected) throws Exception {
        query(
                expression,
                (metadata, result) -> {
                    assertEquals(Types.TIMESTAMP, metadata.getColumnType(1), expression);
                    assertEquals("DATE", metadata.getColumnTypeName(1), expression);
                    assertEquals(7, metadata.getPrecision(1), expression);
                    assertEquals(0, metadata.getScale(1), expression);
                    assertEquals(Timestamp.valueOf(expected), result.getTimestamp(1), expression);
                });
    }

    @Test
    void aTimestampZeroColumnIsADate() throws Exception {
        assertDate("d", "2024-01-31 13:45:10");
        assertDate("d + 1", "2024-02-01 13:45:10");
        assertDate("d - 1.5", "2024-01-30 01:45:10");
        assertDate("1 + d", "2024-02-01 13:45:10");
        assertDate("TRUNC(d)", "2024-01-31 00:00:00");
    }

    @Test
    void theDifferenceOfTwoDatesIsANumberOfDays() throws Exception {
        query(
                "d2 - d",
                (metadata, result) -> {
                    assertEquals(Types.NUMERIC, metadata.getColumnType(1));
                    assertEquals(0, new BigDecimal(30).compareTo(result.getBigDecimal(1)));
                });
    }

    @Test
    void aTimestampSixColumnStaysATimestamp() throws Exception {
        query("t", (metadata, result) -> assertEquals("TIMESTAMP", metadata.getColumnTypeName(1)));
    }

    @Test
    void dmlUsesDateArithmeticOnItsTargetColumn() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("UPDATE o2p_dates SET d = d + 1 WHERE id = 1"));
            assertEquals(
                    1,
                    statement.executeUpdate(
                            "UPDATE o2p_dates SET d = d - 1 WHERE d + 1 > TO_DATE('2024-02-01',"
                                    + " 'YYYY-MM-DD') AND id = 1"));
            try (ResultSet result = statement.executeQuery("SELECT d FROM o2p_dates")) {
                result.next();
                assertEquals(Timestamp.valueOf("2024-01-31 13:45:10"), result.getTimestamp(1));
            }
        }
    }

    /** A timestamp(0) with a time zone is not the DATE Ora2Pg made. */
    @Test
    void aTimeZoneColumnOfWholeSecondsIsNotADate() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS o2p_zones");
            statement.execute("CREATE TABLE o2p_zones (id integer, z timestamptz(0))");
            statement.execute("INSERT INTO o2p_zones VALUES (1, '2024-01-31 13:45:10+00')");
            try (ResultSet result = statement.executeQuery("SELECT z FROM o2p_zones")) {
                assertNotEquals("DATE", result.getMetaData().getColumnTypeName(1));
            }
            assertThrows(
                    SQLException.class,
                    () -> statement.executeQuery("SELECT z + 1 FROM o2p_zones"));
            statement.execute("DROP TABLE o2p_zones");
        }
    }
}
