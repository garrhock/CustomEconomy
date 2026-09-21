package dev.smpeconomy.tooltip;

/**
 * Scheduling seam for {@link RefreshDebouncer}, so the debounce policy can be
 * tested without a running server.
 */
public interface RefreshScheduler {

    /** Runs {@code task} after {@code delayTicks}, returning a cancellable handle. */
    Handle runLater(Runnable task, long delayTicks);

    interface Handle {
        void cancel();
    }
}
