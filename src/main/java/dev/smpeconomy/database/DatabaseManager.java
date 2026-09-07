package dev.smpeconomy.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.smpeconomy.config.ConfigManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Manages the HikariCP connection pool and runs schema migrations.
 *
 * All actual queries live in the repository classes — this class is
 * responsible only for pool lifecycle and DDL.
 */
public final class DatabaseManager {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final Logger log;

    private HikariDataSource pool;

    public DatabaseManager(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.log    = plugin.getLogger();
    }

    /** Returns true if initialization succeeded. */
    public boolean initialize() {
        try {
            HikariConfig hc = new HikariConfig();

            if (config.isMysql()) {
                hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
                hc.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&characterEncoding=utf8",
                    config.getMysqlHost(), config.getMysqlPort(), config.getMysqlDatabase()));
                hc.setUsername(config.getMysqlUsername());
                hc.setPassword(config.getMysqlPassword());
                hc.setMaximumPoolSize(config.getMysqlPoolSize());
                hc.setMinimumIdle(2);
                hc.setConnectionTimeout(5_000);
                hc.setIdleTimeout(300_000);
                hc.setMaxLifetime(600_000);
                hc.addDataSourceProperty("cachePrepStmts", "true");
                hc.addDataSourceProperty("prepStmtCacheSize", "250");
                hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
                hc.addDataSourceProperty("useServerPrepStmts", "true");
                log.info("Using MySQL at " + config.getMysqlHost() + ":" + config.getMysqlPort());
            } else {
                File dbFile = new File(plugin.getDataFolder(), config.getSqliteFile());
                hc.setDriverClassName("org.sqlite.JDBC");
                hc.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
                hc.setMaximumPoolSize(1); // SQLite is single-writer
                hc.setConnectionTimeout(10_000);
                // WAL mode allows concurrent readers alongside one writer
                hc.addDataSourceProperty("journal_mode", "WAL");
                hc.addDataSourceProperty("synchronous", "NORMAL");
                log.info("Using SQLite at " + dbFile.getAbsolutePath());
            }

            hc.setPoolName("CustomEconomy-Pool");
            pool = new HikariDataSource(hc);

