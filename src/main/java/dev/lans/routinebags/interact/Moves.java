package dev.lans.routinebags.interact;

import dev.lans.routinebags.merge.ItemKey;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;

/**
 * 把“一次逻辑搬运”编译成若干个单点击步骤。
 * 每一步执行前都基于菜单实时状态做前提校验：服务器如果悄悄纠偏回滚了某次点击，
 * 校验会失败并中止整条操作，绝不会拿着过期的假设继续瞎点。
 */
public final class Moves {

    private static final int WAIT_TICKS = 20;

    /** 袋到袋搬运（src==dst 时是同袋去碎片：取出后重新塞回即自动合并同类条目） */
    public static void bundleToBundle(StepRunner r, int srcSlot, int entryIdx, ItemKey key, int dstSlot) {
        r.enqueue(() -> InvOps.carried().isEmpty()
                && entryMatches(srcSlot, entryIdx, key)
                && InvOps.selectBundleEntry(srcSlot, entryIdx));
        r.enqueue(() -> entryMatches(srcSlot, entryIdx, key)
                && InvOps.selectBundleEntry(srcSlot, entryIdx) && InvOps.rightClick(srcSlot));
        r.enqueue(waitUntil(r, () -> key.matches(InvOps.carried()), WAIT_TICKS));
        r.enqueue(() -> {
            if (!InvOps.leftClick(dstSlot)) {
                return false;
            }
            r.enqueue(() -> {
                if (!InvOps.carried().isEmpty()) {
                    r.enqueueFirst(() -> putBack(r, srcSlot));
                }
                return true;
            });
            return true;
        });
    }

    /** 从袋子取一个条目到玩家背包：优先并入同类未满堆，否则放空位 */
    public static void bundleToInventory(StepRunner r, int srcSlot, int entryIdx, ItemKey key) {
        r.enqueue(() -> InvOps.carried().isEmpty()
                && entryMatches(srcSlot, entryIdx, key)
                && InvOps.selectBundleEntry(srcSlot, entryIdx));
        r.enqueue(() -> entryMatches(srcSlot, entryIdx, key)
                && InvOps.selectBundleEntry(srcSlot, entryIdx) && InvOps.rightClick(srcSlot));
        r.enqueue(waitUntil(r, () -> key.matches(InvOps.carried()), WAIT_TICKS));
        r.enqueue(depositToInventory(r, srcSlot, key, Inventory.INVENTORY_SIZE));
    }

    /** 从一个袋内条目精确取出指定数量，其余仍放回原袋。 */
    public static void bundleAmountToInventory(StepRunner r, int srcSlot, int entryIdx, ItemKey key, int amount) {
        int[] liveEntry = {-1};
        r.enqueue(() -> {
            liveEntry[0] = matchingEntry(srcSlot, key, amount, entryIdx);
            return amount > 0 && InvOps.carried().isEmpty() && liveEntry[0] >= 0
                    && InvOps.selectBundleEntry(srcSlot, liveEntry[0]);
        });
        r.enqueue(() -> {
            if (!entryMatches(srcSlot, liveEntry[0], key)
                    || !InvOps.selectBundleEntry(srcSlot, liveEntry[0]) || !InvOps.rightClick(srcSlot)) {
                return false;
            }
            // Register recovery in the same input callback as the extraction click. A user can
            // cancel before the next tick, so waiting for cursor confirmation leaves a real gap.
            r.setCancelCleanup(() -> putBack(r, srcSlot));
            return true;
        });
        r.enqueue(waitUntil(r, () -> key.matches(InvOps.carried()), WAIT_TICKS));
        r.enqueue(depositAmountToInventory(r, srcSlot, key, amount));
    }

    /** 光标上的聚合结果放进玩家背包，优先合并同类未满堆，否则放空位。 */
    public static void carriedToInventory(StepRunner r, ItemKey key) {
        r.enqueue(() -> InvOps.carried().isEmpty() || key.matches(InvOps.carried()));
        r.enqueue(depositToInventory(r, -1, key, Inventory.INVENTORY_SIZE));
    }

