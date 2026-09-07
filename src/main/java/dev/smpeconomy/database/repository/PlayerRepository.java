package dev.smpeconomy.database.repository;

import dev.smpeconomy.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Async repository for player metadata.
 * All public methods return CompletableFuture and run on a dedicated thread pool,
 * never touching the main thread.
 */
public final class PlayerRepository {

    private static final Executor DB_POOL =
        Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "CustomEco-DB-Player");
            t.setDaemon(true);
            return t;
        });

    private final DatabaseManager db;
    private final Logger log;

    public PlayerRepository(DatabaseManager db) {
        this.db  = db;
        this.log = Logger.getLogger("CustomEconomy");
    }

    /**
     * Upserts the player's username and bumps last_seen.
     * Called asynchronously when a player joins.
     */
    public CompletableFuture<Void> upsertPlayer(UUID uuid, String username) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO player_data (uuid, username, last_seen)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(uuid) DO UPDATE
                  SET username  = excluded.username,
                      last_seen = CURRENT_TIMESTAMP
                """;
            try (Connection con = db.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, username);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warning("Failed to upsert player " + uuid + ": " + e.getMessage());
            }
        }, DB_POOL);
    }

    /**
     * Adds to total_earned and total_sold counters.
     * Called after each successful sell transaction.
     */
    public CompletableFuture<Void> addEarnings(UUID uuid, double earned, int sold) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                UPDATE player_data
                SET total_earned = total_earned + ?,
                    total_sold   = total_sold   + ?
                WHERE uuid = ?
                """;
            try (Connection con = db.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setDouble(1, earned);
                ps.setInt(2, sold);
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warning("Failed to add earnings for " + uuid + ": " + e.getMessage());
            }
        }, DB_POOL);
    }
}
