package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Oracle sorts and compares character data by binary value. A database with another default
 * collation (Aurora and RDS default to en_US.UTF-8) orders differently without an error. The
 * connection is allowed with one warning; {@code orafit.collation=reject} refuses it and {@code
 * ignore} silences the warning.
 */
final class CollationCheckPostgresTest {
    private static final Logger LOGGER = Logger.getLogger("io.github.orafit");

    private static final class Captured extends Handler {
        final List<String> warnings = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record.getMessage());
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    private static void admin(String sql) throws Exception {
        String url = IntegrationSupport.jdbcUrl().replace("jdbc:orafit:", "jdbc:postgresql:");
        try (Connection connection =
                        DriverManager.getConnection(
                                url,
                                IntegrationSupport.jdbcUser(),
                                IntegrationSupport.jdbcPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection connect(String database, String mode) throws Exception {
        String url = IntegrationSupport.jdbcUrl().replaceFirst("/[^/?]+\\?", "/" + database + "?");
        Properties properties = new Properties();
        properties.setProperty("user", IntegrationSupport.jdbcUser());
        // A trust-authenticated server has no password, and Properties refuses null.
        if (IntegrationSupport.jdbcPassword() != null) {
            properties.setProperty("password", IntegrationSupport.jdbcPassword());
        }
        if (mode != null) properties.setProperty("orafit.collation", mode);
        return DriverManager.getConnection(url, properties);
    }

    /** Opens the database in a mode and returns what the driver warned about. */
    private static List<String> warningsOnConnect(String database, String mode) throws Exception {
        Captured captured = new Captured();
        LOGGER.addHandler(captured);
        try (Connection connection = connect(database, mode)) {
            assertTrue(connection.isValid(5));
            return captured.warnings;
        } finally {
            LOGGER.removeHandler(captured);
        }
    }

    private static void withDatabase(String name, String options, Body body) throws Exception {
        admin("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        admin("CREATE DATABASE " + name + " TEMPLATE template0 " + options);
        try {
            body.run(name);
        } finally {
            admin("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }

    private interface Body {
        void run(String database) throws Exception;
    }

    @Test
    void anIcuDatabaseConnectsAndWarnsOnce() throws Exception {
        withDatabase(
                "orafit_collation_icu",
                "LOCALE_PROVIDER icu ICU_LOCALE 'en-US'",
                database -> {
                    List<String> warnings = warningsOnConnect(database, null);
                    assertEquals(1, warnings.size(), warnings.toString());
                    assertTrue(warnings.get(0).contains("collation"), warnings.get(0));
                });
    }

    @Test
    void rejectRefusesIt() throws Exception {
        withDatabase(
                "orafit_collation_reject",
                "LOCALE_PROVIDER icu ICU_LOCALE 'en-US'",
                database -> {
                    SQLException refused =
                            assertThrows(SQLException.class, () -> connect(database, "reject"));
                    assertTrue(refused.getMessage().contains("collation"), refused.getMessage());
                    assertEquals("0A000", refused.getSQLState());
                });
    }

    @Test
    void ignoreConnectsWithoutAWarning() throws Exception {
        withDatabase(
                "orafit_collation_ignore",
                "LOCALE_PROVIDER icu ICU_LOCALE 'en-US'",
                database -> assertEquals(List.of(), warningsOnConnect(database, "ignore")));
    }

    /** A misspelt mode must not quietly fall back to the warning. */
    @Test
    void anUnknownModeIsRefused() throws Exception {
        withDatabase(
                "orafit_collation_typo",
                "LOCALE_PROVIDER icu ICU_LOCALE 'en-US'",
                database -> {
                    SQLException refused =
                            assertThrows(SQLException.class, () -> connect(database, "rejct"));
                    assertEquals("22023", refused.getSQLState());
                });
    }

    @Test
    void aLibcCDatabaseIsBinaryEvenWhenRejecting() throws Exception {
        withDatabase(
                "orafit_collation_c",
                "LOCALE_PROVIDER libc LOCALE 'C'",
                database -> {
                    assertEquals(List.of(), warningsOnConnect(database, null));
                    assertEquals(List.of(), warningsOnConnect(database, "reject"));
                });
    }

    /** PostgreSQL 17 introduced the builtin provider. */
    @Test
    void aBuiltinDatabaseIsBinaryEvenWhenRejecting() throws Exception {
        try (Connection connection = IntegrationSupport.open()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseMajorVersion() >= 17);
        }
        withDatabase(
                "orafit_collation_builtin",
                "LOCALE_PROVIDER builtin LOCALE 'C.UTF-8'",
                database -> {
                    assertEquals(List.of(), warningsOnConnect(database, null));
                    assertEquals(List.of(), warningsOnConnect(database, "reject"));
                });
    }
}