    /** 把背包里的一个堆存进指定袋子，装不下的放回原位 */
    public static void inventoryToBundle(StepRunner r, int srcInvMenuSlot, ItemKey expected, int dstBagSlot) {
        r.enqueue(() -> {
            if (!InvOps.carried().isEmpty()) {
                return false;
            }
            ItemStack live = InvOps.stackAt(srcInvMenuSlot);
            return !live.isEmpty() && expected.matches(live) && InvOps.leftClick(srcInvMenuSlot);
        });
        r.enqueue(waitUntil(r, () -> expected.matches(InvOps.carried()), WAIT_TICKS));
        r.enqueue(() -> {
            if (InvOps.carried().isEmpty() || !InvOps.leftClick(dstBagSlot)) {
                return false;
            }
            if (!InvOps.carried().isEmpty()) {
                r.enqueueFirst(() -> {
                    // 原位若被外部事件占了，退而求其次找空位；实在没有就明说“在光标上”
                    if (InvOps.stackAt(srcInvMenuSlot).isEmpty()) {
                        return InvOps.leftClick(srcInvMenuSlot) && InvOps.carried().isEmpty();
                    }
                    return dumpCursorToEmptySlot(r);
                });
            }
            return true;
        });
    }

    /** 光标上的余量放回来源袋。刚取出来的东西原袋必然装得下（重量刚被腾出来） */
    private static boolean putBack(StepRunner r, int srcSlot) {
        if (InvOps.carried().isEmpty()) {
            return true;
        }
        if (InvOps.canReachSlot(srcSlot) && InvOps.stackAt(srcSlot).get(DataComponents.BUNDLE_CONTENTS) != null
                && InvOps.leftClick(srcSlot) && InvOps.carried().isEmpty()) {
            return true;
        }
        return dumpCursorToEmptySlot(r);
    }

    /**
     * 光标物品往背包放，一次点击一步，放不完自我复挂，最多 attempts 次。
     * 目标槽只挑同类未满堆或空位——绝不点在别的袋子上，那会变成“存入”。
     */
    private static StepRunner.Step depositToInventory(StepRunner r, int srcBagSlot, ItemKey key, int attempts) {
        return () -> {
            ItemStack carried = InvOps.carried();
            if (carried.isEmpty()) {
                return true;
            }
            if (attempts <= 0) {
                if (srcBagSlot >= 0) {
                    r.abortAfter(() -> putBack(r, srcBagSlot), Component.translatable("gui.routinebags.status.inv_full"));
                } else {
                    r.abort(Component.translatable("gui.routinebags.status.inv_full"));
                }
                return true;
            }
            int dst = findDepositSlot(carried);
            if (dst == -1) {
                if (srcBagSlot >= 0) {
                    r.abortAfter(() -> putBack(r, srcBagSlot), Component.translatable("gui.routinebags.status.inv_full"));
                } else {
                    r.abort(Component.translatable("gui.routinebags.status.inv_full"));
                }
                return true;
            }
            if (!InvOps.leftClick(dst)) {
                return false;
            }
            if (!InvOps.carried().isEmpty()) {
                r.enqueueFirst(depositToInventory(r, srcBagSlot, key, attempts - 1));
            }
            return true;
        };
    }

    private static StepRunner.Step depositAmountToInventory(StepRunner r, int srcBagSlot, ItemKey key, int remaining) {
        return () -> {
            ItemStack carried = InvOps.carried();
            if (remaining <= 0) {
                if (!carried.isEmpty()) {
                    r.enqueueFirst(() -> {
                        boolean returned = putBack(r, srcBagSlot);
                        if (returned) {
                            r.clearCancelCleanup();
                        }
                        return returned;
                    });
                } else {
                    r.clearCancelCleanup();
                }
                return true;
            }
            if (carried.isEmpty()) {
                r.cancelSafely(Component.translatable("gui.routinebags.status.aborted"));
                return true;
            }
            if (!key.matches(carried)) {
                r.cancelSafely(Component.translatable("gui.routinebags.status.aborted"));
                return true;
            }
            int dst = findDepositSlot(carried);
            if (dst == -1) {
                r.cancelSafely(Component.translatable("gui.routinebags.status.inv_full"));
                return true;
            }
            int before = carried.getCount();
            if (!InvOps.rightClick(dst)) {
                r.cancelSafely(Component.translatable("gui.routinebags.status.aborted"));
                return true;
            }
            r.enqueueFirst(waitUntilThenOr(r,
                    () -> InvOps.carried().isEmpty() || InvOps.carried().getCount() == before - 1,
                    WAIT_TICKS, depositAmountToInventory(r, srcBagSlot, key, remaining - 1),
                    () -> {
                        r.cancelSafely(Component.translatable("gui.routinebags.status.aborted"));
                        return true;
                    }));
            return true;
        };
    }

