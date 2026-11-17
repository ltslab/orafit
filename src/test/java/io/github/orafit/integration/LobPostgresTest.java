package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * An Oracle CLOB/BLOB column migrated by Ora2Pg is text/bytea. The JDBC LOB methods an Oracle
 * application calls on it work: getClob/getBlob, createClob/createBlob, setClob/setBlob. The
 * metadata still reports the PostgreSQL type (declared bounded).
 */
final class LobPostgresTest {
    @BeforeAll
    static void createTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS lob_rows");
            statement.execute("CREATE TABLE lob_rows (id integer PRIMARY KEY, c text, b bytea)");
            statement.execute(
                    "INSERT INTO lob_rows VALUES (1, 'hello clob', '\\x0102ff'), (2, NULL, NULL)");
        }
    }

    @AfterAll
    static void dropTable() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS lob_rows");
        }
    }

    @Test
    void getClobAndGetBlobReadTextAndBytea() throws Exception {
        try (Connection connection = IntegrationSupport.open();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery("SELECT c, b FROM lob_rows ORDER BY id")) {
            result.next();
            Clob clob = result.getClob(1);
            assertEquals(10, clob.length());
            assertEquals("hello", clob.getSubString(1, 5));
            assertEquals("hello clob", result.getClob("c").getSubString(1, 10));
            Blob blob = result.getBlob(2);
            assertEquals(3, blob.length());
            assertArrayEquals(new byte[] {1, 2, (byte) 0xff}, blob.getBytes(1, 3));
            assertArrayEquals(
                    new byte[] {1, 2, (byte) 0xff}, blob.getBinaryStream().readAllBytes());
            result.next();
            assertNull(result.getClob(1));
            assertNull(result.getBlob(2));
        }
    }

    @Test
    void createClobAndCreateBlobBindThroughSetClobAndSetBlob() throws Exception {
        try (Connection connection = IntegrationSupport.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement =
                    connection.prepareStatement("INSERT INTO lob_rows VALUES (?, ?, ?)")) {
                Clob clob = connection.createClob();
                clob.setString(1, "made here");
                Blob blob = connection.createBlob();
                blob.setBytes(1, new byte[] {9, 8, 7});
                statement.setInt(1, 10);
                statement.setClob(2, clob);
                statement.setBlob(3, blob);
                assertEquals(1, statement.executeUpdate());
                statement.setInt(1, 11);
                statement.setClob(2, new StringReader("streamed clob"));
                statement.setBlob(3, new ByteArrayInputStream(new byte[] {1}));
                assertEquals(1, statement.executeUpdate());
            }
            try (Statement statement = connection.createStatement();
                    ResultSet result =
                            statement.executeQuery(
                                    "SELECT c, b FROM lob_rows WHERE id >= 10 ORDER BY id")) {
                result.next();
                assertEquals("made here", result.getString(1));
                assertArrayEquals(new byte[] {9, 8, 7}, result.getBytes(2));
                result.next();
                assertEquals("streamed clob", result.getString(1));
                assertArrayEquals(new byte[] {1}, result.getBytes(2));
            }
            connection.rollback();
        }
    }

    /** Oracle refuses these (ORA-17068, ORA-22926); nothing is padded or cut silently. */
    @Test
    void invalidLobArgumentsAreSqlExceptions() throws Exception {
        try (Connection connection = IntegrationSupport.open()) {
            Clob clob = connection.createClob();
            clob.setString(1, "abc");
            assertThrows(SQLException.class, () -> clob.getSubString(1, -1));
            assertThrows(SQLException.class, () -> clob.truncate(5));
            assertThrows(SQLException.class, () -> clob.position("b", 0));
            assertEquals(3, clob.length());
            Blob blob = connection.createBlob();
            blob.setBytes(1, new byte[] {1, 2});
            assertThrows(SQLException.class, () -> blob.getBytes(1, -1));
            assertThrows(SQLException.class, () -> blob.truncate(5));
            assertEquals(2, blob.length());
        }
    }

    /** Oracle 18c XE stores the first three characters and the first two bytes. */
    @Test
    void setClobAndSetBlobWithALengthBindThatManyCharactersOrBytes() throws Exception {
        try (Connection connection = IntegrationSupport.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement =
                    connection.prepareStatement("INSERT INTO lob_rows VALUES (?, ?, ?)")) {
                statement.setInt(1, 90);
                statement.setClob(2, new StringReader("reader"), 3L);
                statement.setBlob(3, new ByteArrayInputStream(new byte[] {9, 8, 7}), 2L);
                assertEquals(1, statement.executeUpdate());
            }
            try (Statement statement = connection.createStatement();
                    ResultSet result =
                            statement.executeQuery("SELECT c, b FROM lob_rows WHERE id = 90")) {
                result.next();
                assertEquals("rea", result.getString(1));
                assertArrayEquals(new byte[] {9, 8}, result.getBytes(2));
            }
            connection.rollback();
        }
    }
}
