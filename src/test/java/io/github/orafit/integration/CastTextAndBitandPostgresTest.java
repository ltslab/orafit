package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** The expected values below are what Oracle 18c XE returns for the same statements. */
final class CastTextAndBitandPostgresTest {
    @BeforeAll
    static void createTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS ctb_rows");
            statement.execute(
                    "CREATE TABLE ctb_rows (id integer PRIMARY KEY, amount numeric(10,2), name"
                            + " varchar(10))");
            statement.execute("INSERT INTO ctb_rows VALUES (1, 1000.50, 'Alice')");
        }
    }

    @AfterAll
    static void dropTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS ctb_rows");
        }
    }

    private static Object value(String expression) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "SELECT " + expression + " FROM ctb_rows WHERE id = 1")) {
            result.next();
            return result.getObject(1);
        }
    }

    @Test
    void castOfANumberToVarchar2UsesOracleNumberText() throws Exception {
        assertEquals("1000.5", value("CAST(amount AS VARCHAR2(20))"));
        assertEquals(".5", value("CAST(0.5 AS VARCHAR2(10))"));
        assertEquals("-.5", value("CAST(-0.5 AS VARCHAR2(10))"));
        assertEquals("1", value("CAST(id AS VARCHAR2(5))"));
        assertEquals("12.5", value("CAST(amount - 988 AS VARCHAR2(30))"));
        assertEquals(".0000000001", value("CAST(1E-10 AS VARCHAR2(20))"));
        assertEquals("100000000000000000000", value("CAST(1E20 AS VARCHAR2(40))"));
        assertNull(value("CAST(CAST(NULL AS NUMBER) AS VARCHAR2(5))"));
    }

    @Test
    void castOfATextToVarchar2IsUntouched() throws Exception {
        assertEquals("Alice", value("CAST(name AS VARCHAR2(10))"));
        assertEquals("abc", value("CAST('abc' AS VARCHAR2(10))"));
    }

    private static void bitand(long expected, String expression) throws Exception {
        assertEquals(0, BigDecimal.valueOf(expected).compareTo((BigDecimal) value(expression)));
    }

    @Test
    void bitandIsTheBitwiseAnd() throws Exception {
        bitand(2, "BITAND(6, 3)");
        bitand(255, "BITAND(-1, 255)");
        bitand(-1, "BITAND(-1, -1)");
        bitand(1099511627776L, "BITAND(1099511627776, 1099511627777)");
        bitand(2, "BITAND(id + 5, 3)");
        assertNull(value("BITAND(6, NULL)"));
    }

    /** Oracle describes BITAND of literals as an unconstrained NUMBER, with a column as scale 0. */
    @Test
    void bitandIsDescribedLikeOracle() throws Exception {
        assertEquals(-127, scaleOf("BITAND(6, 3)"));
        assertEquals(-127, scaleOf("BITAND('6', 3.9)"));
        assertEquals(0, scaleOf("BITAND(id, 3)"));
    }

    private static int scaleOf(String expression) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery("SELECT " + expression + " FROM ctb_rows")) {
            assertEquals(java.sql.Types.NUMERIC, result.getMetaData().getColumnType(1));
            assertEquals(0, result.getMetaData().getPrecision(1));
            return result.getMetaData().getScale(1);
        }
    }

    /** Oracle truncates toward zero and reads a numeric string as a number. */
    @Test
    void bitandTruncatesFractionsAndReadsStrings() throws Exception {
        bitand(2, "BITAND(6.5, 3)");
        bitand(2, "BITAND(-6.5, 3)");
        bitand(2, "BITAND(6, 3.9)");
        bitand(2, "BITAND('6', 3)");
    }

    /** Oracle's result beyond 64 bits is undefined, so it is refused rather than approximated. */
    @Test
    void bitandRefusesWhatSixtyFourBitsCannotHold() {
        SQLException refused =
                assertThrows(SQLException.class, () -> value("BITAND(9223372036854775808, 1)"));
        assertEquals("0A000", refused.getSQLState());
    }

    /** Oracle 18c XE raises ORA-25137 where PostgreSQL's cast would cut the digits off. */
    @Test
    void castOfANumberTooLongForTheVarchar2IsAnError() {
        for (String expression :
                new String[] {"CAST(amount AS VARCHAR2(2))", "CAST(12345 AS VARCHAR2(2))"}) {
            SQLException failure = assertThrows(SQLException.class, () -> value(expression));
            assertEquals(25137, failure.getErrorCode(), expression);
        }
    }
}
