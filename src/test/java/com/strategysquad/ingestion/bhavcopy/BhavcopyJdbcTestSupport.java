package com.strategysquad.ingestion.bhavcopy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
                case "setTimestamp", "setDate", "setString", "setBigDecimal", "setLong", "setBoolean" -> {
                    currentParameters.put((Integer) args[0], args[1]);
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
