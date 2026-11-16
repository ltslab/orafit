package io.github.orafit.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.orafit.OrafitEngine;
import io.github.orafit.translation.Translation;
import io.github.orafit.translation.TranslationException;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

final class TranslationCacheTest {
    private static Translation translation(String sql) throws SQLException {
        try {
            return new OrafitEngine().translate(sql);
        } catch (TranslationException failure) {
            throw new SQLException(failure);
        }
    }

    @Test
    void translatesEachStatementTextOnce() throws Exception {
        TranslationCache cache = new TranslationCache(8);
        AtomicInteger loads = new AtomicInteger();

        Translation first =
                cache.get(
                        "SELECT SYSDATE FROM dual",
                        sql -> {
                            loads.incrementAndGet();
                            return translation(sql);
                        });
        Translation second = cache.get("SELECT SYSDATE FROM dual", sql -> translation(sql));

        assertSame(first, second);
        assertEquals(1, loads.get());
    }

    @Test
    void evictsTheLeastRecentlyUsedEntryWhenFull() throws Exception {
        TranslationCache cache = new TranslationCache(2);
        AtomicInteger loads = new AtomicInteger();
        TranslationCache.Loader counting =
                sql -> {
                    loads.incrementAndGet();
                    return translation(sql);
                };

        cache.get("SELECT 1 FROM dual", counting);
        cache.get("SELECT 2 FROM dual", counting);
        cache.get("SELECT 1 FROM dual", counting); // refresh 1, so 2 is the eldest
        cache.get("SELECT 3 FROM dual", counting); // evicts 2
        cache.get("SELECT 1 FROM dual", counting); // still cached
        cache.get("SELECT 2 FROM dual", counting); // reloaded

        assertEquals(4, loads.get());
    }

    @Test
    void doesNotRememberFailures() throws Exception {
        TranslationCache cache = new TranslationCache(2);
        AtomicInteger loads = new AtomicInteger();

        assertThrows(
                SQLException.class,
                () ->
                        cache.get(
                                "BAD",
                                sql -> {
                                    loads.incrementAndGet();
                                    throw new SQLException("rejected");
                                }));
        Translation ok =
                cache.get(
                        "BAD",
                        sql -> {
                            loads.incrementAndGet();
                            return translation("SELECT 1 FROM dual");
                        });

        assertEquals(2, loads.get());
        assertEquals("SELECT 1 FROM orafit.dual", ok.sql());
    }
}
