package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

/**
 * Oracle stops a failing batch at the failing row: the rows before it stay stored, the update
 * counts of the BatchUpdateException cover exactly those rows, and the transaction stays usable.
 * pgJDBC rolls the whole batch back and reports a count of -3 for every row.
 */
final class BatchFailurePostgresTest {
    @BeforeAll
    static void createTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS bf_rows");
            statement.execute("CREATE TABLE bf_rows (id integer PRIMARY KEY, note varchar(10))");
        }
    }

    @AfterAll
    static void dropTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS bf_rows");
        }
    }

    private static void reset(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM bf_rows");
            statement.execute("INSERT INTO bf_rows VALUES (1, 'one')");
        }
    }

    private static String ids(Connection connection) throws Exception {
        StringBuilder ids = new StringBuilder();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT id FROM bf_rows ORDER BY id")) {
            while (result.next()) ids.append(ids.length() == 0 ? "" : ",").append(result.getInt(1));
        }
        return ids.toString();
    }

    private static BatchUpdateException failing(Connection connection, int... rows)
            throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement("INSERT INTO bf_rows VALUES (?, 'x')")) {
            for (int id : rows) {
                statement.setInt(1, id);
                statement.addBatch();
            }
            return assertThrows(BatchUpdateException.class, statement::executeBatch);
        }
    }

    @Test
    void aFailureInTheMiddleKeepsTheRowsBeforeIt() throws Exception {
        try (Connection connection = IntegrationSupport.open()) {
            connection.setAutoCommit(false);
            reset(connection);
            BatchUpdateException failure = failing(connection, 10, 1, 12);
            assertArrayEquals(new int[] {1}, failure.getUpdateCounts());
            assertEquals(1, failure.getErrorCode());
            assertEquals("23000", failure.getSQLState());
            assertEquals("1,10", ids(connection));
            connection.rollback();
        }
    }

    @Test
    void aFailureInTheFirstRowStoresNothing() throws Exception {
        try (Connection connection = IntegrationSupport.open()) {
            connection.setAutoCommit(false);
            reset(connection);
            BatchUpdateException failure = failing(connection, 1, 11, 12);
            assertArrayEquals(new int[] {}, failure.getUpdateCounts());
            assertEquals("1", ids(connection));
            connection.rollback();
        }
    }

    @Test
    void aFailureInTheLastRowKeepsTheOthers() throws Exception {
        try (Connection connection = IntegrationSupport.open()) {
            connection.setAutoCommit(false);
            reset(connection);
            BatchUpdateException failure = failing(connection, 10, 11, 1);
            assertArrayEquals(new int[] {1, 1}, failure.getUpdateCounts());
            assertEquals("1,10,11", ids(connection));
            connection.rollback();
        }
    }

    @Test
    void theTransactionStaysUsableAfterAFailedBatch() throws Exception {
        try (Connection connection = IntegrationSupport.open()) {
            connection.setAutoCommit(false);
            reset(connection);
            failing(connection, 10, 1, 12);
            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("INSERT INTO bf_rows VALUES (20, 'y')"));
            }
            assertEquals("1,10,20", ids(connection));
            connection.rollback();
        }
    }

    @Test
    void aBatchThatSucceedsReportsOneCountPerRow() throws Exception {
        try (Connection connection = IntegrationSupport.open()) {
            connection.setAutoCommit(false);
            reset(connection);
            try (PreparedStatement statement =
                    connection.prepareStatement("UPDATE bf_rows SET note = ? WHERE id = ?")) {
                for (int id : new int[] {1, 99, 1}) {
                    statement.setString(1, "n" + id);
                    statement.setInt(2, id);
                    statement.addBatch();
                }
                assertArrayEquals(new int[] {1, 0, 1}, statement.executeBatch());
            }
            connection.rollback();
        }
    }

    private static int count(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM bf_rows")) {
            result.next();
            return result.getInt(1);
        }
    }

    /**
     * pgJDBC sends a large batch in several round trips, each its own transaction when auto-commit
     * is on. Oracle 18c XE reports 2998 counts and keeps 2999 rows for these statements.
     */
    @Test
    void aLargeAutoCommitBatchStopsAtTheFailingRow() throws Exception {
        try (Connection connection = IntegrationSupport.open()) {
            reset(connection);
            int[] rows = new int[3000];
            for (int i = 0; i < rows.length; i++) rows[i] = i == 2998 ? 1 : 100 + i;
            BatchUpdateException failure = failing(connection, rows);
            assertEquals(2998, failure.getUpdateCounts().length);
            assertEquals(1, failure.getErrorCode());
            assertEquals(2999, count(connection));
            assertTrue(connection.getAutoCommit());
            reset(connection);
        }
    }

    /** A stream is consumed by the first attempt, so such a batch cannot be run again. */
    @Test
    void aBatchWithAStreamBindIsNotRunAgain() throws Exception {
        try (Connection connection = IntegrationSupport.open()) {
            connection.setAutoCommit(false);
            reset(connection);
            try (PreparedStatement statement =
                    connection.prepareStatement("INSERT INTO bf_rows VALUES (?, ?)")) {
                statement.setInt(1, 10);
                statement.setCharacterStream(2, new StringReader("ten"));
                statement.addBatch();
                statement.setInt(1, 1);
                statement.setCharacterStream(2, new StringReader("dup"));
                statement.addBatch();
                assertThrows(BatchUpdateException.class, statement::executeBatch);
            }
            assertEquals("1", ids(connection));
            connection.rollback();
        }
    }

    /** Each addBatch keeps the parameters of its row, not every setter call made so far. */
    @Test
    void manyRowsAreRecordedInLinearTime() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement =
                        connection.prepareStatement("INSERT INTO bf_rows VALUES (?, ?)")) {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(10),
                    () -> {
                        for (int i = 0; i < 60000; i++) {
                            statement.setInt(1, i);
                            statement.setString(2, "x");
                            statement.addBatch();
                        }
                    });
            statement.clearBatch();
        }
    }
}
