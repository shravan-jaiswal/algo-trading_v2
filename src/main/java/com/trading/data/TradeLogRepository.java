package com.trading.data;

import com.trading.model.TradeLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class TradeLogRepository {

    private static final Logger log = LoggerFactory.getLogger(TradeLogRepository.class);

    private final DatabaseConfig db;

    public TradeLogRepository(DatabaseConfig db) {
        this.db = db;
        ensureTable();
    }

    public void log(TradeLog t) {
        String sql = """
            INSERT INTO trade_logs
              (token, symbol, strategy_name, side, entry_price, exit_price,
               qty, pnl, exit_reason, entry_time, exit_time)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.token());
            ps.setString(2, t.symbol());
            ps.setString(3, t.strategyName());
            ps.setString(4, t.side());
            ps.setDouble(5, t.entryPrice());
            ps.setDouble(6, t.exitPrice());
            ps.setInt(   7, t.qty());
            ps.setDouble(8, t.pnl());
            ps.setString(9, t.exitReason());
            ps.setTimestamp(10, Timestamp.valueOf(t.entryTime()));
            ps.setTimestamp(11, Timestamp.valueOf(t.exitTime()));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("TradeLog insert failed: {}", e.getMessage());
        }
    }

    public List<TradeLog> findByDate(LocalDate date) {
        String sql = """
            SELECT * FROM trade_logs WHERE DATE(entry_time) = ? ORDER BY entry_time ASC
            """;
        List<TradeLog> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new TradeLog(
                        rs.getInt("id"),
                        rs.getString("token"),
                        rs.getString("symbol"),
                        rs.getString("strategy_name"),
                        rs.getString("side"),
                        rs.getDouble("entry_price"),
                        rs.getDouble("exit_price"),
                        rs.getInt("qty"),
                        rs.getDouble("pnl"),
                        rs.getString("exit_reason"),
                        rs.getTimestamp("entry_time").toLocalDateTime(),
                        rs.getTimestamp("exit_time").toLocalDateTime()
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("findByDate failed: {}", e.getMessage());
        }
        return result;
    }

    private void ensureTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS trade_logs (
                id            SERIAL PRIMARY KEY,
                token         VARCHAR(20),
                symbol        VARCHAR(50),
                strategy_name VARCHAR(50),
                side          VARCHAR(10),
                entry_price   DOUBLE PRECISION,
                exit_price    DOUBLE PRECISION,
                qty           INT,
                pnl           DOUBLE PRECISION,
                exit_reason   VARCHAR(50),
                entry_time    TIMESTAMP,
                exit_time     TIMESTAMP
            )
            """;
        try (Connection conn = db.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            log.error("Create trade_logs table failed: {}", e.getMessage());
        }
    }
}
