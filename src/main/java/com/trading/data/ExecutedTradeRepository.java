package com.trading.data;

import com.trading.model.ExecutedTrade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class ExecutedTradeRepository {

    private static final Logger log = LoggerFactory.getLogger(ExecutedTradeRepository.class);

    private final DatabaseConfig db;

    public ExecutedTradeRepository(DatabaseConfig db) {
        this.db = db;
        ensureTable();
    }

    public void save(ExecutedTrade trade) {
        String sql = """
            INSERT INTO executed_trades
              (pos_key, token, symbol, strategy_name, entry_price, quantity, side, status,
               stop_loss, take_profit, exchange, entry_time)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, trade.getPosKey());
            ps.setString(2, trade.getToken());
            ps.setString(3, trade.getSymbol());
            ps.setString(4, trade.getStrategyName());
            ps.setDouble(5, trade.getEntryPrice());
            ps.setInt(   6, trade.getQuantity());
            ps.setString(7, trade.getSide());
            ps.setString(8, trade.getStatus());
            ps.setDouble(9, trade.getStopLoss());
            ps.setDouble(10, trade.getTakeProfit());
            ps.setString(11, trade.getExchange());
            ps.setTimestamp(12, Timestamp.valueOf(trade.getEntryTime()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) trade.setId(keys.getInt(1));
            }
        } catch (SQLException e) {
            log.error("Save trade failed: {}", e.getMessage());
        }
    }

    public void close(ExecutedTrade trade) {
        String sql = """
            UPDATE executed_trades
            SET status='CLOSED', exit_price=?, exit_time=?, pnl=?, exit_reason=?
            WHERE id=?
            """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, trade.getExitPrice());
            ps.setTimestamp(2, Timestamp.valueOf(trade.getExitTime()));
            ps.setDouble(3, trade.getPnl());
            ps.setString(4, trade.getExitReason());
            ps.setInt(5, trade.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Close trade failed: {}", e.getMessage());
        }
    }

    public List<ExecutedTrade> findOpen() {
        String sql = "SELECT * FROM executed_trades WHERE status='OPEN' ORDER BY entry_time ASC";
        List<ExecutedTrade> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) result.add(mapRow(rs));
        } catch (SQLException e) {
            log.error("findOpen failed: {}", e.getMessage());
        }
        return result;
    }

    private ExecutedTrade mapRow(ResultSet rs) throws SQLException {
        ExecutedTrade t = new ExecutedTrade();
        t.setId(rs.getInt("id"));
        t.setPosKey(rs.getString("pos_key"));
        t.setToken(rs.getString("token"));
        t.setSymbol(rs.getString("symbol"));
        t.setStrategyName(rs.getString("strategy_name"));
        t.setEntryPrice(rs.getDouble("entry_price"));
        t.setQuantity(rs.getInt("quantity"));
        t.setSide(rs.getString("side"));
        t.setStatus(rs.getString("status"));
        t.setStopLoss(rs.getDouble("stop_loss"));
        t.setTakeProfit(rs.getDouble("take_profit"));
        t.setExchange(rs.getString("exchange"));
        Timestamp entryTs = rs.getTimestamp("entry_time");
        if (entryTs != null) t.setEntryTime(entryTs.toLocalDateTime());
        t.setExitReason(readOptionalString(rs, "exit_reason"));
        return t;
    }

    private String readOptionalString(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private void ensureTable() {
        String create = """
            CREATE TABLE IF NOT EXISTS executed_trades (
                id             SERIAL PRIMARY KEY,
                pos_key        VARCHAR(100),
                token          VARCHAR(20),
                symbol         VARCHAR(50),
                strategy_name  VARCHAR(50),
                entry_price    DOUBLE PRECISION,
                quantity       INT,
                side           VARCHAR(10),
                status         VARCHAR(10) DEFAULT 'OPEN',
                stop_loss      DOUBLE PRECISION,
                take_profit    DOUBLE PRECISION,
                exchange       VARCHAR(10),
                entry_time     TIMESTAMP,
                exit_time      TIMESTAMP,
                exit_price     DOUBLE PRECISION,
                pnl            DOUBLE PRECISION,
                exit_reason    VARCHAR(50)
            )
            """;
        String alter = "ALTER TABLE executed_trades ADD COLUMN IF NOT EXISTS pos_key VARCHAR(100)";
        String alterExitReason = "ALTER TABLE executed_trades ADD COLUMN IF NOT EXISTS exit_reason VARCHAR(50)";
        try (Connection conn = db.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(create);
            st.execute(alter);
            st.execute(alterExitReason);
        } catch (SQLException e) {
            log.error("Create executed_trades table failed: {}", e.getMessage());
        }
    }
}
