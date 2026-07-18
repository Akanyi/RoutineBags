package dev.lans.routinebags.interact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class StepRunnerTest {
    @Test
    void cancellationRetriesCleanupUntilItCompletes() {
        StepRunner runner = new StepRunner();
        AtomicInteger attempts = new AtomicInteger();
        Component message = Component.literal("cancelled");
        runner.setCancelCleanup(() -> attempts.incrementAndGet() >= 3);

        runner.cancelSafely(message);

        runner.tick(1, 0);
        assertEquals(1, attempts.get());
        assertTrue(runner.busy());
        assertTrue(runner.hasCancelCleanup());
        assertNull(runner.takeAbortMessage());

        runner.tick(1, 0);
        assertEquals(2, attempts.get());
        assertTrue(runner.busy());
        assertTrue(runner.hasCancelCleanup());
        assertNull(runner.takeAbortMessage());

        runner.tick(1, 0);
        assertEquals(3, attempts.get());
        assertFalse(runner.busy());
        assertFalse(runner.hasCancelCleanup());
        assertSame(message, runner.takeAbortMessage());
        assertNull(runner.takeAbortMessage());
    }

    @Test
    void failedStepRunsRegisteredCleanupBeforeReportingAbort() {
        StepRunner runner = new StepRunner();
        AtomicInteger attempts = new AtomicInteger();
        runner.setCancelCleanup(() -> attempts.incrementAndGet() >= 2);
        runner.enqueue(() -> false);

        runner.tick(1, 0);
        assertEquals(0, attempts.get());
        assertTrue(runner.busy());
        assertNull(runner.takeAbortMessage());

        runner.tick(1, 0);
        assertEquals(1, attempts.get());
        assertTrue(runner.busy());
        assertNull(runner.takeAbortMessage());

        runner.tick(1, 0);
        assertEquals(2, attempts.get());
        assertFalse(runner.busy());
        assertEquals("translation{key='gui.routinebags.status.aborted', args=[]}",
                runner.takeAbortMessage().toString());
    }

    @Test
    void cancellationWithMultipleOpsStillWaitsForSuccessfulCleanup() {
        StepRunner runner = new StepRunner();
        AtomicInteger attempts = new AtomicInteger();
        Component message = Component.literal("cancelled");
        runner.setCancelCleanup(() -> attempts.incrementAndGet() >= 3);

        runner.cancelSafely(message);
        runner.tick(8, 0);

        assertEquals(3, attempts.get());
        assertFalse(runner.busy());
        assertFalse(runner.hasCancelCleanup());
        assertSame(message, runner.takeAbortMessage());
    }
}
