package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;

/** Oracle returns BigDecimal for every NUMBER and Timestamp for every DATE from getObject. */
final class ResultObjectTypesPostgresTest {
    @Test
    void integerAndCountResultsAreBigDecimalWithMatchingClassName() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE obj_types (n integer, big bigint, d date)");
            try {
                statement.execute(
                        "INSERT INTO obj_types VALUES (3, 9000000000, DATE '2024-01-31')");
                try (ResultSet result =
                        statement.executeQuery("SELECT n, big, COUNT(*) OVER () FROM obj_types")) {
                    result.next();
                    for (int column = 1; column <= 3; column++) {
                        assertInstanceOf(BigDecimal.class, result.getObject(column));
                        assertEquals(
                                "java.math.BigDecimal",
                                result.getMetaData().getColumnClassName(column));
                    }
                    assertEquals(
                            0, new BigDecimal(3).compareTo((BigDecimal) result.getObject("n")));
                    assertEquals(3, result.getInt(1));
                }
            } finally {
                statement.execute("DROP TABLE obj_types");
            }
        }
    }

    @Test
    void dateColumnIsTimestampWithMatchingClassName() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE obj_dates (d date)");
            try {
                statement.execute("INSERT INTO obj_dates VALUES (DATE '2024-01-31')");
                try (ResultSet result = statement.executeQuery("SELECT d FROM obj_dates")) {
                    result.next();
                    assertInstanceOf(Timestamp.class, result.getObject(1));
                    assertEquals("java.sql.Timestamp", result.getMetaData().getColumnClassName(1));
                }
            } finally {
                statement.execute("DROP TABLE obj_dates");
            }
        }
    }
}
