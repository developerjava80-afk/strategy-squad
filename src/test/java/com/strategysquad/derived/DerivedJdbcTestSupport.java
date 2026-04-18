package com.strategysquad.derived;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DerivedJdbcTestSupport {
    private DerivedJdbcTestSupport() {
    }

    static final class PreparedStatementRecorder implements InvocationHandler {
        private final int[] executeBatchResult;
        private final List<Map<Integer, Object>> batchParameters = new ArrayList<>();
        private final Map<Integer, Object> currentParameters = new LinkedHashMap<>();

        PreparedStatementRecorder(int[] executeBatchResult) {
            this.executeBatchResult = executeBatchResult;
        }

        PreparedStatement proxy() {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class[]{PreparedStatement.class},
                    this
            );
        }

        List<Map<Integer, Object>> batchParameters() {
            return batchParameters;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "setTimestamp", "setDate", "setString", "setLong", "setNull" -> {
                    currentParameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "setDouble" -> {
                    currentParameters.put((Integer) args[0], BigDecimal.valueOf((Double) args[1]));
                    yield null;
                }
                case "addBatch" -> {
                    batchParameters.add(Map.copyOf(currentParameters));
                    currentParameters.clear();
                    yield null;
                }
                case "executeBatch" -> executeBatchResult;
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    static final class ConnectionRecorder implements InvocationHandler {
        private final PreparedStatement preparedStatement;
        private final boolean autoCommit;

        ConnectionRecorder(PreparedStatement preparedStatement, boolean autoCommit) {
            this.preparedStatement = preparedStatement;
            this.autoCommit = autoCommit;
        }

        Connection proxy() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class[]{Connection.class},
                    this
            );
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "prepareStatement" -> preparedStatement;
                case "getAutoCommit" -> autoCommit;
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (boolean.class.equals(type)) {
            return false;
        }
        if (char.class.equals(type)) {
            return '\0';
        }
        return 0;
    }
}
