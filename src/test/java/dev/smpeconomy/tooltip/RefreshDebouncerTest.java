package dev.smpeconomy.tooltip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshDebouncerTest {

    /** Records scheduled work so a test can run or cancel it by hand. */
    private static final class FakeScheduler implements RefreshScheduler {
        final List<Task> tasks = new ArrayList<>();

        final class Task implements Handle {
            final Runnable body;
            final long delay;
            boolean cancelled;

            Task(Runnable body, long delay) { this.body = body; this.delay = delay; }

            @Override public void cancel() { cancelled = true; }
            void fire() { if (!cancelled) body.run(); }
        }

        @Override public Handle runLater(Runnable task, long delayTicks) {
            Task t = new Task(task, delayTicks);
            tasks.add(t);
            return t;
        }

        /** Fires every task that has not been cancelled, oldest first. */
        int fireAll() {
            int ran = 0;
            for (Task t : List.copyOf(tasks)) {
                if (t.cancelled) continue;
                t.fire();
                ran++;
            }
            return ran;
        }

        long liveCount() { return tasks.stream().filter(t -> !t.cancelled).count(); }
    }

    private FakeScheduler scheduler;
    private RefreshDebouncer debouncer;
    private UUID alice;
    private UUID bob;
    private int refreshes;

    @BeforeEach
    void setUp() {
        scheduler = new FakeScheduler();
        debouncer = new RefreshDebouncer(scheduler);
        alice     = UUID.randomUUID();
        bob       = UUID.randomUUID();
        refreshes = 0;
    }

    private void interact(UUID player) {
        debouncer.schedule(player, 20L, () -> refreshes++);
    }

    @Test
    void oneInteractionSchedulesOneRefresh() {
        interact(alice);
        assertTrue(debouncer.isPending(alice));
        assertEquals(1, scheduler.fireAll());
        assertEquals(1, refreshes);
    }

    @Test
    void aBurstOfInteractionsCollapsesToASingleRefresh() {
        // Dragging fires a click per slot; each must not cost a container resend.
        for (int i = 0; i < 10; i++) interact(alice);
        assertEquals(1, scheduler.liveCount());
        scheduler.fireAll();
        assertEquals(1, refreshes);
    }

    @Test
    void theTimerRestartsFromTheLatestInteraction() {
        interact(alice);
        FakeScheduler.Task first = scheduler.tasks.get(0);
        interact(alice);
        assertTrue(first.cancelled, "the earlier refresh must be dropped, not left to fire mid-drag");
        assertEquals(1, scheduler.liveCount());
    }

    @Test
    void refreshUsesTheRequestedDelay() {
        interact(alice);
        assertEquals(20L, scheduler.tasks.get(0).delay);
    }

    @Test
    void playersDebounceIndependently() {
        interact(alice);
        interact(bob);
        assertEquals(2, scheduler.liveCount());
        scheduler.fireAll();
        assertEquals(2, refreshes);
    }

    @Test
    void firingClearsPendingSoTheNextInteractionSchedulesAfresh() {
        interact(alice);
        scheduler.fireAll();
        assertFalse(debouncer.isPending(alice));

        interact(alice);
        assertTrue(debouncer.isPending(alice));
        assertEquals(2, scheduler.tasks.size());
    }

    @Test
    void cancelDropsAPendingRefresh() {
        // A player who quits must not have a refresh fire against them.
        interact(alice);
        debouncer.cancel(alice);
        assertFalse(debouncer.isPending(alice));
        assertEquals(0, scheduler.fireAll());
        assertEquals(0, refreshes);
    }

    @Test
    void cancellingAnUnknownPlayerIsHarmless() {
        debouncer.cancel(alice);
        assertEquals(0, debouncer.pendingCount());
    }

    @Test
    void pendingCountTracksDistinctPlayers() {
        interact(alice);
        interact(alice);
        interact(bob);
        assertEquals(2, debouncer.pendingCount());
    }
}
