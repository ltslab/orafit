package io.github.orafit.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.Translation;

import org.junit.jupiter.api.Test;

/** Oracle's FROM is optional in DELETE; PostgreSQL requires it. */
final class DeleteWithoutFromTest {
    private final OrafitEngine engine = new OrafitEngine();

    @Test
    void fromIsAdded() throws Exception {
        Translation translation = engine.translate("DELETE t WHERE id = 1");
        assertEquals("DELETE FROM t WHERE id = 1", translation.sql());
        assertTrue(translation.rewritten());
    }

    @Test
    void aliasAndMissingWhereAreKept() throws Exception {
        assertEquals(
                "DELETE FROM t d WHERE d.id = 1",
                engine.translate("DELETE t d WHERE d.id = 1").sql());
        assertEquals("DELETE FROM t", engine.translate("DELETE t").sql());
    }

    @Test
    void deleteWithFromIsUntouched() throws Exception {
        Translation translation = engine.translate("DELETE FROM t WHERE id = 1");
        assertEquals("DELETE FROM t WHERE id = 1", translation.sql());
    }
}
