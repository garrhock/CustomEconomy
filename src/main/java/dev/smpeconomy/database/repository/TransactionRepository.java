package dev.smpeconomy.database.repository;

import dev.smpeconomy.database.DatabaseManager;
import dev.smpeconomy.model.Transaction;
import dev.smpeconomy.model.Transaction.Source;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

/**
 * Async transaction logger with internal write queue.
 *
 * Transactions are queued and flushed on a fixed interval rather than
 * written immediately, preventing DB write pressure on large farms.
 * The queue is drained on plugin shutdown to avoid data loss.
 */
public final class TransactionRepository {

    private static final int BATCH_SIZE = 500;

    private static final Executor DB_POOL =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CustomEco-DB-Tx");
            t.setDaemon(true);
            return t;
        });

    private final DatabaseManager db;
    private final Logger log;
    private final LinkedBlockingQueue<Transaction> queue = new LinkedBlockingQueue<>(50_000);

    public TransactionRepository(DatabaseManager db) {
        this.db  = db;
        this.log = Logger.getLogger("CustomEconomy");
    }

    /**
     * Enqueues a transaction for batch writing.
     * This is intentionally non-blocking — if the queue is full the transaction
     * is silently dropped rather than blocking a server thread.
     */
    public void enqueue(Transaction tx) {
        if (!queue.offer(tx)) {
            log.warning("Transaction queue full — transaction dropped (uuid=" + tx.playerUuid() + ")");
        }
    }

    /**
     * Drains the queue and writes all pending transactions.
     * Call periodically from an async scheduler, and once on shutdown.
     */
    public CompletableFuture<Void> flush() {
        if (queue.isEmpty()) return CompletableFuture.completedFuture(null);

        List<Transaction> batch = new ArrayList<>(Math.min(queue.size(), BATCH_SIZE));
        queue.drainTo(batch, BATCH_SIZE);
        if (batch.isEmpty()) return CompletableFuture.completedFuture(null);

        return CompletableFuture.runAsync(() -> writeBatch(batch), DB_POOL);
    }

    /** Blocking drain — use only on plugin shutdown. */
    public void flushSync() {
        List<Transaction> all = new ArrayList<>(queue.size());
        queue.drainTo(all);
        if (!all.isEmpty()) writeBatch(all);
    }

    // ── Leaderboard ───────────────────────────────────────────────────────────

    /** Leaderboard sort dimension. The ORDER BY column is chosen by switch — never interpolated. */
    public enum SortBy { QUANTITY, MONEY }

    /**
     * Returns the top items across the given sources, optionally filtered to
     * transactions after {@code since} (null = all time), ordered by either total
     * quantity or total money, capped at {@code limit}.
     */
    public List<dev.smpeconomy.model.TopItem> getTopItems(
            List<Source> sources, java.time.Instant since, SortBy sortBy, int limit) {

        if (sources.isEmpty()) return List.of();

        // Injection-safe: the order column is a literal chosen by switch, not user input.
        String orderColumn = switch (sortBy) {
            case MONEY    -> "total_value";
            case QUANTITY -> "total_qty";
        };

        StringBuilder sql = new StringBuilder(
            "SELECT item_key, SUM(quantity) AS total_qty, SUM(ABS(total_earned)) AS total_value " +
            "FROM transactions WHERE source IN (");
        for (int i = 0; i < sources.size(); i++) sql.append(i > 0 ? ",?" : "?");
        sql.append(")");
        if (since != null) sql.append(" AND created_at > ?");
        sql.append(" GROUP BY item_key ORDER BY ").append(orderColumn).append(" DESC LIMIT ?");

        List<dev.smpeconomy.model.TopItem> result = new ArrayList<>();
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Source s : sources) ps.setString(idx++, s.name());
            if (since != null) ps.setTimestamp(idx++, Timestamp.from(since));
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new dev.smpeconomy.model.TopItem(
                        rs.getString("item_key"),
                        rs.getLong("total_qty"),
                        rs.getDouble("total_value")
                    ));
                }
            }
        } catch (SQLException e) {
            log.severe("Failed to read top items: " + e.getMessage());
        }
        return result;
    }

    // ── History reads ─────────────────────────────────────────────────────────

    private static final int HISTORY_PAGE_SIZE = 36;

    /**
     * Returns a page of transactions for {@code playerUuid} matching any of the
     * given sources, newest first.  Blocking — call off the main thread.
     */
    public List<Transaction> getHistory(UUID playerUuid, List<Source> sources, int page) {
        if (sources.isEmpty()) return List.of();

        String placeholders = "?,".repeat(sources.size());
        placeholders = placeholders.substring(0, placeholders.length() - 1);
        String sql = "SELECT * FROM transactions WHERE uuid = ? AND source IN ("
                     + placeholders + ") ORDER BY created_at DESC LIMIT ? OFFSET ?";

        List<Transaction> result = new ArrayList<>();
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            int idx = 2;
            for (Source s : sources) ps.setString(idx++, s.name());
            ps.setInt(idx++, HISTORY_PAGE_SIZE);
            ps.setInt(idx,   page * HISTORY_PAGE_SIZE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp created = rs.getTimestamp("created_at");
                    result.add(new Transaction(
                        rs.getLong("id"),
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("item_key"),
                        rs.getInt("quantity"),
                        rs.getDouble("price_per_unit"),
                        rs.getDouble("multiplier"),
                        rs.getDouble("total_earned"),
                        Source.valueOf(rs.getString("source")),
                        created != null ? created.toInstant() : Instant.now()
                    ));
                }
            }
        } catch (SQLException e) {
            log.severe("Failed to read transaction history: " + e.getMessage());
        }
        return result;
    }

    /** Returns the total row count for pagination — same filters as getHistory(). */
    public int countHistory(UUID playerUuid, List<Source> sources) {
        if (sources.isEmpty()) return 0;

        String placeholders = "?,".repeat(sources.size());
        placeholders = placeholders.substring(0, placeholders.length() - 1);
        String sql = "SELECT COUNT(*) FROM transactions WHERE uuid = ? AND source IN (" + placeholders + ")";

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            int idx = 2;
            for (Source s : sources) ps.setString(idx++, s.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.severe("Failed to count transaction history: " + e.getMessage());
            return 0;
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void writeBatch(List<Transaction> batch) {
        if (batch.isEmpty()) return;

        String sql = """
            INSERT INTO transactions (uuid, item_key, quantity, price_per_unit,
                                      multiplier, total_earned, source, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection con = db.getConnection()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                for (Transaction tx : batch) {
                    ps.setString(1, tx.playerUuid().toString());
                    ps.setString(2, tx.itemKey());
                    ps.setInt(3, tx.quantity());
                    ps.setDouble(4, tx.pricePerUnit());
                    ps.setDouble(5, tx.multiplier());
                    ps.setDouble(6, tx.totalEarned());
                    ps.setString(7, tx.source().name());
                    ps.setTimestamp(8, Timestamp.from(tx.createdAt() != null ? tx.createdAt() : Instant.now()));
                    ps.addBatch();
                }
                ps.executeBatch();
                con.commit();
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.severe("Failed to write transaction batch (" + batch.size() + " records): " + e.getMessage());
        }
    }
}
