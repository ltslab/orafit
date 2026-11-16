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
    void leavesUnrelatedCheckViolationsUnchanged() {
        SQLException postgres =
                new SQLException("new row violates check constraint \"positive_amount\"", "23514");

        assertSame(postgres, JdbcErrors.fromDelegate(postgres));
    }
}