    private static boolean dumpCursorToEmptySlot(StepRunner r) {
        int dst = findDepositSlot(InvOps.carried());
        if (dst == -1) {
            r.abort(Component.translatable("gui.routinebags.status.leftover_on_cursor"));
            return true;
        }
        return InvOps.leftClick(dst) && InvOps.carried().isEmpty();
    }

    /** 主背包 9..35 优先于快捷栏 36..44；同类未满堆优先于空位 */
    private static int findDepositSlot(ItemStack carried) {
        int firstEmpty = -1;
        for (int slot = InventoryMenu.INV_SLOT_START; slot < InventoryMenu.USE_ROW_SLOT_END; slot++) {
            ItemStack at = InvOps.stackAt(slot);
            if (at.isEmpty()) {
                if (firstEmpty == -1) {
                    firstEmpty = slot;
                }
            } else if (ItemStack.isSameItemSameComponents(at, carried) && at.getCount() < at.getMaxStackSize()) {
                return slot;
            }
        }
        return firstEmpty;
    }

    public static boolean entryMatches(int menuSlot, int entryIdx, ItemKey key) {
        BundleContents bc = InvOps.bundleAt(menuSlot);
        if (bc == null || entryIdx < 0 || entryIdx >= bc.size()) {
            return false;
        }
        return ItemStack.isSameItemSameComponents(key.proto(), bc.items().get(entryIdx).create());
    }

    private static int matchingEntry(int menuSlot, ItemKey key, int amount, int preferredIndex) {
        BundleContents contents = InvOps.bundleAt(menuSlot);
        if (contents == null) {
            return -1;
        }
        if (preferredIndex >= 0 && preferredIndex < contents.size()) {
            ItemStack preferred = contents.items().get(preferredIndex).create();
            if (key.matches(preferred) && preferred.getCount() >= amount) {
                return preferredIndex;
            }
        }
        for (int i = 0; i < contents.size(); i++) {
            ItemStack stack = contents.items().get(i).create();
            if (key.matches(stack) && stack.getCount() >= amount) {
                return i;
            }
        }
        return -1;
    }

    static StepRunner.Step waitUntil(StepRunner r, java.util.function.BooleanSupplier condition, int ticksLeft) {
        return waitUntilThen(r, condition, ticksLeft, () -> true);
    }

    static StepRunner.Step waitUntilThen(StepRunner r, java.util.function.BooleanSupplier condition, int ticksLeft, StepRunner.Step then) {
        return () -> {
            if (condition.getAsBoolean()) {
                return then.run();
            }
            if (ticksLeft <= 0) {
                return false;
            }
            r.enqueueFirst(waitUntilThen(r, condition, ticksLeft - 1, then));
            return true;
        };
    }

    private static StepRunner.Step waitUntilThenOr(StepRunner r, java.util.function.BooleanSupplier condition,
            int ticksLeft, StepRunner.Step then, StepRunner.Step onTimeout) {
        return () -> {
            if (condition.getAsBoolean()) {
                return then.run();
            }
            if (ticksLeft <= 0) {
                return onTimeout.run();
            }
            r.enqueueFirst(waitUntilThenOr(r, condition, ticksLeft - 1, then, onTimeout));
            return true;
        };
    }

    /** Inventory 下标 → 菜单槽位（与 BagScanner 保持同一套换算） */
    public static int menuSlotForInv(int invIndex) {
        if (invIndex == Inventory.SLOT_OFFHAND) {
            return InventoryMenu.SHIELD_SLOT;
        }
        return invIndex < Inventory.SELECTION_SIZE ? InventoryMenu.USE_ROW_SLOT_START + invIndex : invIndex;
    }

    private Moves() {}
}
