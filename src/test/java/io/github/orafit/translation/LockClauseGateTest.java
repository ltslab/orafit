package io.github.orafit.translation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.orafit.OrafitEngine;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** PostgreSQL has no per-statement lock wait time, so Oracle WAIT n must be rejected by name. */
final class LockClauseGateTest {
    private final OrafitEngine engine = new OrafitEngine();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM emp WHERE id = ? FOR UPDATE WAIT 5",
                "SELECT * FROM emp e FOR UPDATE OF e.sal WAIT 10",
                "select * from emp for update wait 1"
            })
    void rejectsWaitWithATimeout(String sql) {
        TranslationException failure =
                assertThrows(TranslationException.class, () -> engine.translate(sql));

        assertEquals("FOR_UPDATE_WAIT", failure.code());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM emp FOR UPDATE",
                "SELECT * FROM emp FOR UPDATE NOWAIT",
                "SELECT * FROM emp FOR UPDATE SKIP LOCKED",
                "SELECT 'FOR UPDATE WAIT 5' FROM emp"
            })
    void keepsOtherLockClausesAndLiterals(String sql) {
        assertDoesNotThrow(() -> engine.translate(sql));
    }
}
