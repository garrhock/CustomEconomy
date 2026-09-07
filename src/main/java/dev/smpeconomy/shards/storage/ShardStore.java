package dev.smpeconomy.shards.storage;

import dev.smpeconomy.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;
import java.util.logging.Logger;

/**
 * Shard persistence on the plugin-wide {@link DatabaseManager} pool — the
 * `shards` table is created by schema migration v2, so shards follow whichever
 * backend config.yml selects (SQLITE or MYSQL) instead of their own file.
 *
 * Writes still go through a single-thread executor so they stay ordered per
 * player. The in-memory cache in ShardsService is the source of truth while a
 * player is online; this store only loads on join and mirrors changes.
 */
public final class ShardStore {

    private static final String UPSERT = """
        INSERT INTO shards (uuid, balance) VALUES (?, ?)
        ON CONFLICT(uuid) DO UPDATE SET balance = excluded.balance
        """;

    private static final String UPSERT_MYSQL = """
        INSERT INTO shards (uuid, balance) VALUES (?, ?)
        ON DUPLICATE KEY UPDATE balance = VALUES(balance)
        """;

    private final DatabaseManager db;
    private final Logger log;
    private final String upsertSql;
    private final ExecutorService dbThread =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "CustomEco-DB-Shards");
                t.setDaemon(true);
                return t;
            });

    public ShardStore(DatabaseManager db, boolean mysql, Logger log) {
        this.db  = db;
        this.log = log;
        this.upsertSql = mysql ? UPSERT_MYSQL : UPSERT;
    }

    public void loadAsync(UUID uuid, LongConsumer onLoaded) {
        dbThread.execute(() -> {
            long balance = 0L;
            try (Connection c = db.getConnection();
                 PreparedStatement st = c.prepareStatement("SELECT balance FROM shards WHERE uuid = ?")) {
                st.setString(1, uuid.toString());
                try (ResultSet rs = st.executeQuery()) {
                    if (rs.next()) {
                        balance = rs.getLong(1);
                    }
                }
            } catch (SQLException e) {
                // fall through with 0; the upsert on first earn recreates the row
                log.warning("Failed to load shards for " + uuid + ": " + e.getMessage());
            }
            onLoaded.accept(balance);
        });
    }

    public void saveAsync(UUID uuid, long balance) {
        dbThread.execute(() -> save(uuid, balance));
    }

    private void save(UUID uuid, long balance) {
        try (Connection c = db.getConnection();
             PreparedStatement st = c.prepareStatement(upsertSql)) {
            st.setString(1, uuid.toString());
            st.setLong(2, balance);
            st.executeUpdate();
        } catch (SQLException e) {
            log.warning("Failed to save shards for " + uuid + ": " + e.getMessage());
        }
    }

    /**
     * Drains queued writes. The pool itself belongs to DatabaseManager and is
     * closed there, after this returns.
     */
    public void shutdown() {
        dbThread.shutdown();
        try {
            if (!dbThread.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warning("Shard writes did not drain within 10s.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
