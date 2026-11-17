package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/** DELETE table WHERE ... (no FROM) runs and returns the Oracle row count. */
final class DeleteWithoutFromPostgresTest {
    @BeforeAll
    static void createTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS dwf_rows");
            statement.execute("CREATE TABLE dwf_rows (id integer PRIMARY KEY)");
        }
    }

    @AfterAll
    static void dropTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS dwf_rows");
        }
    }

    @Test
    void deleteWithoutFromReturnsTheCount() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM dwf_rows");
            statement.execute("INSERT INTO dwf_rows VALUES (1), (2), (3)");
            assertEquals(1, statement.executeUpdate("DELETE dwf_rows WHERE id = 1"));
            assertEquals(1, statement.executeUpdate("DELETE dwf_rows d WHERE d.id = 2"));
            assertEquals(1, statement.executeUpdate("DELETE dwf_rows"));
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM dwf_rows")) {
                result.next();
                assertEquals(0, result.getInt(1));
            }
        }
    }
}
