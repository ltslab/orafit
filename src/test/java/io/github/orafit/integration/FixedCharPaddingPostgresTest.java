package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * A CHAR(n) column keeps its blank padding in concatenation, LIKE, LPAD, RPAD and SUBSTR. The
 * expected values are what Oracle 18c XE returns for the same statements.
 */
final class FixedCharPaddingPostgresTest {
    @BeforeAll
    static void createTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS fcp_codes");
            statement.execute(
                    "CREATE TABLE fcp_codes (id integer PRIMARY KEY, code char(3), text3"
                            + " varchar(3))");
            statement.execute("INSERT INTO fcp_codes VALUES (1, 'A', 'A'), (3, NULL, 'C')");
        }
    }

    @AfterAll
    static void dropTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS fcp_codes");
        }
    }

    private static Object value(String expression) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "SELECT " + expression + " FROM fcp_codes WHERE id = 1")) {
            result.next();
            return result.getObject(1);
        }
    }

    private static int count(String condition) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM fcp_codes WHERE " + condition)) {
            result.next();
            return result.getInt(1);
        }
    }

    @Test
    void concatenationKeepsThePadding() throws Exception {
        assertEquals("|A  ", value("'|' || code"));
        assertEquals("A  |", value("code || '|'"));
        assertEquals("A  A  ", value("code || code"));
        assertEquals("A  A", value("code || text3"));
        assertEquals(0, new BigDecimal(4).compareTo((BigDecimal) value("LENGTH('|' || code)")));
    }

    @Test
    void likeSeesThePaddedValue() throws Exception {
        assertEquals(0, count("code LIKE 'A'"));
        assertEquals(1, count("code LIKE 'A_ '"));
    }

    @Test
    void lpadRpadAndSubstrSeeThePaddedValue() throws Exception {
        assertEquals("**A  ", value("LPAD(code, 5, '*')"));
        assertEquals("A  **", value("RPAD(code, 5, '*')"));
        assertEquals("  ", value("SUBSTR(code, 2, 2)"));
    }

    /** Oracle: CHAR || CHAR is a CHAR as wide as both; with a VARCHAR2 it is a VARCHAR2. */
    @Test
    void resultsAreDescribedLikeOracle() throws Exception {
        assertDescribedAs("'|' || code", java.sql.Types.CHAR, 4);
        assertDescribedAs("code || '|'", java.sql.Types.CHAR, 4);
        assertDescribedAs("code || code", java.sql.Types.CHAR, 6);
        assertDescribedAs("code || text3", java.sql.Types.VARCHAR, 6);
        assertDescribedAs("SUBSTR(code, 2, 2)", java.sql.Types.VARCHAR, 2);
    }

    private static void assertDescribedAs(String expression, int type, int precision)
            throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery("SELECT " + expression + " FROM fcp_codes")) {
            assertEquals(type, result.getMetaData().getColumnType(1), expression);
            assertEquals(precision, result.getMetaData().getPrecision(1), expression);
        }
    }

    @Test
    void aVarcharColumnIsUntouched() throws Exception {
        assertEquals("A|", value("text3 || '|'"));
        assertEquals(1, count("text3 LIKE 'A'"));
    }
}
