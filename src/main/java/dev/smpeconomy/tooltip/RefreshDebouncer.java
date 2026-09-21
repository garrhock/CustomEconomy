package dev.smpeconomy.tooltip;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collapses a burst of inventory interactions into a single delayed refresh,
 * timed from the *last* interaction.
 *
 * Resending a container bumps its state id, which makes the client throw away
 * the predictions it has in flight — that is what breaks dragging a stack onto
 * another stack. Waiting until the player has stopped touching the inventory
 * keeps the resend clear of anything the client is still predicting.
 */
public final class RefreshDebouncer {

    private final RefreshScheduler scheduler;
    private final Map<UUID, RefreshScheduler.Handle> pending = new ConcurrentHashMap<>();

    public RefreshDebouncer(RefreshScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** Replaces any refresh already queued for this player. */
    public void schedule(UUID player, long delayTicks, Runnable refresh) {
        cancel(player);
        RefreshScheduler.Handle handle = scheduler.runLater(() -> {
            pending.remove(player);
            refresh.run();
        }, delayTicks);
        pending.put(player, handle);
    }

    public void cancel(UUID player) {
        RefreshScheduler.Handle existing = pending.remove(player);
        if (existing != null) existing.cancel();
    }

    public void cancelAll() {
        for (UUID player : Map.copyOf(pending).keySet()) {
            cancel(player);
        }
    }

    public boolean isPending(UUID player) {
        return pending.containsKey(player);
    }

    public int pendingCount() {
        return pending.size();
    }
}
