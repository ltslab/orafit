package io.github.orafit.jdbc;

import io.github.orafit.translation.TranslationException;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central Oracle-facing SQLException adaptation for translation and delegated PostgreSQL errors.
 */
final class JdbcErrors {
    private JdbcErrors() {}

    static SQLException fromTranslation(TranslationException failure) {
        return switch (failure.code()) {
            case "SEQUENCE_WHERE" -> new SQLException(failure.getMessage(), "42000", 2287, failure);
            case "EXTRACT_FIELD" -> new SQLException(failure.getMessage(), "99999", 30076, failure);
            case "COALESCE_ARITY" ->
                    new SQLException(
                            "ORA-00938: not enough arguments for function", "42000", 938, failure);
            case "SET_DATATYPE" ->
                    new SQLException(
                            "ORA-01790: expression must have same datatype as corresponding expression",
                            "42000",
                            1790,
                            failure);
            case "FUNCTION_DATATYPE", "NULLIF_FIRST_NULL" ->
                    new SQLException("ORA-00932: inconsistent datatypes", "42000", 932, failure);
            case "MERGE_ON_COLUMN_UPDATE" ->
                    new SQLException(
                            "ORA-38104: MERGE cannot update a target column referenced by ON",
                            "99999",
                            38104,
                            failure);
            case "CASE_DATATYPE" ->
                    new SQLException(
                            "ORA-00932: inconsistent datatypes: expected NUMBER got CHAR",
                            "42000",
                            932,
                            failure);
            case "IN_LIST_LIMIT" ->
                    new SQLException(
                            "ORA-01795: maximum number of expressions in a list is 1000",
                            "42000",
                            1795,
                            failure);
            case "MULTI_STATEMENT" ->
                    new SQLException(
                            "ORA-00933: SQL command not properly ended", "42000", 933, failure);
            case "PARSER" -> new SQLException(failure.getMessage(), "42000", failure);
            default -> new SQLException(failure.getMessage(), "0A000", failure);
        };
    }

    private static final Pattern ORACLE_PREFIX =
            Pattern.compile("^\\s*(?:ERROR:\\s*)?ORA-(\\d{5})");
    private static final Pattern CONSTRAINT = Pattern.compile("constraint \"([^\"]+)\"");

    static SQLException fromDelegate(SQLException sql) {
        SQLException mapped = adapt(sql);
        if (mapped != sql && sql instanceof BatchUpdateException batch) {
            // Keep the batch type and per-row update counts that callers catch on.
            BatchUpdateException keep =
                    new BatchUpdateException(
                            mapped.getMessage(),
                            mapped.getSQLState(),
                            mapped.getErrorCode(),
                            batch.getLargeUpdateCounts(),
                            batch);
            return keep;
        }
        return mapped;
    }

    private static SQLException adapt(SQLException sql) {
        if (sql.getErrorCode() != 0) return sql;
        String message = sql.getMessage() == null ? "" : sql.getMessage();
        String state = sql.getSQLState() == null ? "" : sql.getSQLState();
        switch (state) {
            case "23514":
                if (message.contains("orafit_byte_length_")) {
                    return new SQLException(
                            "ORA-12899: value too large for column", "72000", 12899, sql);
                }
                return sql;
            case "21000":
                if (message.contains(
                        "more than one row returned by a subquery used as an expression")) {
                    return new SQLException(
                            "ORA-01427: single-row subquery returns more than one row",
                            "21000",
                            1427,
                            sql);
                }
                break;
            case "23505":
                return new SQLException(
                        "ORA-00001: unique constraint (" + constraint(message) + ") violated",
                        "23000",
                        1,
                        sql);
            case "23502":
                return new SQLException(
                        "ORA-01400: cannot insert NULL into (" + constraint(message) + ")",
                        "23000",
                        1400,
                        sql);
            case "23503":
                // PostgreSQL words the child-side and parent-side violations differently.
                return message.contains("is still referenced")
                                || message.contains("update or delete on table")
                        ? new SQLException(
                                "ORA-02292: integrity constraint ("
                                        + constraint(message)
                                        + ") violated - child record found",
                                "23000",
                                2292,
                                sql)
                        : new SQLException(
                                "ORA-02291: integrity constraint ("
                                        + constraint(message)
                                        + ") violated - parent key not found",
                                "23000",
                                2291,
                                sql);
            case "22001":
                return new SQLException(
                        "ORA-12899: value too large for column", "72000", 12899, sql);
            case "40P01":
                return new SQLException(
                        "ORA-00060: deadlock detected while waiting for resource",
                        "61000",
                        60,
                        sql);
            case "55P03":
                return message.contains("lock timeout")
                        ? new SQLException(
                                "ORA-30006: resource busy; acquire with WAIT timeout expired",
                                "61000",
                                30006,
                                sql)
                        : new SQLException(
                                "ORA-00054: resource busy and acquire with NOWAIT specified or"
                                        + " timeout expired",
                                "61000",
                                54,
                                sql);
            case "57014":
                return new SQLTimeoutException(
                        "ORA-01013: user requested cancel of current operation",
                        "72000",
                        1013,
                        sql);
            default:
                break;
        }
        // Only a message that is itself an ORA error counts. Data echoed in a PostgreSQL DETAIL
        // line (for example a key value that happens to read "ORA-00060") must never be scraped.
        Matcher oracle = ORACLE_PREFIX.matcher(message);
        if (!oracle.find()) return sql;
        return new SQLException(message, sql.getSQLState(), Integer.parseInt(oracle.group(1)), sql);
    }

    private static String constraint(String message) {
        Matcher matcher = CONSTRAINT.matcher(message);
        if (matcher.find()) return matcher.group(1);
        int quote = message.indexOf("column \"");
        if (quote >= 0) {
            int close = message.indexOf('"', quote + 8);
            if (close > quote) return message.substring(quote + 8, close);
        }
        return "?";
    }
}
