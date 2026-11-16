package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;

/** Oracle-visible behavior verified against Oracle 18c XE before it was encoded here. */
final class OracleRuntimeSemanticsPostgresTest {
    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    @Test
    void instrWithStartPositionZeroReturnsZero() throws Exception {
        try (Connection connection = IntegrationSupport.open()) {
            assertEquals("0", scalar(connection, "SELECT INSTR('abc', 'b', 0) FROM dual"));
        }
    }

    @Test
    void sysdateTruncatesFractionalSecondsInsteadOfRounding() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            // A rounded-up SYSDATE would be later than the clock for roughly half of the
            // statement start times, so differing fractions expose it.
            for (int i = 0; i < 12; i++) {
                try (ResultSet result =
                        statement.executeQuery(
                                "SELECT orafit.sysdate() > clock_timestamp()::timestamp")) {
                    result.next();
                    assertFalse(result.getBoolean(1), "SYSDATE must never be ahead of the clock");
                }
                Thread.sleep(83);
            }
        }
    }

    @Test
    void toTimestampRejectsTrailingInputWithOra01830() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            SQLException error =
                    assertThrows(
                            SQLException.class,
                            () ->
                                    statement.executeQuery(
                                            "SELECT TO_TIMESTAMP('2024-01-15 10:20:30xyz',"
                                                    + " 'YYYY-MM-DD HH24:MI:SS') FROM dual"));
            assertEquals(1830, error.getErrorCode());
        }
    }

    @Test
    void paddingOfFullWidthCharactersFailsClosed() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            SQLException error =
                    assertThrows(
                            SQLException.class,
                            () -> statement.executeQuery("SELECT LPAD('あ', 5, '*') FROM dual"));
            assertEquals("0A000", error.getSQLState());
            assertEquals("***ab", scalar(connection, "SELECT LPAD('ab', 5, '*') FROM dual"));
        }
    }

    @Test
    void fractionalSecondFormatKeepsTheRequestedDigits() throws Exception {
        try (Connection connection = IntegrationSupport.open()) {
            assertEquals(
                    "03:04:05.123",
                    scalar(
                            connection,
                            "SELECT orafit.to_char_format(TIMESTAMP '2024-01-02 03:04:05.123456',"
                                    + " 'HH24:MI:SS.FF3') FROM dual"));
        }
    }

    @Test
    void currentDateCarriesTheTimeOfDay() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT CURRENT_DATE FROM dual")) {
            result.next();
            assertInstanceOf(Timestamp.class, result.getObject(1));
        }
    }

    @Test
    void additionalStatementsAreRejectedWithOra00933() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            SQLException error =
                    assertThrows(
                            SQLException.class,
                            () -> statement.execute("SELECT 1 FROM dual; SELECT 2 FROM dual"));
            assertEquals(933, error.getErrorCode());
        }
    }

    @Test
    void dateColumnArithmeticUsesDaysLikeOracle() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sem_date_arith (d date)");
            try {
                statement.execute("INSERT INTO sem_date_arith VALUES (DATE '2024-01-31')");
                assertEquals(
                        "2024-02-01 00:00:00",
                        scalar(connection, "SELECT d + 1 FROM sem_date_arith"));
                assertEquals(
                        "2024-01-30 00:00:00",
                        scalar(connection, "SELECT d - 1 FROM sem_date_arith"));
            } finally {
                statement.execute("DROP TABLE sem_date_arith");
            }
        }
    }

    @Test
    void constraintAndLockFailuresCarryOracleVendorCodes() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE sem_errors (id numeric(5) PRIMARY KEY, v varchar(3) NOT NULL)");
            try {
                statement.execute("INSERT INTO sem_errors VALUES (1, 'a')");
                assertEquals(
                        1,
                        assertThrows(
                                        SQLException.class,
                                        () ->
                                                statement.execute(
                                                        "INSERT INTO sem_errors VALUES (1, 'b')"))
                                .getErrorCode());
                assertEquals(
                        1400,
                        assertThrows(
                                        SQLException.class,
                                        () ->
                                                statement.execute(
                                                        "INSERT INTO sem_errors VALUES (2, NULL)"))
                                .getErrorCode());
                assertEquals(
                        12899,
                        assertThrows(
                                        SQLException.class,
                                        () ->
                                                statement.execute(
                                                        "INSERT INTO sem_errors VALUES (3, 'abcd')"))
                                .getErrorCode());
            } finally {
                statement.execute("DROP TABLE sem_errors");
            }
        }
    }

    @Test
    void queryTimeoutIsAnOra01013SqlTimeoutException() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement = connection.prepareStatement("SELECT pg_sleep(2)")) {
            statement.setQueryTimeout(1);
            SQLException error = assertThrows(SQLException.class, statement::executeQuery);
            assertInstanceOf(SQLTimeoutException.class, error);
            assertEquals(1013, error.getErrorCode());
        }
    }

    @Test
    void emptyStringObjectBindIsStoredAsNull() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sem_empty (id numeric(3), v varchar(5))");
            try (PreparedStatement insert =
                    connection.prepareStatement("INSERT INTO sem_empty VALUES (?, ?)")) {
                insert.setInt(1, 1);
                insert.setObject(2, "");
                insert.executeUpdate();
                insert.setInt(1, 2);
                insert.setObject(2, "", Types.VARCHAR);
                insert.executeUpdate();
                assertEquals(
                        "2", scalar(connection, "SELECT COUNT(*) FROM sem_empty WHERE v IS NULL"));
            } finally {
                statement.execute("DROP TABLE sem_empty");
            }
        }
    }

    @Test
    void multiRowReturningRollsTheStatementBack() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE sem_returning (id numeric(3), grp numeric(3), v varchar(5))");
            try {
                statement.execute("INSERT INTO sem_returning VALUES (1, 1, 'a'), (2, 1, 'a')");
                try (CallableStatement call =
                        connection.prepareCall(
                                "UPDATE sem_returning SET v = ? WHERE grp = ? RETURNING id INTO ?")) {
                    call.setString(1, "z");
                    call.setInt(2, 1);
                    call.registerOutParameter(3, Types.NUMERIC);
                    SQLException error = assertThrows(SQLException.class, call::executeUpdate);
                    assertEquals(1422, error.getErrorCode());
                }
                assertEquals(
                        "2",
                        scalar(connection, "SELECT COUNT(*) FROM sem_returning WHERE v = 'a'"));
            } finally {
                statement.execute("DROP TABLE sem_returning");
            }
        }
    }
}
