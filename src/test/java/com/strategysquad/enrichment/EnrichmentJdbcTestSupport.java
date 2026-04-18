package com.strategysquad.enrichment;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EnrichmentJdbcTestSupport {
    private EnrichmentJdbcTestSupport() {
    }

    static final class PreparedStatementRecorder {
        private final List<Map<Integer, Object>> batchParameters = new ArrayList<>();
        private final Map<Integer, Object> currentParameters = new LinkedHashMap<>();
        private final int[] executeBatchResult;
        private final ResultSet resultSet;

        PreparedStatementRecorder(int[] executeBatchResult) {
            this(executeBatchResult, null);
        }

        PreparedStatementRecorder(int[] executeBatchResult, ResultSet resultSet) {
            this.executeBatchResult = executeBatchResult;
            this.resultSet = resultSet;
        }

        PreparedStatement proxy() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setTimestamp", "setString", "setBigDecimal", "setInt", "setLong" -> {
                    currentParameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "setDouble" -> {
                    currentParameters.put((Integer) args[0], BigDecimal.valueOf((Double) args[1]));
                    yield null;
                }
                case "addBatch" -> {
                    batchParameters.add(new LinkedHashMap<>(currentParameters));
                    currentParameters.clear();
                    yield null;
                }
                case "executeBatch" -> executeBatchResult;
                case "executeQuery" -> resultSet;
                case "close" -> null;
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

        Map<Integer, Object> currentParameters() {
            return currentParameters;
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
        private boolean autoCommit;

        ConnectionRecorder(PreparedStatement statement, boolean autoCommit) {
            this.statement = statement;
            this.autoCommit = autoCommit;
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

    static ResultSet resultSet(List<Map<String, Object>> rows) {
        InvocationHandler handler = new InvocationHandler() {
            private int index = -1;

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                return switch (method.getName()) {
                    case "next" -> ++index < rows.size();
                    case "getString", "getBigDecimal", "getTimestamp" -> rows.get(index).get(args[0]);
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                };
            }
        };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                handler
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == void.class) {
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
