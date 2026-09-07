package dev.smpeconomy.shards.storage;

import dev.smpeconomy.database.DatabaseManager;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * One-time carry-over of balances from the standalone Shards plugin.
 *
 * Servers upgrading from separate CustomEconomy + Shards jars still have
 * {@code plugins/Shards/shards.db} on disk. On first enable after the merge we
 * copy those rows into the shared `shards` table and drop a marker file so the
 * import never runs twice. Existing rows win — a balance already in the merged
 * database is never overwritten by the legacy file.
 */
public final class LegacyShardImport {

    private static final String MARKER = "shards-imported.flag";

    private LegacyShardImport() {
    }

    /** @return number of balances imported, or 0 when there was nothing to do. */
    public static int run(File dataFolder, DatabaseManager db, boolean mysql, Logger log) {
        File marker = new File(dataFolder, MARKER);
        if (marker.exists()) {
            return 0;
        }

        // plugins/CustomEconomy/ → plugins/Shards/shards.db
        File legacyDb = new File(dataFolder.getParentFile(), "Shards" + File.separator + "shards.db");
        if (!legacyDb.isFile()) {
            markDone(marker, log);
            return 0;
        }

        Map<String, Long> balances = read(legacyDb, log);
        if (balances == null) {
            // Read failed — leave the marker off so a fixed file still imports later.
            return 0;
        }

        int imported = write(balances, db, mysql, log);
        markDone(marker, log);
        if (imported > 0) {
            log.info("Imported " + imported + " shard balance(s) from " + legacyDb.getPath()
                    + ". The old file is left untouched and can be deleted once verified.");
        }
        return imported;
    }

    /** @return the legacy rows, or null if the file could not be read. */
    private static Map<String, Long> read(File legacyDb, Logger log) {
        Map<String, Long> balances = new LinkedHashMap<>();
        String url = "jdbc:sqlite:" + legacyDb.getAbsolutePath();
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement st = c.prepareStatement("SELECT uuid, balance FROM shards");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                balances.put(rs.getString(1), rs.getLong(2));
            }
            return balances;
        } catch (SQLException e) {
            log.warning("Could not read legacy shards.db (" + e.getMessage()
                    + "). Shard balances were NOT imported; the import will retry on next start.");
            return null;
        }
    }

    private static int write(Map<String, Long> balances, DatabaseManager db, boolean mysql, Logger log) {
        if (balances.isEmpty()) {
            return 0;
        }
        String sql = mysql
                ? "INSERT IGNORE INTO shards (uuid, balance) VALUES (?, ?)"
                : "INSERT INTO shards (uuid, balance) VALUES (?, ?) ON CONFLICT(uuid) DO NOTHING";
        int imported = 0;
        try (Connection c = db.getConnection();
             PreparedStatement st = c.prepareStatement(sql)) {
            for (Map.Entry<String, Long> e : balances.entrySet()) {
                st.setString(1, e.getKey());
                st.setLong(2, e.getValue());
                imported += st.executeUpdate();
            }
        } catch (SQLException e) {
            log.warning("Failed to import legacy shard balances: " + e.getMessage());
        }
        return imported;
    }

    private static void markDone(File marker, Logger log) {
        try {
            //noinspection ResultOfMethodCallIgnored
            marker.getParentFile().mkdirs();
            if (!marker.createNewFile() && !marker.exists()) {
                log.warning("Could not write " + MARKER + " — the shard import may run again next start.");
            }
        } catch (IOException e) {
            log.warning("Could not write " + MARKER + ": " + e.getMessage());
        }
    }
}
