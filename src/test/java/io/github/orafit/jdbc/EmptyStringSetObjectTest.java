package io.github.orafit.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.orafit.translation.BindLineage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** Oracle stores a zero-length string as NULL whichever JDBC setter supplied it. */
final class EmptyStringSetObjectTest {
    private final List<String> calls = new ArrayList<>();
    private final PreparedStatement delegate =
            (PreparedStatement)
                    Proxy.newProxyInstance(
                            PreparedStatement.class.getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (proxy, method, args) -> {
                                calls.add(method.getName() + ":" + args[0] + ":" + args[1]);
                                return null;
                            });

    private void bind(Object... args) throws Throwable {
        Method method =
                PreparedStatement.class.getMethod("setObject", int.class, Object.class, int.class);
        JdbcBinds.mapSetter(delegate, BindLineage.identity(1), method, args, 1, "test");
    }

    @Test
    void setObjectWithEmptyStringAndCharacterTypeBecomesNull() throws Throwable {
        bind(1, "", Types.VARCHAR);

        assertEquals(List.of("setNull:1:" + Types.VARCHAR), calls);
    }

    @Test
    void setObjectWithEmptyStringAndNationalTypeBecomesNationalNull() throws Throwable {
        bind(1, "", Types.NVARCHAR);

        assertEquals(List.of("setNull:1:" + Types.NVARCHAR), calls);
    }

    @Test
    void setObjectWithEmptyStringAndNonCharacterTypeIsNotRewritten() throws Throwable {
        bind(1, "", Types.INTEGER);

        assertEquals(List.of("setObject:1:"), calls);
    }

    @Test
    void twoArgumentSetObjectBecomesNull() throws Throwable {
        Method method = PreparedStatement.class.getMethod("setObject", int.class, Object.class);
        JdbcBinds.mapSetter(
                delegate, BindLineage.identity(1), method, new Object[] {1, ""}, 1, "test");

        assertEquals(List.of("setNull:1:" + Types.VARCHAR), calls);
    }
}
