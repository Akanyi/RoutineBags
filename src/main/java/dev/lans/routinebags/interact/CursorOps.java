package dev.lans.routinebags.interact;

import java.util.List;

import dev.lans.routinebags.bag.BagKind;
import dev.lans.routinebags.bag.BagScanner;
import dev.lans.routinebags.bag.BagView;
import dev.lans.routinebags.merge.ItemKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import org.jspecify.annotations.Nullable;

/**
 * AE 终端式的光标交互：点聚合条目直接拿到鼠标上，数量不足一组时跨袋自动凑。
 *
 * 凑组分两级：先把物品聚合到“可达数量最大”的袋子（可达 = 现有 + 还塞得进的量，
 * 光看现有数量会选中被杂物卡死容量的袋子，凑出 59/64 这种半吊子）；
 * 取出后仍不满一组且袋里还有货，就借一个空背包槽当中转堆，再聚再取、
 * 原版点击自动合并进槽，凑满后一次拿起——把“手动拿两遍”自动化掉。
 * 取半组最后借同一个空槽用原版“右键拿一半”切分。
 */
public final class CursorOps {

    /** 自复挂轮数上限：每轮至少一次点击，48 轮 ≈ 1.2 秒，防异常状态无限循环 */
    private static final int MAX_ROUNDS = 48;

    /** 拿一组（half=false）或半组（half=true）到光标，跨袋自动凑齐 */
    public static void pickupToCursor(StepRunner r, ItemKey key, boolean half) {
        // staging[0]=中转槽，gather[0]=最近一次取出的聚集袋（分堆时的回塞目标）
        int[] staging = {-1};
        int[] gather = {-1};
        r.enqueue(collectStep(r, key, half, staging, gather, MAX_ROUNDS, false));
    }

    /** Shift 拉取：先按聚合条目跨袋凑一组，再把这一组放进玩家背包。 */
    public static void pickupToInventory(StepRunner r, ItemKey key) {
        int[] staging = {-1};
        int[] gather = {-1};
        r.enqueue(collectStep(r, key, false, staging, gather, MAX_ROUNDS, true));
    }

    /**
     * 收集主循环：聚合 → 取出 → 不够则存中转槽再来。
     * 队列所有权独占：每步只在队列跑空时追加后续，尾部追加即保序。
     */
    private static StepRunner.Step collectStep(StepRunner r, ItemKey key, boolean half, int[] staging, int[] gather, int rounds, boolean finishToInventory) {
        return () -> {
            if (!InvOps.carried().isEmpty()) {
                return false;
            }
            int staged = stagedCount(staging, key);
            if (staged < 0) {
                return false; // 中转槽被外部动过，内容对不上，立即收手
            }
            int max = key.proto().getMaxStackSize();
            List<BagView> bags = mutableBagsWith(key);
            int total = 0;
            for (BagView bag : bags) {
                total += countOf(bag, key);
            }
            int need = Math.min(total + staged, max) - staged;
            if (need <= 0 || bags.isEmpty() || rounds <= 0) {
                enqueueFinish(r, key, half, staging, gather, finishToInventory);
                return true;
            }
            // 聚集袋按“可达数量”选：现有 + 还能塞进去的，平手选现有多的（搬运少）
            BagView best = null;
            int bestReach = 0;
            int bestHave = -1;
            for (BagView bag : bags) {
                int have = countOf(bag, key);
                int reach = Math.min(need, have + bag.maxInsertable(key.proto()));
                if (reach > bestReach || (reach == bestReach && have > bestHave)) {
                    bestReach = reach;
                    bestHave = have;
                    best = bag;
                }
            }
            if (best == null || bestReach <= 0) {
                enqueueFinish(r, key, half, staging, gather, finishToInventory);
                return true;
            }
            int bestCount = countOf(best, key);
            // 还没聚够且有其他来源：搬一轮。来源挑存量最小的袋——顺手把小袋清空
            if (bestCount < bestReach) {
                BagView src = smallestOtherSource(bags, best, key);
                if (src != null && best.maxInsertable(key.proto()) > 0) {
                    Moves.bundleToBundle(r, src.menuSlot, firstEntryIdx(src, key), key, best.menuSlot);
                    r.enqueue(collectStep(r, key, half, staging, gather, rounds - 1, finishToInventory));
                    return true;
                }
            }
            // 同袋碎片：第一条目不够数就先并条目，否则右键只取得到第一条的量
            int firstIdx = firstEntryIdx(best, key);
            int firstCount = firstIdx >= 0 ? best.entries.get(firstIdx).getCount() : 0;
            if (firstCount < Math.min(bestCount, need) && nextEntryIdx(best, key, firstIdx) != -1) {
                Moves.bundleToBundle(r, best.menuSlot, nextEntryIdx(best, key, firstIdx), key, best.menuSlot);
                r.enqueue(collectStep(r, key, half, staging, gather, rounds - 1, finishToInventory));
                return true;
            }
            // 取出：选中 → 右键上光标 → 决定“到手/进中转槽再凑”
            gather[0] = best.menuSlot;
            int bagSlot = best.menuSlot;
            r.enqueue(() -> {
                int idx = liveEntryIdx(bagSlot, key);
                return idx >= 0 && InvOps.selectBundleEntry(bagSlot, idx);
            });
            r.enqueue(() -> liveEntryIdx(bagSlot, key) >= 0 && InvOps.rightClick(bagSlot));
            r.enqueue(Moves.waitUntil(r, () -> key.matches(InvOps.carried()), MAX_ROUNDS));
            r.enqueue(afterExtract(r, key, half, staging, gather, rounds - 1, finishToInventory));
            return true;
        };
    }

