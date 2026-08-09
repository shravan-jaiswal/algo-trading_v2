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
        this(db, true);
    }

    /**
     * @param ensureSchema false for read-only diagnostics/backtests against an existing database
     */
    public WatchlistRepository(DatabaseConfig db, boolean ensureSchema) {
        this.db = db;
        if (ensureSchema) ensureTable();
    }

    public List<WatchlistItem> findAll() {
        List<WatchlistItem> items = new ArrayList<>();
        try {
            items = query("SELECT token, symbol, exchange, instrument_type, strategies FROM watchlist WHERE active=true");
        } catch (SQLException e) {
            log.error("Watchlist load failed: {}", e.getMessage());
        }
        return items;
    }

    private List<WatchlistItem> query(String sql) throws SQLException {
        List<WatchlistItem> items = new ArrayList<>();
        try (Connection conn = db.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                items.add(new WatchlistItem(
                    rs.getString("token"),
                    rs.getString("symbol"),
                    rs.getString("exchange"),
                    rs.getString("instrument_type"),
                    WatchlistItem.parseStrategies(rs.getString("strategies"))
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
                    instrument_type VARCHAR(10)  NOT NULL DEFAULT 'EQ',
                    active          BOOLEAN      NOT NULL DEFAULT true,
                    strategies      VARCHAR(200) NOT NULL DEFAULT 'VSRSI'
                )
                """);
            // Best-effort migrations for older schemas
            try { st.execute("ALTER TABLE watchlist ADD COLUMN IF NOT EXISTS instrument_type VARCHAR(10) NOT NULL DEFAULT 'EQ'"); } catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE watchlist ADD COLUMN IF NOT EXISTS strategies VARCHAR(200) NOT NULL DEFAULT 'VSRSI'"); } catch (SQLException ignored) {}
        } catch (SQLException e) {
            log.warn("Watchlist table setup: {}", e.getMessage());
        }
    }
}
