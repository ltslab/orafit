package io.github.orafit.jdbc;

import io.github.orafit.translation.BindLineage;
import io.github.orafit.translation.ResultMetadataPlan;
import io.github.orafit.translation.ScriptPlan;
import io.github.orafit.translation.Translation;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Ordinary PreparedStatement adaptation for bind lineage and Oracle-facing metadata. */
final class PreparedRuntime {
    private PreparedRuntime() {}

    static PreparedStatement wrap(
            PreparedStatement delegate, Translation translation, Connection owner) {
        return JdbcProxy.proxy(
                PreparedStatement.class,
                new Handler(delegate, translation.binds(), translation.metadata(), owner));
    }

    static PreparedStatement wrapScript(
            Connection delegateConnection,
            ScriptPlan script,
            ResultMetadataPlan metadata,
            Connection owner,
            int inputCount,
            Object[] prepareArgs)
            throws SQLException {
        PreparedStatement[] delegates = new PreparedStatement[script.statements().size()];
        try {
            for (int i = 0; i < delegates.length; i++) {
                ScriptPlan.Statement statement = script.statements().get(i);
                Object[] args = prepareArgs.clone();
                args[0] = statement.sql();
                delegates[i] =
                        JdbcProxy.prepareStatement(
                                delegateConnection, statement.sql(), args, "anonymous DML block");
            }
        } catch (SQLException failure) {
            closePrepared(delegates, failure);
            throw failure;
        }
        return JdbcProxy.proxy(
                PreparedStatement.class,
                new ScriptHandler(
                        delegateConnection,
                        delegates,
                        script.statements(),
                        metadata,
                        owner,
                        inputCount));
    }

