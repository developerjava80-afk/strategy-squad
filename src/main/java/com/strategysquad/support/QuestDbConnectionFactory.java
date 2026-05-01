package com.strategysquad.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Shared QuestDB/PostgreSQL-wire connection defaults for local Strategy Squad services.
 */
public final class QuestDbConnectionFactory {

    public static final String DEFAULT_USER = "admin";
    public static final String DEFAULT_PASSWORD = "quest";

    /** Seconds to wait for a TCP connection to QuestDB. */
    private static final String CONNECT_TIMEOUT_SECONDS = "5";

    /** Seconds to wait for a query response before failing with SQLException. */
    private static final String SOCKET_TIMEOUT_SECONDS = "10";

    private QuestDbConnectionFactory() {
    }

    public static Connection open(String jdbcUrl) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", DEFAULT_USER);
        props.setProperty("password", DEFAULT_PASSWORD);
        props.setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS);
        props.setProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS);
        return DriverManager.getConnection(jdbcUrl, props);
    }
}
