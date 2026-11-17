package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Oracle's AVG is the NUMBER quotient of the sum and the count, so it keeps the digits of NUMBER
 * division (40 significant digits, checked against Oracle 18c XE); PostgreSQL's avg stops at 16
 * fractional digits.
 */
final class AvgPrecisionPostgresTest {
    /** AVG of 5000, 3200 and 2500 as Oracle 18c XE returns it. */
    private static final String ORACLE_AVERAGE = "3566.666666666666666666666666666666666667";

    @BeforeAll
    static void createTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS avg_rows");
            statement.execute("CREATE TABLE avg_rows (id integer PRIMARY KEY, n numeric(10,2))");
            statement.execute(
                    "INSERT INTO avg_rows VALUES (1, 5000), (2, 3200), (3, 2500), (4, NULL)");
        }
    }

    @AfterAll
    static void dropTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS avg_rows");
        }
    }

    private static String value(String sql) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            java.math.BigDecimal value = result.getBigDecimal(1);
            return value == null ? null : value.stripTrailingZeros().toPlainString();
        }
    }

    @Test
    void avgKeepsTheDigitsOfNumberDivision() throws Exception {
        assertEquals(ORACLE_AVERAGE, value("SELECT AVG(n) FROM avg_rows"));
        assertEquals(ORACLE_AVERAGE, value("SELECT AVG(n) OVER () FROM avg_rows ORDER BY id"));
    }

    @Test
    void avgDistinctAveragesEachValueOnce() throws Exception {
        assertEquals("4100", value("SELECT AVG(DISTINCT n) FROM avg_rows WHERE id <> 3"));
        assertEquals("1", value("SELECT AVG(DISTINCT 1) FROM avg_rows"));
    }

    @Test
    void avgOfNoValuesIsNull() throws Exception {
        assertNull(value("SELECT AVG(n) FROM avg_rows WHERE id < 0"));
        assertNull(value("SELECT AVG(n) FROM avg_rows WHERE id = 4"));
    }
}