    /** 取出到光标之后的分流：够数（或没货了/没空槽）就收尾，否则进中转槽继续凑 */
    private static StepRunner.Step afterExtract(StepRunner r, ItemKey key, boolean half, int[] staging, int[] gather, int rounds, boolean finishToInventory) {
        return () -> {
            ItemStack carried = InvOps.carried();
            if (carried.isEmpty() || !key.matches(carried)) {
                return false;
            }
            int max = key.proto().getMaxStackSize();
            int staged = stagedCount(staging, key);
            if (staged < 0) {
                return false;
            }
            boolean moreInBags = !mutableBagsWith(key).isEmpty();
            if (staged == 0) {
                if (carried.getCount() >= max || !moreInBags || rounds <= 0) {
                    // 光标即结果：满组、或袋里没货了——半组流程接管，否则直接到手
                    enqueueSplitIfHalf(r, key, half, gather, finishToInventory);
                    return true;
                }
                staging[0] = findEmptySlot();
                if (staging[0] == -1) {
                    r.abort(Component.translatable("gui.routinebags.status.no_empty_for_topup"));
                    return true; // 尽力交付：现有数量留在光标上
                }
            }
            // 存进中转槽（同类自动合并）；槽到 max 装不下的余量塞回聚集袋（刚腾出的空间必然够）
            if (!InvOps.leftClick(staging[0])) {
                return false;
            }
            if (!InvOps.carried().isEmpty() && gather[0] != -1 && !InvOps.leftClick(gather[0])) {
                return false;
            }
            r.enqueue(collectStep(r, key, half, staging, gather, rounds, finishToInventory));
            return true;
        };
    }

    /** 收集结束：中转槽里的堆一次拿起上光标，然后按需切半 */
    private static void enqueueFinish(StepRunner r, ItemKey key, boolean half, int[] staging, int[] gather, boolean finishToInventory) {
        r.enqueue(() -> {
            if (staging[0] != -1 && stagedCount(staging, key) > 0) {
                if (!InvOps.leftClick(staging[0])) {
                    return false;
                }
                staging[0] = -1;
            }
            enqueueSplitIfHalf(r, key, half, gather, finishToInventory);
            return true;
        });
    }

