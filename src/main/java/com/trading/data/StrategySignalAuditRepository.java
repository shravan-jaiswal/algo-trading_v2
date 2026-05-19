package com.trading.data;

import com.trading.model.StrategySignalAudit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

public class StrategySignalAuditRepository {

    private static final Logger log = LoggerFactory.getLogger(StrategySignalAuditRepository.class);

    private final DatabaseConfig db;

    public StrategySignalAuditRepository(DatabaseConfig db) {
        this.db = db;
        ensureTable();
    }

    public void save(StrategySignalAudit row) {
        String sql = """
            INSERT INTO strategy_signal_audit (
                evaluated_at, token, symbol, strategy_name, candle_ts, candle_count,
                signal, reason, current_price, close, rsi, vwap, supertrend,
                supertrend_bullish, adx, volume_ratio, bull_score, bear_score
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(row.evaluatedAt()));
            ps.setString(2, row.token());
            ps.setString(3, row.symbol());
            ps.setString(4, row.strategyName());
            ps.setTimestamp(5, row.candleTs() != null ? Timestamp.valueOf(row.candleTs()) : null);
            ps.setInt(6, row.candleCount());
            ps.setString(7, row.signal());
            ps.setString(8, row.reason());
            ps.setDouble(9, row.currentPrice());
            ps.setDouble(10, row.close());
            ps.setDouble(11, row.rsi());
            ps.setDouble(12, row.vwap());
            ps.setDouble(13, row.supertrend());
            if (row.supertrendBullish() == null) ps.setNull(14, java.sql.Types.BOOLEAN);
            else ps.setBoolean(14, row.supertrendBullish());
            ps.setDouble(15, row.adx());
            ps.setDouble(16, row.volumeRatio());
            ps.setInt(17, row.bullScore());
            ps.setInt(18, row.bearScore());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Signal audit save failed: {}", e.getMessage());
        }
    }

    private void ensureTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS strategy_signal_audit (
                id                  BIGSERIAL PRIMARY KEY,
                evaluated_at        TIMESTAMP NOT NULL,
                token               VARCHAR(20) NOT NULL,
                symbol              VARCHAR(80) NOT NULL,
                strategy_name       VARCHAR(40) NOT NULL,
                candle_ts           TIMESTAMP,
                candle_count        INTEGER NOT NULL,
                signal              VARCHAR(10) NOT NULL,
                reason              TEXT,
                current_price       DOUBLE PRECISION,
                close               DOUBLE PRECISION,
                rsi                 DOUBLE PRECISION,
                vwap                DOUBLE PRECISION,
                supertrend          DOUBLE PRECISION,
                supertrend_bullish  BOOLEAN,
                adx                 DOUBLE PRECISION,
                volume_ratio        DOUBLE PRECISION,
                bull_score          INTEGER,
                bear_score          INTEGER
            )
            """;
        try (Connection conn = db.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_strategy_signal_audit_lookup
                ON strategy_signal_audit (token, strategy_name, candle_ts DESC)
                """);
        } catch (SQLException e) {
            log.warn("Strategy signal audit table setup failed: {}", e.getMessage());
        }
    }
}
