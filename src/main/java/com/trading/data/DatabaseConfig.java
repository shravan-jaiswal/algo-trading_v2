package com.trading.data;

import com.trading.config.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    private final HikariDataSource dataSource;

    public DatabaseConfig() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(       AppConfig.get(   "db.url",          "jdbc:postgresql://localhost:5432/trading"));
        cfg.setUsername(      AppConfig.get(   "db.username",     "trading_user"));
        cfg.setPassword(      AppConfig.get(   "db.password",     "trading_pass"));
        cfg.setMaximumPoolSize(AppConfig.getInt("db.pool.max",    10));
        cfg.setMinimumIdle(   AppConfig.getInt("db.pool.min",     2));
        cfg.setConnectionTimeout(AppConfig.getInt("db.pool.timeout", 30000));
        cfg.setPoolName("trading-pool");
        cfg.addDataSourceProperty("cachePrepStmts",          "true");
        cfg.addDataSourceProperty("prepStmtCacheSize",       "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit",   "2048");

        this.dataSource = new HikariDataSource(cfg);
        log.info("Database pool initialized | url:{}", cfg.getJdbcUrl());
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
            log.info("Database pool closed.");
        }
    }
}