    /**
     * 切半组：光标全量 → 空槽放下 → 右键拿走大半 → 大半塞回袋 → 拿走剩下的小半。
     * 回塞目标优先聚集袋（刚腾出等量空间必然装得下），没有就找最能装的袋。
     */
    private static void enqueueSplitIfHalf(StepRunner r, ItemKey key, boolean half, int[] gather, boolean finishToInventory) {
        if (!half) {
            if (finishToInventory) {
                Moves.carriedToInventory(r, key);
            }
            return;
        }
        int[] slot = {-1};
        r.enqueue(() -> {
            ItemStack carried = InvOps.carried();
            if (carried.isEmpty() || carried.getCount() <= 1) {
                return true; // 1 个没法切也不必切
            }
            slot[0] = findEmptySlot();
            if (slot[0] == -1) {
                r.abort(Component.translatable("gui.routinebags.status.no_empty_for_half"));
                return true;
            }
            return InvOps.leftClick(slot[0]);
        });
        r.enqueue(Moves.waitUntil(r, () -> InvOps.carried().isEmpty(), MAX_ROUNDS));
        r.enqueue(() -> slot[0] == -1 || InvOps.rightClick(slot[0]));
        r.enqueue(() -> {
            if (slot[0] == -1) {
                return true;
            }
            // 大半塞回：聚集袋优先，失效则找最能装的
            int dst = gather[0];
            ItemStack carried = InvOps.carried();
            if (dst == -1 || InvOps.bundleAt(dst) == null) {
                BagView best = bestFitBag(carried);
                if (best == null) {
                    r.abort(Component.translatable("gui.routinebags.status.partial_insert"));
                    return true;
                }
                dst = best.menuSlot;
            }
            return InvOps.leftClick(dst);
        });
        r.enqueue(Moves.waitUntil(r, () -> InvOps.carried().isEmpty(), MAX_ROUNDS));
        r.enqueue(() -> slot[0] == -1 || InvOps.leftClick(slot[0]));
    }

    /** 光标物品跨袋全存：一个袋装不下就自动找下一个，直到光标空或所有袋都满 */
    public static void storeAllFromCursor(StepRunner r) {
        r.enqueue(storeAllStep(r, MAX_ROUNDS));
    }

    private static StepRunner.Step storeAllStep(StepRunner r, int rounds) {
        return () -> {
            ItemStack carried = InvOps.carried();
            if (carried.isEmpty()) {
                return true;
            }
            if (!BundleContents.canItemBeInBundle(carried) || rounds <= 0) {
                r.abort(Component.translatable("gui.routinebags.status.partial_insert"));
                return true;
            }
            BagView best = bestFitBag(carried);
            if (best == null) {
                r.abort(Component.translatable("gui.routinebags.status.partial_insert"));
                return true;
            }
            if (!InvOps.leftClick(best.menuSlot)) {
                return false;
            }
            if (!InvOps.carried().isEmpty()) {
                r.enqueueFirst(storeAllStep(r, rounds - 1));
            }
            return true;
        };
    }

    /**
     * 从光标只存 1 个进袋（AE 右键语义）。原版没有“往袋放一个”的点击，
     * 借两个空槽把 1 个精确切出来：右键放 1 到 S → 剩余放 T → 拿 S 的 1 → 入袋 → 取回 T。
     */
    public static void storeOneFromCursor(StepRunner r) {
        int[] s = {-1};
        int[] t = {-1};
        int[] bagSlot = {-1};
        r.enqueue(() -> {
            ItemStack carried = InvOps.carried();
            if (carried.isEmpty() || !BundleContents.canItemBeInBundle(carried)) {
                return false;
            }
            BagView best = bestFitBag(carried);
            if (best == null) {
                r.abort(Component.translatable("gui.routinebags.status.bags_full_generic"));
                return true;
            }
            bagSlot[0] = best.menuSlot;
            if (carried.getCount() == 1) {
                // 光标只有 1 个，直接整堆塞入，不用切
                return InvOps.leftClick(bagSlot[0]);
            }
            s[0] = findEmptySlot();
            return s[0] != -1 && InvOps.rightClick(s[0]);
        });
        r.enqueue(() -> {
            if (bagSlot[0] == -1 || s[0] == -1) {
                return true;
            }
            t[0] = findEmptySlot();
            if (t[0] == -1) {
                // 切了一半发现没有第二个空槽：把 1 个拿回来合并，等于什么都没发生
                r.abort(Component.translatable("gui.routinebags.status.no_empty_for_half"));
                r.enqueueFirst(() -> InvOps.leftClick(s[0]));
                return true;
            }
            return InvOps.leftClick(t[0]);
        });
        r.enqueue(Moves.waitUntil(r, () -> InvOps.carried().isEmpty(), MAX_ROUNDS));
        r.enqueue(() -> s[0] == -1 || t[0] == -1 || InvOps.leftClick(s[0]));
        r.enqueue(() -> s[0] == -1 || t[0] == -1 || InvOps.leftClick(bagSlot[0]));
        r.enqueue(Moves.waitUntil(r, () -> s[0] == -1 || t[0] == -1 || InvOps.carried().isEmpty(), MAX_ROUNDS));
        r.enqueue(() -> s[0] == -1 || t[0] == -1 || InvOps.leftClick(t[0]));
    }

