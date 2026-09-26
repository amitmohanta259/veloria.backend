package com.veloria.automation.db;

import com.veloria.automation.support.Config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain JDBC against the application database, for two purposes only:
 * seeding deterministic test data and verifying what the application wrote.
 *
 * Timestamps are compared in UTC because the application stores
 * {@code Instant} into naive TIMESTAMP columns as UTC; comparing against a
 * zoned {@code now()} would be off by the session's UTC offset.
 */
public final class Db {

    private Db() {}

    public static Connection connect() {
        try {
            return DriverManager.getConnection(Config.dbUrl(), Config.dbUser(), Config.dbPassword());
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot connect to " + Config.dbUrl(), e);
        }
    }

    public static int execute(String sql, Object... args) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("SQL failed: " + sql, e);
        }
    }

    public static List<Map<String, Object>> query(String sql, Object... args) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQL failed: " + sql, e);
        }
    }

    public static Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = query(sql, args);
        if (rows.isEmpty()) throw new AssertionError("No row for: " + sql);
        return rows.get(0);
    }

    public static long count(String sql, Object... args) {
        Object v = one(sql, args).values().iterator().next();
        return ((Number) v).longValue();
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
    }
}
