package com.strategysquad.ingestion.bhavcopy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BhavcopyJdbcTestSupport {
    private BhavcopyJdbcTestSupport() {
    }

    static final class PreparedStatementRecorder {
        private final List<Map<Integer, Object>> batchParameters = new ArrayList<>();
        private final int[] executeBatchResult;
        private final Map<Integer, Object> currentParameters = new LinkedHashMap<>();
        private boolean closed;

        PreparedStatementRecorder(int[] executeBatchResult) {
            this.executeBatchResult = executeBatchResult;
        }

        PreparedStatement proxy() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setTimestamp", "setDate", "setString", "setBigDecimal", "setLong", "setBoolean", "setInt", "setDouble" -> {
                    currentParameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "setNull" -> {
                    currentParameters.put((Integer) args[0], null);
                    yield null;
                }
                case "addBatch" -> {
                    batchParameters.add(new LinkedHashMap<>(currentParameters));
                    currentParameters.clear();
                    yield null;
                }
                case "executeBatch" -> executeBatchResult;
                case "close" -> {
                    closed = true;
                    yield null;
                }
                case "isClosed" -> closed;
                default -> defaultValue(method.getReturnType());
            };
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    handler
            );
        }

        List<Map<Integer, Object>> batchParameters() {
            return batchParameters;
        }
    }

    static final class ConnectionRecorder {
        private final List<PreparedStatement> statements;
        private final Savepoint savepoint = new Savepoint() {
            @Override
            public int getSavepointId() {
                return 1;
            }

            @Override
            public String getSavepointName() {
                return "sp";
            }
        };
        private final List<String> events = new ArrayList<>();
        private int prepareStatementCalls;
        private boolean autoCommit;

        ConnectionRecorder(boolean initialAutoCommit, PreparedStatement... statements) {
            this.statements = List.of(statements);
            this.autoCommit = initialAutoCommit;
        }

        Connection proxy() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> {
                    if (prepareStatementCalls >= statements.size()) {
                        throw new IllegalStateException("Unexpected prepareStatement call");
                    }
                    yield statements.get(prepareStatementCalls++);
                }
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> {
                    autoCommit = (Boolean) args[0];
                    events.add("setAutoCommit:" + autoCommit);
                    yield null;
                }
                case "commit" -> {
                    events.add("commit");
                    yield null;
                }
                case "rollback" -> {
                    events.add(args == null ? "rollback" : "rollbackToSavepoint");
                    yield null;
                }
                case "setSavepoint" -> {
                    events.add("setSavepoint");
                    yield savepoint;
                }
                case "releaseSavepoint" -> {
                    events.add("releaseSavepoint");
                    yield null;
                }
                case "close" -> null;
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            };
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    handler
            );
        }

        List<String> events() {
            return events;
        }
    }

    /**
     * Proxy for a PreparedStatement used in SELECT queries.
     * Returns a configurable ResultSet on {@code executeQuery()}.
     */
    static final class QueryStatementRecorder {
        private final List<List<Object>> resultRows;
        private final Map<Integer, Object> currentParameters = new LinkedHashMap<>();
        private boolean closed;

        /**
         * @param resultRows rows to return from the ResultSet. Each inner list
         *                   represents one row; column values are accessed by
         *                   1-based index.
         */
        QueryStatementRecorder(List<List<Object>> resultRows) {
            this.resultRows = resultRows;
        }

        PreparedStatement proxy() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setString", "setDate", "setTimestamp", "setInt", "setLong" -> {
                    currentParameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "executeQuery" -> resultSetProxy();
                case "clearParameters" -> {
                    currentParameters.clear();
                    yield null;
                }
                case "close" -> {
                    closed = true;
                    yield null;
                }
                case "isClosed" -> closed;
                default -> defaultValue(method.getReturnType());
            };
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    handler
            );
        }

        private ResultSet resultSetProxy() {
            int[] cursor = {-1};
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "next" -> {
                    cursor[0]++;
                    yield cursor[0] < resultRows.size();
                }
                case "getString" -> {
                    int col = (Integer) args[0];
                    yield resultRows.get(cursor[0]).get(col - 1);
                }
                case "getDate" -> {
                    int col = (Integer) args[0];
                    yield resultRows.get(cursor[0]).get(col - 1);
                }
                case "close" -> null;
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            };
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    handler
            );
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        throw new IllegalArgumentException("Unsupported primitive return type: " + returnType);
    }
}