    private static void closePrepared(PreparedStatement[] delegates, SQLException failure) {
        for (PreparedStatement delegate : delegates) {
            if (delegate == null) continue;
            try {
                delegate.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private static final class Handler implements InvocationHandler {
        private final PreparedStatement delegate;
        private final BindLineage binds;
        private final ResultMetadataPlan metadata;
        private final Connection owner;
        private final int[] bindWidths;

        /** The last setter call of each parameter ({method, args}), and one copy per addBatch. */
        private final Object[][] setters;

        private final List<Object[][]> batch = new ArrayList<>();

        private Handler(
                PreparedStatement delegate,
                BindLineage binds,
                ResultMetadataPlan metadata,
                Connection owner) {
            this.delegate = delegate;
            this.binds = binds;
            this.metadata = metadata;
            this.owner = owner;
            this.bindWidths = new int[binds.inputCount() + 1];
            this.setters = new Object[binds.inputCount() + 1][];
        }

        /**
         * Oracle stops a failing batch at the failing row and keeps the rows before it; pgJDBC
         * rolls the whole batch back (to the savepoint autosave took) and reports -3 for every row.
         * After such a failure the rows are replayed one at a time on the intact transaction, which
         * stops at the same row with the counts of the rows before it.
         *
         * <p>With auto-commit on, pgJDBC sends a large batch in several round trips that commit
         * separately, so there would be no intact state to replay on: the batch then runs in one
         * transaction, committed when it ends (the rows before a failing row included, as in
         * Oracle).
         */
        private Object executeBatch(Method method, Object[] args) throws Throwable {
            List<Object[][]> rows = new ArrayList<>(batch);
            batch.clear();
            Connection connection = delegate.getConnection();
            boolean autoCommit = connection.getAutoCommit();
            if (autoCommit) connection.setAutoCommit(false);
            Object counts;
            try {
                counts = JdbcProxy.invokeDelegate(method, delegate, args);
            } catch (Throwable failure) {
                Throwable outcome = failure;
                if (failure instanceof BatchUpdateException batchFailure && replayable(rows)) {
                    outcome = replay(rows, batchFailure);
                }
                if (autoCommit) {
                    try {
                        connection.setAutoCommit(true);
                    } catch (SQLException commitFailure) {
                        outcome.addSuppressed(commitFailure);
                    }
                }
                throw outcome;
            }
            if (autoCommit) connection.setAutoCommit(true);
            return counts;
        }

        /** A stream or reader was consumed by the first attempt and cannot be bound again. */
        private static boolean replayable(List<Object[][]> rows) {
            for (Object[][] row : rows) {
                for (Object[] call : row) {
                    if (call != null && JdbcBinds.duplicateUnsafe((Object[]) call[1])) return false;
                }
            }
            return true;
        }

        private SQLException replay(List<Object[][]> rows, BatchUpdateException first) {
            long[] counts = new long[rows.size()];
            int done = 0;
            for (Object[][] row : rows) {
                try {
                    delegate.clearParameters();
                    for (Object[] call : row) {
                        if (call == null) continue;
                        Object[] setter = (Object[]) call[1];
                        JdbcBinds.mapSetter(
                                delegate,
                                binds,
                                (Method) call[0],
                                setter,
                                (Integer) setter[0],
                                "the supported Orafit contract");
                    }
                    counts[done] = delegate.executeLargeUpdate();
                    done++;
                } catch (SQLException failure) {
                    // Without autosave the failed batch aborted the transaction: nothing can run
                    // on it, and the batch's own failure is the one to report.
                    if ("25P02".equals(failure.getSQLState())) return first;
                    return batchFailure(JdbcErrors.fromDelegate(failure), counts, done);
                } catch (Throwable failure) {
                    return batchFailure(JdbcErrors.fromDelegate(first), counts, done);
                }
            }
            // The batch failed as a whole but every row runs alone: report the first failure.
            return batchFailure(JdbcErrors.fromDelegate(first), counts, done);
        }

        private static BatchUpdateException batchFailure(
                SQLException mapped, long[] counts, int done) {
            return new BatchUpdateException(
                    mapped.getMessage(),
                    mapped.getSQLState(),
                    mapped.getErrorCode(),
                    Arrays.copyOf(counts, done),
                    mapped);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("getConnection")) return owner;
            if (name.equals("getMetaData")) {
                Object value = JdbcProxy.invokeDelegate(method, delegate, args);
                return value instanceof ResultSetMetaData raw
                        ? JdbcMetadata.oracleMetadata(raw, metadata, bindWidths, owner)
                        : value;
            }
            if (name.equals("getParameterMetaData")) {
                Object value = JdbcProxy.invokeDelegate(method, delegate, args);
                return value instanceof ParameterMetaData raw
                        ? JdbcBinds.oracleParameters(raw, binds)
                        : value;
            }
            if (name.equals("clearParameters")) {
                Arrays.fill(bindWidths, 0);
                Arrays.fill(setters, null);
                return JdbcProxy.invokeDelegate(method, delegate, args);
            }
            if (JdbcProxy.parameterSetter(method, args)) {
                int input = (Integer) args[0];
                JdbcBinds.mapSetter(
                        delegate, binds, method, args, input, "the supported Orafit contract");
                bindWidths[input] = JdbcBinds.bindWidth(method, args);
                setters[input] = new Object[] {method, args};
                return null;
            }
            if (name.equals("addBatch") && (args == null || args.length == 0)) {
                batch.add(setters.clone());
            } else if (name.equals("clearBatch")) {
                batch.clear();
            } else if (name.equals("executeBatch") || name.equals("executeLargeBatch")) {
                return executeBatch(method, args);
            }
            return JdbcMetadata.oracleResult(
                    JdbcProxy.invokeDelegate(method, delegate, args),
                    metadata,
                    bindWidths,
                    (Statement) proxy);
        }
    }

    private static final class ScriptHandler implements InvocationHandler {
        private final Connection delegateConnection;
        private final PreparedStatement[] delegates;
        private final List<ScriptPlan.Statement> statements;
        private final ResultMetadataPlan metadata;
        private final Connection owner;
        private final int inputCount;
        private int updateCount = -1;
        private long largeUpdateCount = -1;

        private ScriptHandler(
                Connection delegateConnection,
                PreparedStatement[] delegates,
                List<ScriptPlan.Statement> statements,
                ResultMetadataPlan metadata,
                Connection owner,
                int inputCount) {
            this.delegateConnection = delegateConnection;
            this.delegates = delegates;
            this.statements = statements;
            this.metadata = metadata;
            this.owner = owner;
            this.inputCount = inputCount;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("getConnection")) return owner;
            if (name.equals("getMetaData")) {
                Object value = delegates[delegates.length - 1].getMetaData();
                return value instanceof ResultSetMetaData raw
                        ? JdbcMetadata.oracleMetadata(raw, metadata, new int[inputCount + 1], owner)
                        : value;
            }
            if (name.equals("getParameterMetaData")) {
                return JdbcProxy.proxy(
                        ParameterMetaData.class,
                        (metadataProxy, metadataMethod, metadataArgs) -> {
                            if (metadataMethod.getName().equals("getParameterCount"))
                                return inputCount;
                            if (JdbcProxy.indexed(metadataArgs)) {
                                int input = (Integer) metadataArgs[0];
                                validateInput(input);
                                for (int i = 0; i < delegates.length; i++) {
                                    List<Integer> outputs =
                                            statements.get(i).binds().outputPositions(input);
                                    if (outputs.isEmpty()) continue;
                                    Object[] mapped = metadataArgs.clone();
                                    mapped[0] = outputs.get(0);
                                    return JdbcProxy.invokeDelegate(
                                            metadataMethod,
                                            delegates[i].getParameterMetaData(),
                                            mapped);
                                }
                                throw JdbcProxy.badIndex(
                                        input, "does not map to script parameter metadata");
                            }
                            return JdbcProxy.invokeDelegate(
                                    metadataMethod,
                                    delegates[0].getParameterMetaData(),
                                    metadataArgs);
                        });
            }
            if (name.equals("clearParameters")) {
                for (PreparedStatement delegate : delegates) delegate.clearParameters();
                updateCount = -1;
                largeUpdateCount = -1;
                return null;
            }
            if (JdbcProxy.parameterSetter(method, args)) {
                int input = (Integer) args[0];
                validateInput(input);
                boolean mapped = false;
                for (int i = 0; i < delegates.length; i++) {
                    BindLineage binds = statements.get(i).binds();
                    if (!binds.outputPositions(input).isEmpty()) {
                        JdbcBinds.mapSetter(
                                delegates[i], binds, method, args, input, "anonymous DML block");
                        mapped = true;
                    }
                }
                if (!mapped) throw JdbcProxy.badIndex(input, "does not map to a script bind");
                return null;
            }
            if (JdbcProxy.batchCall(name)) {
                throw JdbcProxy.unsupported(
                        "Batch execution of anonymous DML blocks is unsupported");
            }
            if ((name.equals("executeQuery") && (args == null || args.length == 0))
                    || name.equals("getGeneratedKeys")) {
                throw JdbcProxy.unsupported("Anonymous DML blocks do not produce result sets");
            }
            if (name.startsWith("execute")
                    && args != null
                    && args.length > 0
                    && args[0] instanceof String) {
                throw JdbcProxy.unsupported(
                        "Prepared anonymous DML blocks cannot execute replacement SQL");
            }
            if (name.equals("executeUpdate") && (args == null || args.length == 0)) {
                updateCount = -1;
                largeUpdateCount = -1;
                updateCount = executeAtomically(this::executeUpdates);
                largeUpdateCount = updateCount;
                return updateCount;
            }
            if (name.equals("executeLargeUpdate") && (args == null || args.length == 0)) {
                updateCount = -1;
                largeUpdateCount = -1;
                largeUpdateCount = executeAtomically(this::executeLargeUpdates);
                updateCount =
                        largeUpdateCount > Integer.MAX_VALUE
                                ? Integer.MAX_VALUE
                                : (int) largeUpdateCount;
                return largeUpdateCount;
            }
            if (name.equals("execute") && (args == null || args.length == 0)) {
                updateCount = -1;
                largeUpdateCount = -1;
                Execution result = executeAtomically(this::executeStatements);
                updateCount = result.updateCount();
                largeUpdateCount = updateCount;
                return result.resultSet();
            }
            if (name.equals("getUpdateCount")) return updateCount;
            if (name.equals("getLargeUpdateCount")) return largeUpdateCount;
            if (name.equals("close")) {
                SQLException failure = null;
                for (PreparedStatement delegate : delegates) {
                    try {
                        delegate.close();
                    } catch (SQLException ex) {
                        if (failure == null) failure = ex;
                        else failure.addSuppressed(ex);
                    }
                }
                if (failure != null) throw failure;
                return null;
            }
            if (name.equals("isClosed")) {
                for (PreparedStatement delegate : delegates) if (!delegate.isClosed()) return false;
                return true;
            }
            // Statement configuration (timeout, fetch size, poolability, etc.) applies to every
            // statement in the lowered block. Getter calls use the last statement's value.
            Object result = null;
            for (PreparedStatement delegate : delegates) {
                result = JdbcProxy.invokeDelegate(method, delegate, args);
            }
            return result;
        }

        private void validateInput(int input) throws SQLException {
            if (input < 1 || input > inputCount) {
                throw JdbcProxy.badIndex(input, "is outside the anonymous DML block bind range");
            }
        }

        private int executeUpdates() throws SQLException {
            for (PreparedStatement delegate : delegates) JdbcProxy.executeUpdate(delegate);
            return 1;
        }

        private long executeLargeUpdates() throws SQLException {
            for (PreparedStatement delegate : delegates) JdbcProxy.executeLargeUpdate(delegate);
            return 1;
        }

        private Execution executeStatements() throws SQLException {
            for (PreparedStatement delegate : delegates) {
                if (JdbcProxy.execute(delegate)) {
                    throw JdbcProxy.unsupported(
                            "Anonymous DML block statement unexpectedly produced a result set");
                }
            }
            return new Execution(false, 1);
        }

        private <T> T executeAtomically(JdbcProxy.SqlOperation<T> operation) throws SQLException {
            return JdbcProxy.atomically(delegateConnection, operation);
        }

        private record Execution(boolean resultSet, int updateCount) {}
    }
}
