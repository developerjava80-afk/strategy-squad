package com.strategysquad.ingestion.live;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JdbcRecordingSupport {
    private JdbcRecordingSupport() {
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
                case "setTimestamp", "setString", "setBigDecimal", "setLong" -> {
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
        private final PreparedStatement statement;
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
        private final boolean initialAutoCommit;
        private boolean autoCommit;

        ConnectionRecorder(PreparedStatement statement, boolean initialAutoCommit) {
            this.statement = statement;
            this.initialAutoCommit = initialAutoCommit;
            this.autoCommit = initialAutoCommit;
        }

        Connection proxy() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> statement;
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

        boolean initialAutoCommit() {
            return initialAutoCommit;
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
