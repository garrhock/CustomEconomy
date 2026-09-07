package dev.smpeconomy.database.repository;

import dev.smpeconomy.database.DatabaseManager;
import dev.smpeconomy.model.ItemCategory;

import java.sql.*;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.logging.Logger;

public final class ProgressionRepository {

    private static final Executor DB_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "CustomEco-DB-Prog");
        t.setDaemon(true);
        return t;
    });

    private final DatabaseManager db;
    private final Logger log;

    public ProgressionRepository(DatabaseManager db) {
        this.db  = db;
        this.log = Logger.getLogger("CustomEconomy");
    }

    /** Loads all category XP totals for a player from the database. */
    public CompletableFuture<Map<ItemCategory, Long>> load(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            Map<ItemCategory, Long> result = new EnumMap<>(ItemCategory.class);
            String sql = "SELECT category, xp FROM category_progression WHERE uuid = ?";
            try (Connection con = db.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ItemCategory cat = ItemCategory.fromString(rs.getString("category"));
                        result.put(cat, rs.getLong("xp"));
                    }
                }
            } catch (SQLException e) {
                log.warning("Failed to load progression for " + uuid + ": " + e.getMessage());
            }
            return result;
        }, DB_POOL);
    }

    /** Upserts all category entries for a player in a single batched statement. */
    public CompletableFuture<Void> save(UUID uuid,
                                        Map<ItemCategory, Long> xpMap,
                                        Function<Long, Integer> levelOf,
                                        Function<Integer, Double> multiplierOf) {
        if (xpMap.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO category_progression (uuid, category, xp, level, multiplier, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(uuid, category) DO UPDATE
                  SET xp         = excluded.xp,
                      level      = excluded.level,
                      multiplier = excluded.multiplier,
                      updated_at = CURRENT_TIMESTAMP
                """;
            try (Connection con = db.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                for (Map.Entry<ItemCategory, Long> entry : xpMap.entrySet()) {
                    int    level = levelOf.apply(entry.getValue());
                    double mult  = multiplierOf.apply(level);
                    ps.setString(1, uuid.toString());
                    ps.setString(2, entry.getKey().name());
                    ps.setLong(3, entry.getValue());
                    ps.setInt(4, level);
                    ps.setDouble(5, mult);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                log.warning("Failed to save progression for " + uuid + ": " + e.getMessage());
            }
        }, DB_POOL);
    }
}
