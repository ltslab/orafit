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

/**
 * A date or timestamp bind used inside an expression has the type of its setter. The expected
 * values are what Oracle 18c XE returns for the same statements.
 */
final class DateBindExpressionPostgresTest {
    @BeforeAll
    static void createTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS dbe_dates");
            statement.execute("CREATE TABLE dbe_dates (id integer PRIMARY KEY, created date)");
            statement.execute(
                    "INSERT INTO dbe_dates VALUES (1, '2020-01-31'), (2, '2020-02-29'),"
                            + " (3, '2024-01-01'), (4, '2024-02-29')");
        }
    }

    @AfterAll
    static void dropTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS dbe_dates");
        }
    }

    private interface Binder {
        void bind(PreparedStatement statement) throws Exception;
    }

    private static Object value(String expression, Binder binder) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT " + expression + " FROM dual")) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getObject(1);
            }
        }
    }

    private static int count(String condition, Binder binder) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM dbe_dates WHERE " + condition)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    @Test
    void aTimestampBindInADateFunction() throws Exception {
        Binder moment = s -> s.setTimestamp(1, Timestamp.valueOf("2026-01-31 08:00:00"));
        assertEquals(Timestamp.valueOf("2026-01-31 00:00:00"), value("TRUNC(?)", moment));
        assertEquals(
                Timestamp.valueOf("2026-02-28 08:00:00"),
                value(
                        "LAST_DAY(?)",
                        s -> s.setTimestamp(1, Timestamp.valueOf("2026-02-10 08:00:00"))));
    }

    @Test
    void aDateBindInADateFunction() throws Exception {
        Binder day = s -> s.setDate(1, Date.valueOf("2026-01-31"));
        assertEquals(Timestamp.valueOf("2026-01-31 00:00:00"), value("TRUNC(?)", day));
        assertEquals(Timestamp.valueOf("2026-02-28 00:00:00"), value("ADD_MONTHS(?, 1)", day));
        assertEquals(
                Timestamp.valueOf("2026-02-28 00:00:00"),
                value("ADD_MONTHS(?, 1)", s -> s.setObject(1, Date.valueOf("2026-01-31"))));
    }

    /** Against a DATE, "bind - days" is date arithmetic; the bind is a date by its setter. */
    @Test
    void daysOffsetFromADateBindComparedWithADate() throws Exception {
        // 2024-06-01 minus 400 days is 2023-04-28: the two rows of 2024 are on or after it.
        assertEquals(2, count("created >= ? - 400", s -> s.setDate(1, Date.valueOf("2024-06-01"))));
        assertEquals(
                2,
                count(
                        "created < ? + 0",
                        s -> s.setTimestamp(1, Timestamp.valueOf("2021-01-01 00:00:00"))));
    }

    /** What Oracle returns for the same statements; the bind's setter decides date or number. */
    @Test
    void anOffsetOfADateBindIsDateArithmetic() throws Exception {
        assertEquals(
                Timestamp.valueOf("2026-02-01 00:00:00"),
                value("? + 1", s -> s.setDate(1, Date.valueOf("2026-01-31"))));
        assertEquals(
                Timestamp.valueOf("2026-02-28 06:00:00"),
                value("? - 1", s -> s.setTimestamp(1, Timestamp.valueOf("2026-03-01 06:00:00"))));
        assertEquals(
                Timestamp.valueOf("2026-03-01 18:00:00"),
                value("? + 0.5", s -> s.setTimestamp(1, Timestamp.valueOf("2026-03-01 06:00:00"))));
        assertEquals(
                Timestamp.valueOf("2026-03-01 00:00:00"),
                value("2 + ?", s -> s.setDate(1, Date.valueOf("2026-02-27"))));
    }

    @Test
    void anOffsetOfANumberBindIsANumber() throws Exception {
        assertEquals(
                0,
                new java.math.BigDecimal(42)
                        .compareTo((java.math.BigDecimal) value("? + 1", s -> s.setInt(1, 41))));
        assertEquals(
                0,
                new java.math.BigDecimal("10.25")
                        .compareTo(
                                (java.math.BigDecimal)
                                        value(
                                                "? - 0.25",
                                                s ->
                                                        s.setBigDecimal(
                                                                1,
                                                                new java.math.BigDecimal(
                                                                        "10.5")))));
        assertEquals(
                0,
                new java.math.BigDecimal(6)
                        .compareTo(
                                (java.math.BigDecimal) value("? + 1", s -> s.setString(1, "5"))));
    }

    @Test
    void theMetadataOfABindOffsetFollowsWhatWasBound() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT ? + 1 AS value FROM dual")) {
            statement.setDate(1, Date.valueOf("2026-01-31"));
            try (ResultSet result = statement.executeQuery()) {
                assertEquals(java.sql.Types.TIMESTAMP, result.getMetaData().getColumnType(1));
                assertEquals(7, result.getMetaData().getPrecision(1));
            }
            statement.setInt(1, 41);
            try (ResultSet result = statement.executeQuery()) {
                assertEquals(java.sql.Types.NUMERIC, result.getMetaData().getColumnType(1));
                assertEquals(0, result.getMetaData().getScale(1));
            }
        }
    }

    /** TRUNC(?) is a DATE or a NUMBER by what was bound, and the metadata agrees with it. */
    @Test
    void truncOfABindIsDescribedByWhatWasBound() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT TRUNC(?) AS value FROM dual")) {
            statement.setTimestamp(1, Timestamp.valueOf("2026-01-31 08:00:00"));
            try (ResultSet result = statement.executeQuery()) {
                assertEquals(java.sql.Types.TIMESTAMP, result.getMetaData().getColumnType(1));
                assertEquals("DATE", result.getMetaData().getColumnTypeName(1));
                assertEquals(7, result.getMetaData().getPrecision(1));
                result.next();
                assertEquals(Timestamp.valueOf("2026-01-31 00:00:00"), result.getTimestamp(1));
            }
            statement.setBigDecimal(1, new java.math.BigDecimal("7.9"));
            try (ResultSet result = statement.executeQuery()) {
                assertEquals(java.sql.Types.NUMERIC, result.getMetaData().getColumnType(1));
                result.next();
                assertEquals(0, new java.math.BigDecimal("7").compareTo(result.getBigDecimal(1)));
            }
        }
    }

    @Test
    void aNumberBindOffsetIsStillANumber() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM dbe_dates WHERE id = ? - 1")) {
            statement.setInt(1, 3);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                assertEquals(1, result.getInt(1));
            }
        }
    }

    /** Oracle 18c XE returns 6.5 for both; a long keeps every digit. */
    @Test
    void aFloatingPointOrLongBindPlusANumber() throws Exception {
        assertEquals("6.5", value("? + 1", s -> s.setDouble(1, 5.5)).toString());
        assertEquals("6.5", value("? + 1", s -> s.setFloat(1, 5.5f)).toString());
        assertEquals("4.5", value("? - 1", s -> s.setObject(1, 5.5)).toString());
        assertEquals(
                "9007199254740994",
                value("? + 1", s -> s.setLong(1, 9007199254740993L)).toString());
    }

    /** The first bind decides, as with a literal offset; a second bind is a number. */
    @Test
    void aBindPlusOrMinusABind() throws Exception {
        Timestamp moment = Timestamp.valueOf("2024-03-10 08:09:10.5");
        assertEquals(
                Timestamp.valueOf("2024-03-08 08:09:10"),
                value(
                        "? - ?",
                        s -> {
                            s.setTimestamp(1, moment);
                            s.setInt(2, 2);
                        }));
        assertEquals(
                Timestamp.valueOf("2024-03-12 08:09:10"),
                value(
                        "? + ?",
                        s -> {
                            s.setTimestamp(1, moment);
                            s.setInt(2, 2);
                        }));
        assertEquals(
                "7",
                value(
                                "? - ?",
                                s -> {
                                    s.setInt(1, 9);
                                    s.setInt(2, 2);
                                })
                        .toString());
        assertEquals(
                "7.5",
                value(
                                "? + ?",
                                s -> {
                                    s.setString(1, "5");
                                    s.setDouble(2, 2.5);
                                })
                        .toString());
    }

    /** Oracle returns an INTERVAL DAY TO SECOND, which Orafit does not have: refused. */
    @Test
    void theDifferenceOfTwoTimestampBindsIsRefused() {
        assertThrows(
                SQLException.class,
                () ->
                        value(
                                "? - ?",
                                s -> {
                                    s.setTimestamp(1, Timestamp.valueOf("2024-03-10 08:09:10"));
                                    s.setTimestamp(2, Timestamp.valueOf("2024-03-01 00:00:00"));
                                }));
    }
}
