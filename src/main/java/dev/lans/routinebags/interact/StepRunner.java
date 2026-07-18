package dev.lans.routinebags.interact;

import java.util.ArrayDeque;

import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * 限速步进器：每 tick 只执行 opsPerTick 个步骤（每步至多一次点击），
 * 避免点击包连发触发服务器限流。任何一步返回 false 即中止整条队列——
 * 这比“硬着头皮继续”安全得多，因为后续步骤的前提已经不成立了。
 */
public final class StepRunner {

    @FunctionalInterface
    public interface Step {
        boolean run();
    }

    private final ArrayDeque<Step> queue = new ArrayDeque<>();
    private @Nullable Component abortMessage;
    private @Nullable Step cancelCleanup;
    private int cooldownTicks;

    public void enqueue(Step step) {
        this.queue.addLast(step);
    }

    /** 步骤执行中动态追加的后续（如“余量放回”），插队到最前保证顺序语义 */
    public void enqueueFirst(Step step) {
        this.queue.addFirst(step);
    }

    public boolean busy() {
        return !this.queue.isEmpty();
    }

    public void clear() {
        this.queue.clear();
        this.cancelCleanup = null;
        this.cooldownTicks = 0;
    }

    public void setCancelCleanup(Step cleanup) {
        this.cancelCleanup = cleanup;
    }

    public boolean hasCancelCleanup() {
        return this.cancelCleanup != null;
    }

    public void clearCancelCleanup() {
        this.cancelCleanup = null;
    }

    public @Nullable Component takeAbortMessage() {
        Component m = this.abortMessage;
        this.abortMessage = null;
        return m;
    }

    public void abort(Component message) {
        this.queue.clear();
        this.cancelCleanup = null;
        this.cooldownTicks = 0;
        this.abortMessage = message;
    }

    public void cancelSafely(Component message) {
        this.queue.clear();
        this.cooldownTicks = 0;
        Step cleanup = this.cancelCleanup;
        if (cleanup == null) {
            this.abortMessage = message;
            return;
        }
        this.queue.addLast(retryCancelCleanup(cleanup, message));
    }

    public void abortAfter(Step cleanup, Component message) {
        this.cancelCleanup = cleanup;
        this.cancelSafely(message);
    }

    private Step retryCancelCleanup(Step cleanup, Component message) {
        return () -> {
            if (!cleanup.run()) {
                this.queue.addFirst(retryCancelCleanup(cleanup, message));
                return true;
            }
            if (this.cancelCleanup == cleanup) {
                this.cancelCleanup = null;
            }
            if (this.abortMessage == null) {
                this.abortMessage = message;
            }
            return true;
        };
    }

    public void tick(int opsPerTick, int stepDelayTicks) {
        if (this.cooldownTicks > 0) {
            this.cooldownTicks--;
            return;
        }
        for (int i = 0; i < opsPerTick && !this.queue.isEmpty(); i++) {
            Step step = this.queue.pollFirst();
            if (!step.run()) {
                Component message = this.abortMessage == null
                        ? Component.translatable("gui.routinebags.status.aborted") : this.abortMessage;
                this.cancelSafely(message);
                return;
            }
            if (stepDelayTicks > 0 && !this.queue.isEmpty()) {
                this.cooldownTicks = stepDelayTicks;
                return;
            }
        }
    }
}
