package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

/** TO_CHAR with a format of a date expression or a bound date. Values are Oracle's. */
final class ToCharDateExpressionPostgresTest {
    @BeforeAll
    static void createTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS tcd_rows");
            statement.execute("CREATE TABLE tcd_rows (id integer PRIMARY KEY, d timestamp(0))");
            statement.execute("INSERT INTO tcd_rows VALUES (1, '2024-02-29 13:45:10')");
        }
    }

    @AfterAll
    static void dropTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS tcd_rows");
        }
    }

    private static String column(String expression) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery("SELECT " + expression + " FROM tcd_rows")) {
            result.next();
            return result.getString(1);
        }
    }

    private interface Binder {
        void bind(PreparedStatement statement) throws Exception;
    }

    private static String bound(String expression, Binder binder) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT " + expression + " FROM dual")) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    @Test
    void aDateExpressionOverAColumn() throws Exception {
        assertEquals("2024-03-01", column("TO_CHAR(d + 1, 'YYYY-MM-DD')"));
        assertEquals("2024-02-29 00:00:00", column("TO_CHAR(TRUNC(d), 'YYYY-MM-DD HH24:MI:SS')"));
        assertEquals("29/02/2024", column("TO_CHAR((d), 'DD/MM/YYYY')"));
        assertEquals("2024-03-01", column("TO_CHAR(TRUNC(d) + 1, 'YYYY-MM-DD')"));
    }

    @Test
    void aBoundDate() throws Exception {
        assertEquals(
                "2026-01-31",
                bound("TO_CHAR(?, 'YYYY-MM-DD')", s -> s.setDate(1, Date.valueOf("2026-01-31"))));
        assertEquals(
                "2026-01-31 08:09:10",
                bound(
                        "TO_CHAR(?, 'YYYY-MM-DD HH24:MI:SS')",
                        s -> s.setTimestamp(1, Timestamp.valueOf("2026-01-31 08:09:10"))));
        assertEquals(
                "2026-02-01",
                bound(
                        "TO_CHAR(? + 1, 'YYYY-MM-DD')",
                        s -> s.setDate(1, Date.valueOf("2026-01-31"))));
    }

    /** A number is not a date: the date format on it fails instead of printing something. */
    @Test
    void aNumberWithADateFormatFails() {
        assertThrows(
                SQLException.class, () -> bound("TO_CHAR(?, 'YYYY-MM-DD')", s -> s.setInt(1, 5)));
    }
}
