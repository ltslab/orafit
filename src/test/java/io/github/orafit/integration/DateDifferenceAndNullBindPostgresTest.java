package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** DATE subtraction over computed DATE operands, and untyped binds in IS NULL tests. */
final class DateDifferenceAndNullBindPostgresTest {
    private static BigDecimal number(String sql) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getBigDecimal(1);
        }
    }

    @Test
    void computedDateOperandsSubtractToDays() throws Exception {
        assertEquals(
                0,
                new BigDecimal(5)
                        .compareTo(
                                number(
                                        "SELECT (DATE '2024-03-01' + 5) - DATE '2024-03-01'"
                                                + " FROM dual")));
        assertEquals(
                0,
                BigDecimal.ONE.compareTo(
                        number(
                                "SELECT TRUNC(TIMESTAMP '2024-03-02 10:00:00') - DATE"
                                        + " '2024-03-01' FROM dual")));
        assertEquals(
                0,
                new BigDecimal(3)
                        .compareTo(
                                number(
                                        "SELECT DATE '2024-03-05' - (DATE '2024-03-05' - 3)"
                                                + " FROM dual")));
        assertEquals(
                0,
                BigDecimal.ZERO.compareTo(
                        number("SELECT TRUNC(SYSDATE) - TRUNC(SYSDATE) FROM dual")));
        assertEquals(
                1,
                number(
                                "SELECT COUNT(*) FROM dual WHERE TRUNC(SYSDATE) - DATE '2000-01-01'"
                                        + " > 1")
                        .intValue());
    }

    @Test
    void bareBindIsNullDoesNotNeedAType() throws Exception {
        try (Connection connection = IntegrationSupport.open()) {
            for (String value : new String[] {null, "abc", ""}) {
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM dual WHERE (? IS NULL OR 'abc' = ?)")) {
                    statement.setString(1, value);
                    statement.setString(2, value);
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        // NULL and the empty string are NULL in Oracle, so the filter is skipped.
                        assertEquals(
                                value == null || value.isEmpty() || value.equals("abc") ? 1 : 0,
                                result.getInt(1));
                    }
                }
            }
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT COUNT(*) FROM dual WHERE ? IS NOT NULL")) {
                statement.setInt(1, 7);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    assertEquals(1, result.getInt(1));
                }
            }
        }
    }
}
