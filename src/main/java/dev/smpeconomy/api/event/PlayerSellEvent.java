package dev.smpeconomy.api.event;

import dev.smpeconomy.model.SellResult;
import dev.smpeconomy.model.Transaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Fired on the main thread after items have been removed from the player's
 * inventory and before money is deposited.  Cancelling this event returns
 * all items and does NOT deposit funds.
 */
public final class PlayerSellEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final SellResult result;
    private final List<Transaction> transactions;
    private boolean cancelled = false;

    public PlayerSellEvent(Player player, SellResult result, List<Transaction> transactions) {
        this.player       = player;
        this.result       = result;
        this.transactions = transactions;
    }

    public Player getPlayer()                  { return player; }
    public SellResult getResult()              { return result; }
    public List<Transaction> getTransactions() { return transactions; }

    @Override public boolean isCancelled()           { return cancelled; }
    @Override public void setCancelled(boolean c)    { this.cancelled = c; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList()       { return HANDLERS; }
}
