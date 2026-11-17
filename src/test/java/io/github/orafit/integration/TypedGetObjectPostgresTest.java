package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * getObject(column, Class) on an Oracle DATE (a date or timestamp(0) column here) and on a NUMBER.
 * The expected values are what Oracle 18c XE's JDBC driver returns for the same calls.
 */
final class TypedGetObjectPostgresTest {
    @BeforeAll
    static void createTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS tgo_rows");
            statement.execute(
                    "CREATE TABLE tgo_rows (id integer PRIMARY KEY, d timestamp(0), dd date, n"
                            + " numeric(4), sal numeric(8,2), name varchar(20))");
            statement.execute(
                    "INSERT INTO tgo_rows VALUES (1, '2020-01-31 13:45:10', '2020-01-31', 1,"
                            + " 5000.50, 'ALICE'), (2, NULL, NULL, NULL, NULL, NULL)");
        }
    }

    @AfterAll
    static void dropTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS tgo_rows");
        }
    }

    private interface Check {
        void accept(ResultSet result) throws Exception;
    }

    private static void row(int id, Check check) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "SELECT d, dd, n, sal, name FROM tgo_rows WHERE id = " + id)) {
            result.next();
            check.accept(result);
        }
    }

    @Test
    void aDateColumnInTheJavaTimeAndSqlTypes() throws Exception {
        row(
                1,
                r -> {
                    for (int column : new int[] {1, 2}) {
                        assertEquals(
                                LocalDate.of(2020, 1, 31), r.getObject(column, LocalDate.class));
                        assertEquals(
                                java.sql.Date.valueOf("2020-01-31"),
                                r.getObject(column, java.sql.Date.class));
                        assertThrows(
                                SQLException.class,
                                () -> r.getObject(column, OffsetDateTime.class));
                    }
                    assertEquals(
                            LocalDateTime.of(2020, 1, 31, 13, 45, 10),
                            r.getObject(1, LocalDateTime.class));
                    assertEquals(
                            Timestamp.valueOf("2020-01-31 13:45:10"),
                            r.getObject(1, Timestamp.class));
                    assertEquals("2020-01-31 13:45:10", r.getObject(1, String.class));
                    assertEquals(
                            LocalDateTime.of(2020, 1, 31, 0, 0),
                            r.getObject(2, LocalDateTime.class));
                    assertEquals(
                            Timestamp.valueOf("2020-01-31 00:00:00"),
                            r.getObject(2, Timestamp.class));
                    assertEquals("2020-01-31 00:00:00", r.getObject(2, String.class));
                });
    }

    @Test
    void aNumberColumnInTheJavaNumberTypes() throws Exception {
        row(
                1,
                r -> {
                    assertEquals(1, r.getObject(3, Integer.class));
                    assertEquals(1L, r.getObject(3, Long.class));
                    assertEquals(0, BigDecimal.ONE.compareTo(r.getObject(3, BigDecimal.class)));
                    assertEquals(5000.5, r.getObject(4, Double.class));
                    assertEquals("5000.5", r.getObject(4, String.class));
                    assertEquals("ALICE", r.getObject(5, String.class));
                });
    }

    @Test
    void nullStaysNull() throws Exception {
        row(
                2,
                r -> {
                    assertNull(r.getObject(1, LocalDate.class));
                    assertNull(r.getObject(2, Timestamp.class));
                    assertNull(r.getObject(3, Integer.class));
                    assertNull(r.getObject(4, String.class));
                });
    }

    /** Measured on Oracle 18c XE: a NULL DATE is null for every class the DATE answers. */
    @Test
    void timeZoneClassesOnADate() throws Exception {
        row(
                1,
                r -> {
                    SQLException zoned =
                            assertThrows(
                                    SQLException.class, () -> r.getObject(1, OffsetDateTime.class));
                    assertEquals(18716, zoned.getErrorCode());
                    assertEquals("72000", zoned.getSQLState());
                    SQLException instant =
                            assertThrows(
                                    SQLException.class,
                                    () -> r.getObject(1, java.time.Instant.class));
                    assertEquals(17004, instant.getErrorCode());
                });
        row(
                2,
                r -> {
                    assertNull(r.getObject(1, OffsetDateTime.class));
                    assertNull(r.getObject(1, java.time.ZonedDateTime.class));
                    assertEquals(
                            17004,
                            assertThrows(
                                            SQLException.class,
                                            () -> r.getObject(1, java.time.Instant.class))
                                    .getErrorCode());
                });
    }
}
