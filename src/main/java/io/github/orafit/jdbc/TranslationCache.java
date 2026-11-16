package io.github.orafit.jdbc;

import io.github.orafit.translation.Translation;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small least-recently-used cache of translations for one connection.
 *
 * <p>A {@link Translation} is immutable and depends only on the statement text and on the column
 * types the connection already caches, so it is safe to reuse for the life of that connection.
 * Failures are never cached.
 */
final class TranslationCache {
    @FunctionalInterface
    interface Loader {
        Translation load(String sql) throws SQLException;
    }

    private final Map<String, Translation> entries;

    TranslationCache(int maxEntries) {
        this.entries =
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Translation> eldest) {
                        return size() > maxEntries;
                    }
                };
    }

    Translation get(String sql, Loader loader) throws SQLException {
        synchronized (entries) {
            Translation cached = entries.get(sql);
            if (cached != null) return cached;
        }
        Translation loaded = loader.load(sql);
        synchronized (entries) {
            entries.put(sql, loaded);
        }
        return loaded;
    }
}
