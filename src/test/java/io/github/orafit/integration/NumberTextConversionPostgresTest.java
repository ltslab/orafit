package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** NUMBER text conversions verified against Oracle 18c XE. */
final class NumberTextConversionPostgresTest {
    private static String scalar(String sql) throws SQLException, Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static SQLException failure(String sql) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            return assertThrows(SQLException.class, () -> statement.executeQuery(sql));
        }
    }

    @Test
    void toNumberOverflowIsOra01426() throws Exception {
        assertEquals(1426, failure("SELECT TO_NUMBER('1e126') FROM dual").getErrorCode());
        assertEquals(1426, failure("SELECT TO_NUMBER('-1e126') FROM dual").getErrorCode());
    }

    @Test
    void toNumberUnderflowIsZeroAndLongInputRoundsToThirtyEightDigits() throws Exception {
        assertEquals("0", scalar("SELECT TO_NUMBER('1e-131') FROM dual"));
        assertEquals(
                "12345678901234567890123456789012345678900000",
                scalar(
                        "SELECT TO_NUMBER('12345678901234567890123456789012345678901234') FROM dual"));
    }

    @Test
    void toCharKeepsPlainNotationUpToFortyCharacters() throws Exception {
        assertEquals(
                "1000000000000000000000000000000000000000",
                scalar("SELECT TO_CHAR(1000000000000000000000000000000000000000) FROM dual"));
        assertEquals(
                ".000000000000000000000000000000000000001",
                scalar("SELECT TO_CHAR(0.000000000000000000000000000000000000001) FROM dual"));
    }

    @Test
    void toCharBeyondFortyCharactersFailsClosedInsteadOfPrintingPlainDigits() throws Exception {
        // Oracle switches to scientific notation (1.0E+40); PostgreSQL has no equivalent.
        assertEquals(
                "0A000",
                failure("SELECT TO_CHAR(10000000000000000000000000000000000000000) FROM dual")
                        .getSQLState());
        assertEquals(
                "0A000",
                failure("SELECT TO_CHAR(-1000000000000000000000000000000000000000) FROM dual")
                        .getSQLState());
        assertEquals(
                "0A000",
                failure("SELECT TO_CHAR(0.0000000000000000000000000000000000000001) FROM dual")
                        .getSQLState());
    }
}
