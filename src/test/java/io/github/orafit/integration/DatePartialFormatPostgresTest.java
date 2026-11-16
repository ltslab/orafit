package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Omitted fields and two-digit years follow Oracle: the current date fills what is missing. */
final class DatePartialFormatPostgresTest {
    private static LocalDateTime date(String expression) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT " + expression + " FROM dual")) {
            result.next();
            return result.getTimestamp(1).toLocalDateTime();
        }
    }

    private static String text(String expression) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT " + expression + " FROM dual")) {
            result.next();
            return result.getString(1);
        }
    }

    private final LocalDate today = LocalDate.now();
    private final int century = today.getYear() / 100 * 100;

    @Test
    void twoDigitYearUsesTheCurrentCentury() throws Exception {
        assertEquals(
                LocalDateTime.of(century + 99, 1, 1, 0, 0),
                date("TO_DATE('99-01-01', 'YY-MM-DD')"));
        assertEquals(
                LocalDateTime.of(century + 26, 1, 15, 0, 0), date("TO_DATE('260115', 'YYMMDD')"));
        assertEquals(
                LocalDateTime.of(century + 1, 1, 1, 0, 0), date("TO_DATE('1-01-01', 'YY-MM-DD')"));
        assertEquals(
                LocalDateTime.of(century, 6, 30, 0, 0), date("TO_DATE('00-06-30', 'YY-MM-DD')"));
    }

    @Test
    void omittedYearAndMonthComeFromTheCurrentDate() throws Exception {
        assertEquals(
                LocalDateTime.of(today.getYear(), 1, 15, 0, 0), date("TO_DATE('01-15', 'MM-DD')"));
        assertEquals(LocalDateTime.of(today.getYear(), 3, 1, 0, 0), date("TO_DATE('03', 'MM')"));
        assertEquals(
                LocalDateTime.of(2024, today.getMonthValue(), 1, 0, 0),
                date("TO_DATE('2024', 'YYYY')"));
        assertEquals(
                LocalDateTime.of(today.getYear(), today.getMonthValue(), 1, 10, 20, 30),
                date("TO_DATE('10:20:30', 'HH24:MI:SS')"));
        assertEquals(LocalDateTime.of(2024, 3, 1, 0, 0), date("TO_DATE('2024-03', 'YYYY-MM')"));
    }

    @Test
    void fractionalSecondElementsTruncateToTheRequestedDigits() throws Exception {
        String ts = "TIMESTAMP '2024-01-02 03:04:05.123956'";
        assertEquals("1", text("orafit.to_char_format(" + ts + ", 'FF1')"));
        assertEquals("12", text("orafit.to_char_format(" + ts + ", 'FF2')"));
        assertEquals("123", text("orafit.to_char_format(" + ts + ", 'FF3')"));
        assertEquals("1239", text("orafit.to_char_format(" + ts + ", 'FF4')"));
        assertEquals("12395", text("orafit.to_char_format(" + ts + ", 'FF5')"));
        assertEquals("123956", text("orafit.to_char_format(" + ts + ", 'FF6')"));
        assertEquals("123956000", text("orafit.to_char_format(" + ts + ", 'FF9')"));
    }
}
