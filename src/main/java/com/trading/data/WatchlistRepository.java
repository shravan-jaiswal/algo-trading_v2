package com.trading.data;

import com.trading.model.WatchlistItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class WatchlistRepository {

    private static final Logger log = LoggerFactory.getLogger(WatchlistRepository.class);

    private final DatabaseConfig db;

    public WatchlistRepository(DatabaseConfig db) {
        this.db = db;
        ensureTable();
    }

    public List<WatchlistItem> findAll() {
        List<WatchlistItem> items = new ArrayList<>();
        // Try full query first; fall back without instrument_type for legacy v1 schema
        try {
            items = query("SELECT token, symbol, exchange, instrument_type FROM watchlist WHERE active=true", true);
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("instrument_type")) {
                log.warn("instrument_type column missing - run migration: " +
                         "ALTER TABLE watchlist ADD COLUMN IF NOT EXISTS instrument_type VARCHAR(10) NOT NULL DEFAULT 'EQ'; " +
                         "Defaulting all instruments to EQ.");
                try {
                    items = query("SELECT token, symbol, exchange FROM watchlist WHERE active=true", false);
                } catch (SQLException ex) {
                    log.error("Watchlist load failed: {}", ex.getMessage());
                }
            } else {
                log.error("Watchlist load failed: {}", e.getMessage());
            }
        }
        return items;
    }

    private List<WatchlistItem> query(String sql, boolean hasInstrumentType) throws SQLException {
        List<WatchlistItem> items = new ArrayList<>();
        try (Connection conn = db.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String instrType = hasInstrumentType ? rs.getString("instrument_type") : "EQ";
                items.add(new WatchlistItem(
                    rs.getString("token"),
                    rs.getString("symbol"),
                    rs.getString("exchange"),
                    instrType
                ));
            }
        }
        return items;
    }

    private void ensureTable() {
        try (Connection conn = db.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS watchlist (
                    token           VARCHAR(20) PRIMARY KEY,
                    symbol          VARCHAR(50) NOT NULL,
                    exchange        VARCHAR(10) NOT NULL,
                    instrument_type VARCHAR(10) NOT NULL DEFAULT 'EQ',
                    active          BOOLEAN NOT NULL DEFAULT true
                )
                """);
            // Best-effort migration for existing v1 tables (requires table ownership)
            try {
                st.execute("ALTER TABLE watchlist ADD COLUMN IF NOT EXISTS instrument_type VARCHAR(10) NOT NULL DEFAULT 'EQ'");
            } catch (SQLException ignored) {
                // Will be handled gracefully in findAll() if column is still missing
            }
        } catch (SQLException e) {
            log.warn("Watchlist table setup: {}", e.getMessage());
        }
    }
}
