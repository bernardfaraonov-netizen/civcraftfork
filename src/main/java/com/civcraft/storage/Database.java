package com.civcraft.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connection pool plus a single writer thread. Every write goes through {@link #write}, so SQLite never
 * sees concurrent writers and MySQL writes keep their order. Reads at startup happen synchronously
 * before the server accepts players.
 */
public final class Database implements AutoCloseable {

    public enum Dialect { SQLITE, MYSQL }

    public record Settings(Dialect dialect, File sqliteFile, String host, int port, String database,
                           String user, String password, String tablePrefix, int poolSize) {
    }

    private final HikariDataSource dataSource;
    private final Dialect dialect;
    private final String prefix;
    private final ExecutorService writer;
    private final Logger logger;

    public Database(Settings settings, Logger logger) {
        this.logger = logger;
        this.dialect = settings.dialect();
        this.prefix = settings.tablePrefix();
        HikariConfig config = new HikariConfig();
        config.setPoolName("CivCraft");
        if (dialect == Dialect.SQLITE) {
            settings.sqliteFile().getParentFile().mkdirs();
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + settings.sqliteFile().getAbsolutePath());
            config.setMaximumPoolSize(1);
            config.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA foreign_keys=ON;");
        } else {
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setJdbcUrl("jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database()
                    + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true");
            config.setUsername(settings.user());
            config.setPassword(settings.password());
            config.setMaximumPoolSize(Math.max(2, settings.poolSize()));
        }
        this.dataSource = new HikariDataSource(config);
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CivCraft-DB-Writer");
            t.setDaemon(true);
            return t;
        });
    }

    public Dialect dialect() {
        return dialect;
    }

    public String table(String name) {
        return prefix + name;
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    public interface SqlWork {
        void run(Connection connection) throws SQLException;
    }

    /** Runs {@code work} on the writer thread inside a transaction. */
    public CompletableFuture<Void> write(SqlWork work) {
        return CompletableFuture.runAsync(() -> {
            try (Connection c = connection()) {
                c.setAutoCommit(false);
                try {
                    work.run(c);
                    c.commit();
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Database write failed", e);
                throw new IllegalStateException(e);
            }
        }, writer);
    }

    /** Runs {@code work} on the calling thread. Only for startup/shutdown. */
    public void blocking(SqlWork work) {
        try (Connection c = connection()) {
            work.run(c);
        } catch (SQLException e) {
            throw new IllegalStateException("Database error", e);
        }
    }

    public void execute(String sql) {
        blocking(c -> {
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
        });
    }

    /** Waits for queued writes to complete. */
    public void drain(long timeoutSeconds) {
        try {
            writer.submit(() -> { }).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Timed out waiting for database writes", e);
        }
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.severe("Database writer did not finish in 30s; some changes may be lost");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }
}
