package io.github.orafit.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.api.Test;

/**
 * CHAR(n CHAR) uses character length semantics. CAST(... AS CHAR(n CHAR)) must lower to
 * orafit.cast_char(..., n, FALSE). CHAR(n BYTE) and unqualified CHAR(n) keep byte semantics (TRUE).
 * CHAR(n CHAR) outside a CAST is rejected rather than silently treated as bytes.
 */
public final class CharLengthSemanticsTest {
    private final OrafitEngine engine = new OrafitEngine();

    @Test
    void charLengthCastLowersToCharacterSemantics() throws Exception {
        String sql = engine.translate("SELECT CAST('É' AS CHAR(2 CHAR)) AS v FROM dual").sql();
        assertTrue(sql.matches("(?is).*orafit\\.cast_char\\(.*,\\s*2,\\s*FALSE\\).*"), sql);
    }

    @Test
    void byteAndUnqualifiedCastsKeepByteSemantics() throws Exception {
        String bytes = engine.translate("SELECT CAST('É' AS CHAR(2 BYTE)) AS v FROM dual").sql();
        String plain = engine.translate("SELECT CAST('É' AS CHAR(2)) AS v FROM dual").sql();
        assertTrue(bytes.matches("(?is).*orafit\\.cast_char\\(.*,\\s*2,\\s*TRUE\\).*"), bytes);
        assertTrue(plain.matches("(?is).*orafit\\.cast_char\\(.*,\\s*2,\\s*TRUE\\).*"), plain);
    }

    @Test
    void charLengthInsideALiteralIsUntouched() throws Exception {
        String sql = engine.translate("SELECT 'CHAR(2 CHAR)' AS v FROM dual").sql();
        assertTrue(sql.contains("'CHAR(2 CHAR)'"), sql);
    }

    @Test
    void charLengthOutsideCastIsRejected() {
        TranslationException exception =
                assertThrows(
                        TranslationException.class,
                        () -> engine.translate("CREATE TABLE t (c CHAR(2 CHAR))"));
        assertEquals("CHAR_LENGTH_SEMANTICS", exception.code());
    }
}
