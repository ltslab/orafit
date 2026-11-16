package io.github.orafit.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Savepoint;

/** Shared proxy construction, delegate invocation, and JDBC boundary mechanics. */
final class JdbcProxy {
    private JdbcProxy() {}

    static PreparedStatement prepareStatement(
            Connection connection, String sql, Object[] args, String context) throws SQLException {
        if (args.length == 1) return connection.prepareStatement(sql);
        if (args.length == 3
                && args[1] instanceof Integer type
                && args[2] instanceof Integer concurrency) {
            return connection.prepareStatement(sql, type, concurrency);
        }
        if (args.length == 4
                && args[1] instanceof Integer type
                && args[2] instanceof Integer concurrency
                && args[3] instanceof Integer holdability) {
            return connection.prepareStatement(sql, type, concurrency, holdability);
        }
        throw unsupported("Unsupported prepareCall overload for " + context);
    }

    static SQLException badIndex(int index, String message) {
        return new SQLException("Parameter " + index + " " + message, "07009");
    }

    static SQLFeatureNotSupportedException unsupported(String message) {
        return new SQLFeatureNotSupportedException(message, "0A000");
    }

    static boolean indexed(Object[] args) {
        return args != null && args.length > 0 && args[0] instanceof Integer;
    }

    static boolean callableGetter(Method method, Object[] args) {
        return method.getDeclaringClass() == CallableStatement.class
                && method.getName().startsWith("get")
                && indexed(args);
    }

    static boolean parameterSetter(Method method, Object[] args) {
        return method.getDeclaringClass() == PreparedStatement.class
                && method.getName().startsWith("set")
                && indexed(args);
    }

    static boolean executeCall(String name) {
        return name.equals("execute")
                || name.equals("executeUpdate")
                || name.equals("executeLargeUpdate");
    }

    static boolean batchCall(String name) {
        return name.equals("addBatch")
                || name.equals("executeBatch")
                || name.equals("executeLargeBatch");
    }

    static Object executeOutcome(String methodName, int updateCount) {
        return switch (methodName) {
            case "execute" -> false;
            case "executeUpdate" -> updateCount;
            case "executeLargeUpdate" -> (long) updateCount;
            default -> throw new AssertionError(methodName);
        };
    }

    static boolean routineStatus(String name) {
        return name.equals("wasNull")
                || name.equals("getUpdateCount")
                || name.equals("getLargeUpdateCount")
                || name.equals("getResultSet")
                || name.equals("getMoreResults");
    }

    static Object routineStatus(String name, boolean lastWasNull, int updateCount) {
        return switch (name) {
            case "wasNull" -> lastWasNull;
            case "getUpdateCount" -> updateCount;
            case "getLargeUpdateCount" -> (long) updateCount;
            case "getResultSet" -> null;
            case "getMoreResults" -> false;
            default -> throw new AssertionError(name);
        };
    }

    static void rejectRoutineExtras(String name, Method method, String kind)
            throws SQLFeatureNotSupportedException {
        if (name.equals("executeQuery")) {
            throw unsupported(
                    "Use execute(), executeUpdate(), or executeLargeUpdate() for Oracle "
                            + kind
                            + " calls");
        }
        if (batchCall(name)) throw unsupported("Batch Oracle " + kind + " calls are not supported");
        if (method.getDeclaringClass() == CallableStatement.class) {
            throw unsupported(
                    "CallableStatement method is outside the current " + kind + " subset: " + name);
        }
    }

    @FunctionalInterface
    interface SqlOperation<T> {
        T execute() throws SQLException;
    }

    /** Runs one statement atomically: a failure rolls back only this statement's effects. */
    static <T> T atomically(Connection delegateConnection, SqlOperation<T> operation)
            throws SQLException {
        boolean autoCommit = delegateConnection.getAutoCommit();
        Savepoint savepoint = null;
        if (autoCommit) delegateConnection.setAutoCommit(false);
        else savepoint = delegateConnection.setSavepoint();

        T result = null;
        SQLException failure = null;
        try {
            result = operation.execute();
            if (autoCommit) delegateConnection.commit();
            else delegateConnection.releaseSavepoint(savepoint);
        } catch (SQLException executionFailure) {
            failure = executionFailure;
            try {
                if (autoCommit) {
                    delegateConnection.rollback();
                } else {
                    delegateConnection.rollback(savepoint);
                    delegateConnection.releaseSavepoint(savepoint);
                }
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
        if (autoCommit) {
            try {
                delegateConnection.setAutoCommit(true);
            } catch (SQLException restoreFailure) {
                if (failure == null) failure = restoreFailure;
                else failure.addSuppressed(restoreFailure);
            }
        }
        if (failure != null) throw failure;
        return result;
    }

    /** Runs a prepared delegate so ORA-code adaptation also covers the runtime-owned paths. */
    static boolean execute(PreparedStatement delegate) throws SQLException {
        try {
            return delegate.execute();
        } catch (SQLException ex) {
            throw JdbcErrors.fromDelegate(ex);
        }
    }

    static int executeUpdate(PreparedStatement delegate) throws SQLException {
        try {
            return delegate.executeUpdate();
        } catch (SQLException ex) {
            throw JdbcErrors.fromDelegate(ex);
        }
    }

    static long executeLargeUpdate(PreparedStatement delegate) throws SQLException {
        try {
            return delegate.executeLargeUpdate();
        } catch (SQLException ex) {
            throw JdbcErrors.fromDelegate(ex);
        }
    }

    static Object invokeDelegate(Method method, Object delegate, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof SQLException sql) throw JdbcErrors.fromDelegate(sql);
            throw cause;
        }
    }

    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (proxy, method, args) -> {
                            if (args != null
                                    && args.length == 1
                                    && args[0] instanceof Class<?> requested
                                    && requested.isInstance(proxy)) {
                                if (method.getName().equals("isWrapperFor")) return true;
                                if (method.getName().equals("unwrap")) return requested.cast(proxy);
                            }
                            return handler.invoke(proxy, method, args);
                        }));
    }
}
