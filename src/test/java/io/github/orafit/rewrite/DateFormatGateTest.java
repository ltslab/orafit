package io.github.orafit.rewrite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Formats whose Oracle meaning PostgreSQL cannot reproduce must be rejected, not approximated. */
final class DateFormatGateTest {
    private final OrafitEngine engine = new OrafitEngine();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT TO_DATE('99-01-01', 'YY-MM-DD') FROM dual",
                "SELECT TO_DATE('2024', 'YYYY') FROM dual",
                "SELECT TO_DATE('12:30', 'HH24:MI') FROM dual",
                "SELECT TO_DATE('01-15', 'MM-DD') FROM dual",
                "SELECT TO_TIMESTAMP('2024-01-01 00:00:00.123456789', 'YYYY-MM-DD HH24:MI:SS.FF9') FROM dual",
                "SELECT TO_TIMESTAMP('2024-01-01 00:00:00.1', 'YYYY-MM-DD HH24:MI:SS.FF') FROM dual",
                "SELECT TO_TIMESTAMP('2024-01-01 00:00:00.1', 'YYYY-MM-DD HH24:MI:SS.FF1') FROM dual"
            })
    void rejectsFormatsWithOracleSpecificDefaults(String sql) {
        TranslationException failure =
                assertThrows(TranslationException.class, () -> engine.translate(sql));

        assertTrue(failure.code().endsWith("_FORMAT"), failure.code());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT TO_DATE('2024-01', 'YYYY-MM') FROM dual",
                "SELECT TO_DATE('20240115', 'YYYYMMDD') FROM dual",
                "SELECT TO_TIMESTAMP('2024-01-01 00:00:00.123', 'YYYY-MM-DD HH24:MI:SS.FF3') FROM dual",
                "SELECT TO_TIMESTAMP('2024-01-01 00:00:00.123456', 'YYYY-MM-DD HH24:MI:SS.FF6') FROM dual"
            })
    void keepsFormatsThatNameYearAndMonthAndSupportedFractions(String sql) {
        assertDoesNotThrow(() -> engine.translate(sql));
    }

    @Test
    void lowersCurrentDateToTheSessionZoneDateWithTime() throws Exception {
        String sql = engine.translate("SELECT CURRENT_DATE FROM dual").sql();

        assertEquals("SELECT orafit.sysdate() FROM orafit.dual", sql);
    }
}