            migrate();
            return true;

        } catch (Exception e) {
            log.severe("Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /** Gracefully drains the connection pool. */
    public void shutdown() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
            log.info("Database pool closed.");
        }
    }

    /** Returns a connection from the pool — caller must close() it. */
    public Connection getConnection() throws SQLException {
        if (pool == null || pool.isClosed()) throw new SQLException("Pool is not initialized");
        return pool.getConnection();
    }

    // ── Schema migrations ────────────────────────────────────────────────────

    private void migrate() throws SQLException {
        try (Connection con = getConnection(); Statement st = con.createStatement()) {

            // Schema version table
            st.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version     INTEGER NOT NULL,
                    applied_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            int current = currentVersion(con);

            if (current < 1) applyV1(st);
            if (current < 2) applyV2(st);
            if (current < 3) applyV3(st);
            if (current < 4) applyV4(st);

            if (current < 1) setVersion(con, 1);
            if (current < 2) setVersion(con, 2);
            if (current < 3) setVersion(con, 3);
            if (current < 4) setVersion(con, 4);
        }
    }

    private int currentVersion(Connection con) {
        try (var ps = con.prepareStatement("SELECT MAX(version) FROM schema_version");
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    private void setVersion(Connection con, int version) throws SQLException {
        try (var ps = con.prepareStatement("INSERT INTO schema_version (version) VALUES (?)")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }

    private void applyV1(Statement st) throws SQLException {
        log.info("Applying database schema v1...");

        // Player economy metadata (balance is managed by Vault/Essentials)
        st.execute("""
            CREATE TABLE IF NOT EXISTS player_data (
                uuid            VARCHAR(36)  NOT NULL PRIMARY KEY,
                username        VARCHAR(16)  NOT NULL,
                total_earned    DECIMAL(20,4) NOT NULL DEFAULT 0,
                total_sold      BIGINT        NOT NULL DEFAULT 0,
                first_seen      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                last_seen       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);

        // Per-category multiplier progression (populated in Phase 2)
        st.execute("""
            CREATE TABLE IF NOT EXISTS category_progression (
                uuid        VARCHAR(36)  NOT NULL,
                category    VARCHAR(64)  NOT NULL,
                xp          BIGINT       NOT NULL DEFAULT 0,
                level       INTEGER      NOT NULL DEFAULT 0,
                multiplier  DECIMAL(6,4) NOT NULL DEFAULT 1.0000,
                updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (uuid, category)
            )
            """);

        // Dynamic market prices (populated in Phase 2)
        st.execute("""
            CREATE TABLE IF NOT EXISTS market_prices (
                item_key        VARCHAR(128) NOT NULL PRIMARY KEY,
                base_price      DECIMAL(20,4) NOT NULL,
                current_price   DECIMAL(20,4) NOT NULL,
                total_sold      BIGINT        NOT NULL DEFAULT 0,
                price_frozen    BOOLEAN       NOT NULL DEFAULT FALSE,
                last_updated    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);

        // Transaction log — fire-and-forget async writes
        st.execute(config.isMysql()
            ? """
              CREATE TABLE IF NOT EXISTS transactions (
                  id              BIGINT        NOT NULL PRIMARY KEY AUTO_INCREMENT,
                  uuid            VARCHAR(36)   NOT NULL,
                  item_key        VARCHAR(128)  NOT NULL,
                  quantity        INTEGER       NOT NULL,
                  price_per_unit  DECIMAL(20,4) NOT NULL,
                  multiplier      DECIMAL(6,4)  NOT NULL DEFAULT 1.0000,
                  total_earned    DECIMAL(20,4) NOT NULL,
                  source          VARCHAR(32)   NOT NULL DEFAULT 'SELL_HAND',
                  created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
              )
              """
            : """
              CREATE TABLE IF NOT EXISTS transactions (
                  id              INTEGER       PRIMARY KEY AUTOINCREMENT,
                  uuid            VARCHAR(36)   NOT NULL,
                  item_key        VARCHAR(128)  NOT NULL,
                  quantity        INTEGER       NOT NULL,
                  price_per_unit  DECIMAL(20,4) NOT NULL,
                  multiplier      DECIMAL(6,4)  NOT NULL DEFAULT 1.0000,
                  total_earned    DECIMAL(20,4) NOT NULL,
                  source          VARCHAR(32)   NOT NULL DEFAULT 'SELL_HAND',
                  created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
              )
              """);

        // Indexes for common queries
        safeExec(st, "CREATE INDEX IF NOT EXISTS idx_tx_uuid    ON transactions(uuid)");
        safeExec(st, "CREATE INDEX IF NOT EXISTS idx_tx_item    ON transactions(item_key)");
        safeExec(st, "CREATE INDEX IF NOT EXISTS idx_tx_created ON transactions(created_at)");

        log.info("Schema v1 applied.");
    }

    private void applyV2(Statement st) throws SQLException {
        log.info("Applying database schema v2...");

        // Player-to-player listings: both sell offers and buy orders
        st.execute(config.isMysql()
            ? """
              CREATE TABLE IF NOT EXISTS player_listings (
                  id                BIGINT        NOT NULL PRIMARY KEY AUTO_INCREMENT,
                  owner_uuid        VARCHAR(36)   NOT NULL,
                  owner_name        VARCHAR(16)   NOT NULL,
                  listing_type      VARCHAR(4)    NOT NULL,
                  item_key          VARCHAR(128)  NOT NULL,
                  item_display_name VARCHAR(256)  NOT NULL,
                  item_data         MEDIUMBLOB    NOT NULL,
                  quantity_total    INTEGER       NOT NULL,
                  quantity_filled   INTEGER       NOT NULL DEFAULT 0,
                  price_per_unit    DECIMAL(20,4) NOT NULL,
                  status            VARCHAR(12)   NOT NULL DEFAULT 'ACTIVE',
                  created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  expires_at        TIMESTAMP     NOT NULL
              )
              """
            : """
              CREATE TABLE IF NOT EXISTS player_listings (
                  id                INTEGER       PRIMARY KEY AUTOINCREMENT,
                  owner_uuid        VARCHAR(36)   NOT NULL,
                  owner_name        VARCHAR(16)   NOT NULL,
                  listing_type      VARCHAR(4)    NOT NULL,
                  item_key          VARCHAR(128)  NOT NULL,
                  item_display_name VARCHAR(256)  NOT NULL,
                  item_data         BLOB          NOT NULL,
                  quantity_total    INTEGER       NOT NULL,
                  quantity_filled   INTEGER       NOT NULL DEFAULT 0,
                  price_per_unit    DECIMAL(20,4) NOT NULL,
                  status            VARCHAR(12)   NOT NULL DEFAULT 'ACTIVE',
                  created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  expires_at        TIMESTAMP     NOT NULL
              )
              """);

        // Per-player item storage: unclaimed items from cancelled/expired/fulfilled listings
        st.execute(config.isMysql()
            ? """
              CREATE TABLE IF NOT EXISTS player_storage (
                  id         BIGINT     NOT NULL PRIMARY KEY AUTO_INCREMENT,
                  owner_uuid VARCHAR(36) NOT NULL,
                  item_data  MEDIUMBLOB NOT NULL,
                  quantity   INTEGER    NOT NULL,
                  created_at TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
              )
              """
            : """
              CREATE TABLE IF NOT EXISTS player_storage (
                  id         INTEGER    PRIMARY KEY AUTOINCREMENT,
                  owner_uuid VARCHAR(36) NOT NULL,
                  item_data  BLOB       NOT NULL,
                  quantity   INTEGER    NOT NULL,
                  created_at TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
              )
              """);

        safeExec(st, "CREATE INDEX IF NOT EXISTS idx_listings_owner   ON player_listings(owner_uuid)");
        safeExec(st, "CREATE INDEX IF NOT EXISTS idx_listings_status  ON player_listings(status, listing_type)");
        safeExec(st, "CREATE INDEX IF NOT EXISTS idx_listings_expires ON player_listings(expires_at, status)");
        safeExec(st, "CREATE INDEX IF NOT EXISTS idx_storage_owner    ON player_storage(owner_uuid)");

        log.info("Schema v2 applied.");
    }

    private void applyV3(Statement st) throws SQLException {
        log.info("Applying database schema v3...");

        // Link each claim-storage row to the listing that produced it, so My Offers
        // can tell when a completed listing's items have all been claimed.
        // Nullable; identical DDL on SQLite and MySQL. safeExec so a partial re-run self-heals.
        safeExec(st, "ALTER TABLE player_storage ADD COLUMN listing_id BIGINT");
        safeExec(st, "CREATE INDEX IF NOT EXISTS idx_storage_listing ON player_storage(listing_id)");

        log.info("Schema v3 applied.");
    }

    private void applyV4(Statement st) throws SQLException {
        log.info("Applying database schema v4...");

        // Shard balances. Shards were a separate plugin with their own
        // shards.db until the merge; LegacyShardImport carries those rows over
        // on the first start after upgrading.
        st.execute("""
            CREATE TABLE IF NOT EXISTS shards (
                uuid        VARCHAR(36) NOT NULL PRIMARY KEY,
                balance     BIGINT      NOT NULL DEFAULT 0,
                updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);

        log.info("Schema v4 applied.");
    }

    private void safeExec(Statement st, String sql) {
        try { st.execute(sql); } catch (SQLException ignored) {}
    }
}
