package io.github.orafit.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Oracle compares and sorts character data by binary value (NLS_SORT and NLS_COMP are BINARY by
 * default). A PostgreSQL database whose default collation orders differently (en_US, an ICU locale)
 * changes ORDER BY, comparisons, MIN/MAX and pagination without raising anything. {@code C}, {@code
 * POSIX}, {@code C.UTF-8} and the builtin provider sort by code point, which is the byte order of
 * AL32UTF8.
 *
 * <p>Managed PostgreSQL (Aurora, RDS) defaults to en_US.UTF-8, so the connection is allowed and the
 * difference is logged once. {@code orafit.collation} (a connection property or a URL parameter) is
 * {@code warn} (the default), {@code reject} to refuse the connection, or {@code ignore} to say
 * nothing.
 */
final class CollationCheck {
    static final String PROPERTY = "orafit.collation";

    private static final Logger LOGGER = Logger.getLogger("io.github.orafit.jdbc");
    private static final Set<String> MODES = Set.of("warn", "reject", "ignore");
    private static final Set<String> BINARY_LOCALES =
            Set.of("C", "POSIX", "C.UTF-8", "C.UTF8", "C.utf8");

    private CollationCheck() {}

    static void check(Connection connection, String url, Properties info) throws SQLException {
        String mode = mode(url, info);
        if (!MODES.contains(mode)) {
            connection.close();
            throw new SQLException(
                    PROPERTY + " must be warn, reject or ignore, not " + mode + ".", "22023");
        }
        if (mode.equals("ignore")) return;
        String provider;
        String collate;
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT datlocprovider::text, datcollate FROM pg_catalog.pg_database"
                                        + " WHERE datname = pg_catalog.current_database()");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) return;
            provider = result.getString(1);
            collate = result.getString(2);
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
        // 'b' is PostgreSQL's builtin provider (C and C.UTF-8 only); 'c' is libc; 'i' is ICU.
        if ("b".equals(provider)
                || ("c".equals(provider) && collate != null && BINARY_LOCALES.contains(collate))) {
            return;
        }
        String difference =
                "The database default collation is "
                        + ("i".equals(provider) ? "an ICU locale" : "the locale " + collate)
                        + ", which does not sort like Oracle's binary NLS_SORT: ORDER BY, comparisons,"
                        + " MIN/MAX and pagination over character data can differ from Oracle. A"
                        + " database created with LOCALE_PROVIDER libc LOCALE 'C' (or builtin"
                        + " C.UTF-8) orders like Oracle.";
        if (mode.equals("reject")) {
            connection.close();
            throw new SQLFeatureNotSupportedException(difference, "0A000");
        }
        LOGGER.warning(
                difference
                        + " Set "
                        + PROPERTY
                        + "=reject to refuse such a database, or "
                        + PROPERTY
                        + "=ignore to silence this warning.");
    }

    private static String mode(String url, Properties info) {
        String value = info == null ? null : info.getProperty(PROPERTY);
        if (value == null && url != null) {
            int query = url.indexOf('?');
            if (query >= 0) {
                for (String pair : url.substring(query + 1).split("&")) {
                    if (pair.startsWith(PROPERTY + "=")) {
                        value = pair.substring(PROPERTY.length() + 1);
                    }
                }
            }
        }
        return value == null ? "warn" : value.trim().toLowerCase(Locale.ROOT);
    }
}
