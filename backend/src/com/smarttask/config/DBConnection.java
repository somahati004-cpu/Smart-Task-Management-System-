package com.smarttask.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class DBConnection {
    private static final String DEFAULT_URL =
            "jdbc:oracle:thin:@//localhost:1522/orcl";

    private DBConnection() {
    }

    public static Connection getConnection() throws SQLException {
        String url = setting("SMARTTASK_DB_URL", DEFAULT_URL);
        String username = setting("SMARTTASK_DB_USER", null);
        String password = setting("SMARTTASK_DB_PASSWORD", null);

        if (username == null || password == null) {
            throw new IllegalStateException(
                    "Set SMARTTASK_DB_USER and SMARTTASK_DB_PASSWORD before starting the backend.");
        }

        try {
            Class.forName("oracle.jdbc.driver.OracleDriver");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException(
                    "Oracle JDBC driver was not found on the classpath.", exception);
        }

        return DriverManager.getConnection(url, username, password);
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
