package com.trading.data;

import com.trading.model.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class CandleRepository {

    private static final Logger log = LoggerFactory.getLogger(CandleRepository.class);

    private final DatabaseConfig db;

    public CandleRepository(DatabaseConfig db) {
        this(db, true);
    }

    /**
     * @param ensureSchema false for read-only diagnostics/backtests against an existing database
     */
    public CandleRepository(DatabaseConfig db, boolean ensureSchema) {
        this.db = db;
        if (ensureSchema) ensureTable();
    }

    public void upsert(Candle c) {
        String sql = """
            INSERT INTO candles (token, timeframe, ts, open, high, low, close, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (token, timeframe, ts) DO UPDATE
            SET high=EXCLUDED.high, low=EXCLUDED.low,
                close=EXCLUDED.close, volume=COALESCE(EXCLUDED.volume, 0)
            """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sanitize(c.getToken()));
            ps.setString(2, sanitize(c.getTimeframe()));
            ps.setTimestamp(3, Timestamp.valueOf(c.getTs()));
            ps.setDouble(4, c.getOpen());
            ps.setDouble(5, c.getHigh());
            ps.setDouble(6, c.getLow());
            ps.setDouble(7, c.getClose());
            ps.setDouble(8, Math.max(c.getVolume(), 0));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Candle upsert failed: {}", e.getMessage());
        }
    }

    public List<Candle> findRecent(String token, String timeframe, int limit) {
        String sql = """
            SELECT token, timeframe, ts, open, high, low, close, volume
            FROM candles
            WHERE token=? AND timeframe=?
            ORDER BY ts DESC LIMIT ?
            """;
        List<Candle> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setString(2, timeframe);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(0, mapRow(rs));  // prepend to maintain chronological order
                }
            }
        } catch (SQLException e) {
            log.error("findRecent failed for {}: {}", token, e.getMessage());
        }
        return result;
    }

    public List<Candle> findBetween(String token, String timeframe,
                                     LocalDateTime from, LocalDateTime to) {
        String sql = """
            SELECT token, timeframe, ts, open, high, low, close, volume
            FROM candles
            WHERE token=? AND timeframe=? AND ts BETWEEN ? AND ?
            ORDER BY ts ASC
            """;
        List<Candle> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setString(2, timeframe);
            ps.setTimestamp(3, Timestamp.valueOf(from));
            ps.setTimestamp(4, Timestamp.valueOf(to));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapRow(rs));
            }
        } catch (SQLException e) {
            log.error("findBetween failed: {}", e.getMessage());
        }
        return result;
    }

    public List<Candle> findRecentBefore(String token, String timeframe,
                                         LocalDateTime before, int limit) {
        String sql = """
            SELECT token, timeframe, ts, open, high, low, close, volume
            FROM candles
            WHERE token=? AND timeframe=? AND ts < ?
            ORDER BY ts DESC LIMIT ?
            """;
        List<Candle> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setString(2, timeframe);
            ps.setTimestamp(3, Timestamp.valueOf(before));
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(0, mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("findRecentBefore failed for {}: {}", token, e.getMessage());
        }
        return result;
    }

    /** Strips null bytes that the broker WebSocket occasionally embeds in token strings. */
    private static String sanitize(String s) {
        return s == null ? null : s.replaceAll("\\u0000", "");
    }

    private Candle mapRow(ResultSet rs) throws SQLException {
        return new Candle(
            rs.getString("token"),
            rs.getString("timeframe"),
            rs.getTimestamp("ts").toLocalDateTime(),
            rs.getDouble("open"),
            rs.getDouble("high"),
            rs.getDouble("low"),
            rs.getDouble("close"),
            rs.getDouble("volume")
        );
    }

    private void ensureTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS candles (
                token     VARCHAR(20)  NOT NULL,
                timeframe VARCHAR(20)  NOT NULL,
                ts        TIMESTAMP    NOT NULL,
                open      DOUBLE PRECISION,
                high      DOUBLE PRECISION,
                low       DOUBLE PRECISION,
                close     DOUBLE PRECISION,
                volume    DOUBLE PRECISION,
                PRIMARY KEY (token, timeframe, ts)
            )
            """;
        try (Connection conn = db.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
            st.execute("UPDATE candles SET volume = 0 WHERE volume IS NULL");
            st.execute("ALTER TABLE candles ALTER COLUMN volume SET DEFAULT 0");
            st.execute("ALTER TABLE candles ALTER COLUMN volume SET NOT NULL");
        } catch (SQLException e) {
            log.error("Create candles table failed: {}", e.getMessage());
        }
    }
}
