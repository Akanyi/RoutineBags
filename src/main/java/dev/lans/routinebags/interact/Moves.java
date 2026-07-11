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
        r.enqueue(() -> entryMatches(srcSlot, entryIdx, key) && InvOps.rightClick(srcSlot));
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
        r.enqueue(() -> entryMatches(srcSlot, entryIdx, key) && InvOps.rightClick(srcSlot));
        r.enqueue(waitUntil(r, () -> key.matches(InvOps.carried()), WAIT_TICKS));
        r.enqueue(depositToInventory(r, srcSlot, key, 6));
    }

    /** 光标上的聚合结果放进玩家背包，优先合并同类未满堆，否则放空位。 */
    public static void carriedToInventory(StepRunner r, ItemKey key) {
        r.enqueue(() -> InvOps.carried().isEmpty() || key.matches(InvOps.carried()));
        r.enqueue(depositToInventory(r, -1, key, 6));
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
        if (InvOps.stackAt(srcSlot).get(DataComponents.BUNDLE_CONTENTS) != null) {
            return InvOps.leftClick(srcSlot) && InvOps.carried().isEmpty();
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
                // 背包塞不下，物归原主
                if (srcBagSlot >= 0) {
                    r.enqueueFirst(() -> putBack(r, srcBagSlot));
                }
                r.abort(Component.translatable("gui.routinebags.status.inv_full"));
                return true;
            }
            int dst = findDepositSlot(carried);
            if (dst == -1) {
                if (srcBagSlot >= 0) {
                    r.enqueueFirst(() -> putBack(r, srcBagSlot));
                }
                r.abort(Component.translatable("gui.routinebags.status.inv_full"));
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

    /** Inventory 下标 → 菜单槽位（与 BagScanner 保持同一套换算） */
    public static int menuSlotForInv(int invIndex) {
        if (invIndex == Inventory.SLOT_OFFHAND) {
            return InventoryMenu.SHIELD_SLOT;
        }
        return invIndex < Inventory.SELECTION_SIZE ? InventoryMenu.USE_ROW_SLOT_START + invIndex : invIndex;
    }

    private Moves() {}
}
