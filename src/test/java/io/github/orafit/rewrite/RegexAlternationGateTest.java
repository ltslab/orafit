package io.github.orafit.rewrite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Oracle tries alternatives left to right; PostgreSQL returns the longest match. They agree only
 * when the alternatives cannot match at the same position, so anything else is rejected.
 */
final class RegexAlternationGateTest {
    private final OrafitEngine engine = new OrafitEngine();

    private String lower(String pattern) throws TranslationException {
        return engine.translate("SELECT REGEXP_SUBSTR('abcd', '" + pattern + "') FROM dual").sql();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "a|ab|abc",
                "^(a|ab)$",
                "x(foo|foobar)y",
                "^(a[0-9]|b)$",
                "x(a|)y",
                "(a|(b|bc))",
                "(ab*|c)",
                "a|b+"
            })
    void rejectsAlternationThatCanMatchTheSamePositionDifferently(String pattern) {
        TranslationException failure =
                assertThrows(TranslationException.class, () -> lower(pattern));

        assertEquals("REGEXP_PATTERN", failure.code());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "abc",
                "^(Mon|Tue|Wed)$",
                "(abc|abd)[0-9]+",
                "[a|b]",
                "a\\|b",
                "(ab)+",
                "^(x|y)(1|2)$"
            })
    void keepsPatternsWithoutAmbiguousAlternation(String pattern) {
        assertDoesNotThrow(() -> lower(pattern));
    }
}