    // ---------------------------------------------------------------- helpers

    /** 中转槽当前堆数。槽被外部改成别的东西返回 -1（触发中止） */
    private static int stagedCount(int[] staging, ItemKey key) {
        if (staging[0] == -1) {
            return 0;
        }
        ItemStack at = InvOps.stackAt(staging[0]);
        if (at.isEmpty()) {
            return 0;
        }
        return key.matches(at) ? at.getCount() : -1;
    }

    private static @Nullable BagView bestFitBag(ItemStack stack) {
        BagView best = null;
        int bestFit = 0;
        for (BagView bag : mutableBags()) {
            int fit = bag.maxInsertable(stack);
            if (fit > bestFit) {
                bestFit = fit;
                best = bag;
            }
        }
        return best;
    }

    private static List<BagView> mutableBags() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return List.of();
        }
        return BagScanner.scan(player, false).stream()
                .filter(b -> b.kind == BagKind.BUNDLE && b.mutable)
                .filter(b -> InvOps.canReachSlot(b.menuSlot))
                .toList();
    }

    private static List<BagView> mutableBagsWith(ItemKey key) {
        return mutableBags().stream().filter(b -> countOf(b, key) > 0).toList();
    }

    private static int countOf(BagView bag, ItemKey key) {
        int n = 0;
        for (ItemStack entry : bag.entries) {
            if (key.matches(entry)) {
                n += entry.getCount();
            }
        }
        return n;
    }

    private static @Nullable BagView smallestOtherSource(List<BagView> bags, BagView best, ItemKey key) {
        BagView src = null;
        int srcCount = Integer.MAX_VALUE;
        for (BagView bag : bags) {
            if (bag.menuSlot == best.menuSlot) {
                continue;
            }
            int count = countOf(bag, key);
            if (count > 0 && count < srcCount) {
                srcCount = count;
                src = bag;
            }
        }
        return src;
    }

    private static int firstEntryIdx(BagView bag, ItemKey key) {
        for (int i = 0; i < bag.entries.size(); i++) {
            if (key.matches(bag.entries.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int nextEntryIdx(BagView bag, ItemKey key, int skipIdx) {
        for (int i = 0; i < bag.entries.size(); i++) {
            if (i != skipIdx && key.matches(bag.entries.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int liveEntryIdx(int menuSlot, ItemKey key) {
        BundleContents bc = InvOps.bundleAt(menuSlot);
        if (bc == null) {
            return -1;
        }
        for (int i = 0; i < bc.size(); i++) {
            if (ItemStack.isSameItemSameComponents(key.proto(), bc.items().get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 纯空槽（不能借用同类未满堆——中转与切分都要求数量精确可控） */
    private static int findEmptySlot() {
        for (int slot = InventoryMenu.INV_SLOT_START; slot < InventoryMenu.USE_ROW_SLOT_END; slot++) {
            if (InvOps.stackAt(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private CursorOps() {}
}
