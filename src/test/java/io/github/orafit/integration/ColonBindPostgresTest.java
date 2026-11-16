package io.github.orafit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** Oracle's {@code :name} and {@code :1} placeholders bind by position of appearance. */
final class ColonBindPostgresTest {
    private static int count(String sql, int... values) throws Exception {
        try (Connection connection = IntegrationSupport.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setInt(i + 1, values[i]);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    @Test
    void namedAndNumberedPlaceholdersBindInOrder() throws Exception {
        assertEquals(1, count("SELECT COUNT(*) FROM dual WHERE :a = 1 AND :b = 2", 1, 2));
        assertEquals(1, count("SELECT COUNT(*) FROM dual WHERE :2 = 3 AND :1 = 2", 3, 2));
        assertEquals(1, count("SELECT COUNT(*) FROM dual WHERE :a + :a = 4", 2, 2));
    }

    @Test
    void literalsAndCastsKeepTheirColons() throws Exception {
        assertEquals(1, count("SELECT COUNT(*) FROM dual WHERE 'x:y' = 'x:y' AND :a = 1", 1));
    }
}
