package com.strategysquad.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Shared QuestDB/PostgreSQL-wire connection defaults for local Strategy Squad services.
 */
public final class QuestDbConnectionFactory {

    public static final String DEFAULT_USER = "admin";
    public static final String DEFAULT_PASSWORD = "quest";

    private QuestDbConnectionFactory() {
    }

    public static Connection open(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, DEFAULT_USER, DEFAULT_PASSWORD);
    }
}
