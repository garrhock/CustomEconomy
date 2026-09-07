package dev.smpeconomy.hook;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

import java.util.logging.Logger;

/**
 * Wraps Vault's Economy service.
 *
 * Vault calls are synchronous and in-memory (EssentialsX stores balances in
 * its own cache), so calling deposit/withdraw from the main thread is safe.
 * From async threads, schedule a main-thread task to call these methods.
 */
public final class VaultHook {

    private final JavaPlugin plugin;
    private final Logger log;
    private Economy economy;

    public VaultHook(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
    }

    /** Returns true if Vault and an economy provider were found. */
    public boolean hook() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            log.severe("Vault plugin not found!");
            return false;
        }
        RegisteredServiceProvider<Economy> rsp =
            plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            log.severe("No Vault economy provider found (is EssentialsX installed?)");
            return false;
        }
        economy = rsp.getProvider();
        log.info("Hooked into Vault economy: " + economy.getName());
        return true;
    }

    public boolean isHooked() { return economy != null; }

    /** Deposits {@code amount} into the player's account. Must be called on main thread. */
    public boolean deposit(OfflinePlayer player, double amount) {
        if (!isHooked()) return false;
        EconomyResponse resp = economy.depositPlayer(player, amount);
        return resp.transactionSuccess();
    }

    /** Deposits into an account by UUID — works for offline players. */
    public boolean deposit(UUID uuid, double amount) {
        return deposit(plugin.getServer().getOfflinePlayer(uuid), amount);
    }

    /** Withdraws {@code amount} from the player's account. Must be called on main thread. */
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (!isHooked()) return false;
        EconomyResponse resp = economy.withdrawPlayer(player, amount);
        return resp.transactionSuccess();
    }

    public double getBalance(OfflinePlayer player) {
        return isHooked() ? economy.getBalance(player) : 0;
    }

    public boolean has(OfflinePlayer player, double amount) {
        return isHooked() && economy.has(player, amount);
    }

    public Economy getEconomy() { return economy; }
}
