package io.github.orafit.translation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.orafit.OrafitEngine;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Oracle JDBC runs one statement per call; a second statement is ORA-00933, never silent. */
final class StatementShapeGateTest {
    private final OrafitEngine engine = new OrafitEngine();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT 1 FROM dual; DELETE FROM emp",
                "SELECT 1 FROM emp; DELETE FROM emp",
                "DELETE FROM emp WHERE id = ?; DELETE FROM emp",
                "UPDATE emp SET a = 1; COMMIT"
            })
    void rejectsAdditionalStatements(String sql) {
        TranslationException failure =
                assertThrows(TranslationException.class, () -> engine.translate(sql));

        assertEquals("MULTI_STATEMENT", failure.code());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT 1 FROM emp;",
                "SELECT ';' FROM emp",
                "SELECT 1 FROM emp -- ; DELETE FROM emp",
                "SELECT 1 FROM emp /* ; DELETE */",
                "SELECT q'[;]' FROM dual",
                "BEGIN pkg.proc(?); END;",
                "CREATE OR REPLACE PROCEDURE p AS BEGIN NULL; END;"
            })
    void keepsSingleStatementsAndProgramUnits(String sql) {
        assertDoesNotThrow(() -> engine.translate(sql));
    }
}
