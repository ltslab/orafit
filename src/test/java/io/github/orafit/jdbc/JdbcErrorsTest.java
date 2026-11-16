package io.github.orafit.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

final class JdbcErrorsTest {
    @Test
    void mapsOnlyOrafitByteLengthCheckViolationsToOra12899() {
        SQLException postgres =
                new SQLException(
                        "new row violates check constraint \"orafit_byte_length_012345\"", "23514");

        SQLException oracle = JdbcErrors.fromDelegate(postgres);

        assertEquals(12899, oracle.getErrorCode());
        assertEquals("72000", oracle.getSQLState());
        assertTrue(oracle.getMessage().startsWith("ORA-12899:"));
        assertSame(postgres, oracle.getCause());
    }

    @Test
    void mapsCommonConstraintAndLockErrorsToOracleCodes() {
        assertEquals(
                1,
                JdbcErrors.fromDelegate(
                                new SQLException(
                                        "ERROR: duplicate key value violates unique constraint"
                                                + " \"emp_pkey\"",
                                        "23505"))
                        .getErrorCode());
        assertEquals(
                1400,
                JdbcErrors.fromDelegate(new SQLException("null value", "23502")).getErrorCode());
        assertEquals(
                2291,
                JdbcErrors.fromDelegate(
                                new SQLException(
                                        "insert or update on table \"c\" violates foreign key"
                                                + " constraint \"fk\"",
                                        "23503"))
                        .getErrorCode());
        assertEquals(
                2292,
                JdbcErrors.fromDelegate(
                                new SQLException(
                                        "update or delete on table \"p\" violates foreign key"
                                                + " constraint \"fk\" on table \"c\"",
                                        "23503"))
                        .getErrorCode());
        assertEquals(
                60, JdbcErrors.fromDelegate(new SQLException("deadlock", "40P01")).getErrorCode());
        assertEquals(
                54,
                JdbcErrors.fromDelegate(new SQLException("could not obtain lock on row", "55P03"))
                        .getErrorCode());
        SQLException timeout = JdbcErrors.fromDelegate(new SQLException("canceling", "57014"));
        assertEquals(1013, timeout.getErrorCode());
        assertTrue(timeout instanceof java.sql.SQLTimeoutException);
    }

    @Test
    void doesNotScrapeOraCodesFromEchoedData() {
        SQLException postgres =
                new SQLException(
                        "ERROR: bad\n  Detail: Key (c)=(ORA-00060) already exists.", "23999");

        assertSame(postgres, JdbcErrors.fromDelegate(postgres));
    }

    @Test
    void keepsBatchUpdateExceptionType() {
        java.sql.BatchUpdateException batch =
                new java.sql.BatchUpdateException("dup", "23505", 0, new int[] {1, 0}, null);

        SQLException mapped = JdbcErrors.fromDelegate(batch);

        assertTrue(mapped instanceof java.sql.BatchUpdateException);
        assertEquals(1, mapped.getErrorCode());
        assertEquals(2, ((java.sql.BatchUpdateException) mapped).getUpdateCounts().length);
    }

    @Test
    void mapsCheckViolationsToOra02290() {
        SQLException postgres =
                new SQLException("new row violates check constraint \"positive_amount\"", "23514");

        SQLException mapped = JdbcErrors.fromDelegate(postgres);

        assertEquals(2290, mapped.getErrorCode());
        assertEquals("23000", mapped.getSQLState());
        assertEquals("ORA-02290: check constraint (positive_amount) violated", mapped.getMessage());
    }

    @Test
    void mapsMissingObjectsAndColumnPrecision() {
        assertEquals(
                942, JdbcErrors.fromDelegate(new SQLException("relation", "42P01")).getErrorCode());
        assertEquals(
                904, JdbcErrors.fromDelegate(new SQLException("column", "42703")).getErrorCode());
        assertEquals(
                918,
                JdbcErrors.fromDelegate(new SQLException("ambiguous", "42702")).getErrorCode());
        assertEquals(
                1438,
                JdbcErrors.fromDelegate(new SQLException("ERROR: numeric field overflow", "22003"))
                        .getErrorCode());
        SQLException other = new SQLException("value out of range", "22003");
        assertSame(other, JdbcErrors.fromDelegate(other));
    }
}
