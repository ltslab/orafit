package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/** UPDATE t SET (a, b) = (SELECT ...) into NUMBER columns keeps its multiple-column form. */
final class MultiColumnUpdatePostgresTest {
    @BeforeAll
    static void createTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS mcu_rows");
            statement.execute(
                    "CREATE TABLE mcu_rows (id integer PRIMARY KEY, a numeric(10,2), b"
                            + " numeric(10,2))");
            statement.execute("INSERT INTO mcu_rows VALUES (1, 0, 0), (2, 0, 0)");
        }
    }

    @AfterAll
    static void dropTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS mcu_rows");
        }
    }

    private static BigDecimal read(Statement statement, String column, int id) throws Exception {
        try (ResultSet result =
                statement.executeQuery("SELECT " + column + " FROM mcu_rows WHERE id = " + id)) {
            result.next();
            return result.getBigDecimal(1);
        }
    }

    @Test
    void aSubqueryAssignsSeveralNumberColumns() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            assertEquals(
                    1,
                    statement.executeUpdate(
                            "UPDATE mcu_rows SET (a, b) = (SELECT 1, 2 FROM dual) WHERE id = 1"));
            assertEquals(0, BigDecimal.ONE.compareTo(read(statement, "a", 1)));
            assertEquals(0, new BigDecimal(2).compareTo(read(statement, "b", 1)));
        }
    }

    @Test
    void aCorrelatedSubqueryAssignsSeveralNumberColumns() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            assertEquals(
                    1,
                    statement.executeUpdate(
                            "UPDATE mcu_rows r SET (a, b) = (SELECT r.id + 10, r.id + 20 FROM dual)"
                                    + " WHERE r.id = 2"));
            assertEquals(0, new BigDecimal(12).compareTo(read(statement, "a", 2)));
            assertEquals(0, new BigDecimal(22).compareTo(read(statement, "b", 2)));
        }
    }
}
