package com.trading.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;

public class DailySummaryRepository {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryRepository.class);

    private final DatabaseConfig db;

    public DailySummaryRepository(DatabaseConfig db) {
        this.db = db;
        ensureTable();
    }

    public void save(LocalDate date, int trades, double grossPnl,
                     double grossLoss, double netPnl) {
        String sql = """
            INSERT INTO daily_summary (trade_date, trades, gross_profit, gross_loss, net_pnl)
            VALUES (?,?,?,?,?)
            ON CONFLICT (trade_date) DO UPDATE
            SET trades=EXCLUDED.trades, gross_profit=EXCLUDED.gross_profit,
                gross_loss=EXCLUDED.gross_loss, net_pnl=EXCLUDED.net_pnl
            """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1,   Date.valueOf(date));
            ps.setInt(2,    trades);
            ps.setDouble(3, grossPnl);
            ps.setDouble(4, grossLoss);
            ps.setDouble(5, netPnl);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("DailySummary save failed: {}", e.getMessage());
        }
    }

    private void ensureTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS daily_summary (
                trade_date   DATE PRIMARY KEY,
                trades       INT,
                gross_profit DOUBLE PRECISION,
                gross_loss   DOUBLE PRECISION,
                net_pnl      DOUBLE PRECISION
            )
            """;
        try (Connection conn = db.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            log.error("Create daily_summary table failed: {}", e.getMessage());
        }
    }
}
