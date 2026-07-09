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
        this.cooldownTicks = 0;
    }

    public @Nullable Component takeAbortMessage() {
        Component m = this.abortMessage;
        this.abortMessage = null;
        return m;
    }

    public void abort(Component message) {
        this.queue.clear();
        this.cooldownTicks = 0;
        this.abortMessage = message;
    }

    public void tick(int opsPerTick, int stepDelayTicks) {
        if (this.cooldownTicks > 0) {
            this.cooldownTicks--;
            return;
        }
        for (int i = 0; i < opsPerTick && !this.queue.isEmpty(); i++) {
            Step step = this.queue.pollFirst();
            if (!step.run()) {
                this.queue.clear();
                if (this.abortMessage == null) {
                    this.abortMessage = Component.translatable("gui.routinebags.status.aborted");
                }
                return;
            }
            if (stepDelayTicks > 0 && !this.queue.isEmpty()) {
                this.cooldownTicks = stepDelayTicks;
                return;
            }
        }
    }
}
