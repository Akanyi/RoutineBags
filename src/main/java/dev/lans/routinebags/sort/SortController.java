package dev.lans.routinebags.sort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.math.Fraction;
import org.jspecify.annotations.Nullable;

import dev.lans.routinebags.ClientConfig;
import dev.lans.routinebags.RoutineBags;
import dev.lans.routinebags.SortMode;
import dev.lans.routinebags.bag.BagKind;
import dev.lans.routinebags.bag.BagScanner;
import dev.lans.routinebags.bag.BagView;
import dev.lans.routinebags.interact.InvOps;
import dev.lans.routinebags.interact.Moves;
import dev.lans.routinebags.interact.StepRunner;
import dev.lans.routinebags.merge.ItemKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 整理控制器：策略循环而非预编脚本。每次只从实时状态规划“下一次搬运”，
 * 执行完再看一眼世界。服务器纠偏、背包外部变化都天然被吸收，不会一步错步步错。
 *
 * 收敛性三重保险：目标布局只由物品总量决定（搬运不改变总量 → 目标恒定）；
 * 每步执行前实时校验；状态哈希查重——同一布局出现第二次说明进入循环，立即优雅收手。
 */
public final class SortController {

    /** 从 src 袋的 entryIdx 条目搬到 dst 袋；src==dst 表示同袋去碎片 */
    private record Move(BagView src, int entryIdx, ItemKey key, BagView dst) {}

    private boolean active;
    private int moves;
    private final Set<Long> seenStates = new HashSet<>();
    private @Nullable Component status;
    /** 本次整理运行定格的填充优先级（invIndex → 名次）。恒定目标是收敛的根基 */
    private @Nullable Map<Integer, Integer> fillRank;

    public void start() {
        this.active = true;
        this.moves = 0;
        this.seenStates.clear();
        this.fillRank = null;
        this.status = Component.translatable("gui.routinebags.status.sorting", 0);
    }

    public void cancel() {
        if (this.active) {
            this.active = false;
            this.status = Component.translatable("gui.routinebags.status.cancelled", this.moves);
        }
    }

    public boolean isActive() {
        return this.active;
    }

    public @Nullable Component status() {
        return this.status;
    }

    public void tick(StepRunner runner, SortMode mode) {
        if (!this.active || runner.busy()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            this.active = false;
            return;
        }
        List<BagView> bundles = BagScanner.scan(player, false).stream()
                .filter(b -> b.kind == BagKind.BUNDLE && b.mutable)
                .filter(b -> InvOps.canReachSlot(b.menuSlot))
                .toList();
        if (bundles.isEmpty()) {
            finish();
            return;
        }
        if (this.moves >= ClientConfig.MAX_SORT_STEPS.get()) {
            this.active = false;
            this.status = Component.translatable("gui.routinebags.status.step_capped", this.moves);
            return;
        }
        if (!this.seenStates.add(stateHash(bundles))) {
            finish();
            return;
        }
        Move move = plan(bundles, mode);
        if (move == null) {
            finish();
            return;
        }
        this.moves++;
        this.status = Component.translatable("gui.routinebags.status.sorting", this.moves);
        RoutineBags.LOGGER.info("[sort] move#{}: {} slot{} -> slot{}", this.moves,
                move.key().proto().typeHolder().getRegisteredName(), move.src().menuSlot, move.dst().menuSlot);
        Moves.bundleToBundle(runner, move.src().menuSlot, move.entryIdx(), move.key(), move.dst().menuSlot);
    }

    private void finish() {
        this.active = false;
        RoutineBags.LOGGER.info("[sort] finished after {} moves", this.moves);
        this.status = Component.translatable("gui.routinebags.status.sorted", this.moves);
    }

    private static long stateHash(List<BagView> bundles) {
        long h = 1;
        for (BagView bag : bundles) {
            h = h * 31 + bag.invIndex;
            for (ItemStack entry : bag.entries) {
                h = h * 31 + ItemStack.hashItemAndComponents(entry);
                h = h * 31 + entry.getCount();
            }
        }
        return h;
    }

    private @Nullable Move plan(List<BagView> bundles, SortMode mode) {
        // 总量统计——搬运不改变它，所以目标布局在整个整理过程中保持不变
        Map<ItemKey, Long> totals = new LinkedHashMap<>();
        for (BagView bag : bundles) {
            for (ItemStack entry : bag.entries) {
                totals.merge(ItemKey.of(entry), (long) entry.getCount(), Long::sum);
            }
        }
        if (totals.isEmpty()) {
            return null;
        }

        Move compact = planDirectCompaction(bundles);
        if (compact != null) {
            return compact;
        }

        List<ItemKey> order = new ArrayList<>(totals.keySet());
        order.sort(keyComparator(mode, totals));

        int n = bundles.size();
        List<Map<ItemKey, Integer>> current = new ArrayList<>(n);
        for (BagView bag : bundles) {
            Map<ItemKey, Integer> m = new HashMap<>();
            for (ItemStack entry : bag.entries) {
                m.merge(ItemKey.of(entry), entry.getCount(), Integer::sum);
            }
            current.add(m);
        }

        // 填充顺序在整理开始时定格一次，之后全程不变——目标布局恒定是收敛的根基。
        // 若按实时占用每步重排：搬运改变占用 → 排序翻转 → 目标漂移 → 规划陷入循环舞步，
        // 状态查重触发提前"完成"，表现为明明能合并却不动（实测踩过）。
        // 定格规则：占用降序，最满的袋子当聚集地，最空的自然被搬空。
        if (this.fillRank == null) {
            List<BagView> byWeight = new ArrayList<>(bundles);
            byWeight.sort((a, b) -> {
                int cmp = b.weightUsed.compareTo(a.weightUsed);
                return cmp != 0 ? cmp : Integer.compare(a.invIndex, b.invIndex);
            });
            this.fillRank = new HashMap<>();
            for (int r = 0; r < byWeight.size(); r++) {
                this.fillRank.put(byWeight.get(r).invIndex, r);
            }
            RoutineBags.LOGGER.info("[sort] fill order locked: {}",
                    byWeight.stream().map(b -> "slot" + b.menuSlot + "(" + b.usedUnits() + "u)").toList());
        }
        Integer[] fillOrder = new Integer[n];
        for (int i = 0; i < n; i++) {
            fillOrder[i] = i;
        }
        // 中途新捡的袋子没有名次，排最后
        java.util.Arrays.sort(fillOrder, Comparator.comparingInt(
                (Integer i) -> this.fillRank.getOrDefault(bundles.get(i).invIndex, Integer.MAX_VALUE)));

        List<Map<ItemKey, Integer>> target = new ArrayList<>(n);
        Fraction[] free = new Fraction[n];
        for (int i = 0; i < n; i++) {
            target.add(new HashMap<>());
            free[i] = Fraction.ONE;
        }
        for (ItemKey key : order) {
            int remaining = (int) Math.min(totals.get(key), Integer.MAX_VALUE);
            Fraction weight = BagView.unitWeight(key.proto());
            Fraction wholeWeight = weight.multiplyBy(Fraction.getFraction(remaining, 1));
            int wholeTarget = -1;
            for (int oi = 0; oi < n; oi++) {
                int i = fillOrder[oi];
                if (free[i].compareTo(wholeWeight) >= 0) {
                    wholeTarget = i;
                    break;
                }
            }
            if (wholeTarget != -1) {
                target.get(wholeTarget).merge(key, remaining, Integer::sum);
                free[wholeTarget] = free[wholeTarget].subtract(wholeWeight);
                continue;
            }
            for (int oi = 0; oi < n && remaining > 0; oi++) {
                int i = fillOrder[oi];
                int fit = Math.max(free[i].divideBy(weight).intValue(), 0);
                int take = Math.min(fit, remaining);
                if (take > 0) {
                    target.get(i).merge(key, take, Integer::sum);
                    free[i] = free[i].subtract(weight.multiplyBy(Fraction.getFraction(take, 1)));
                    remaining -= take;
                }
            }
            // remaining>0 意味着连当前袋组都装不下总量——只可能来自异常组件，放弃这部分不影响其余
        }

        // 优先跨袋归并：把超出目标的条目搬去缺口最大的袋子
        for (int i = 0; i < n; i++) {
            BagView src = bundles.get(i);
            for (int idx = 0; idx < src.entries.size(); idx++) {
                ItemKey key = ItemKey.of(src.entries.get(idx));
                int surplus = current.get(i).getOrDefault(key, 0) - target.get(i).getOrDefault(key, 0);
                if (surplus <= 0) {
                    continue;
                }
                int bestDst = -1;
                int bestDeficit = 0;
                for (int j = 0; j < n; j++) {
                    if (j == i) {
                        continue;
                    }
                    int deficit = target.get(j).getOrDefault(key, 0) - current.get(j).getOrDefault(key, 0);
                    if (deficit > bestDeficit && bundles.get(j).maxInsertable(key.proto()) > 0) {
                        bestDeficit = deficit;
                        bestDst = j;
                    }
                }
                if (bestDst != -1) {
                    return new Move(src, idx, key, bundles.get(bestDst));
                }
            }
        }

        // 再做同袋去碎片：同一种物品占了两个条目的，取出重塞让 vanilla 自动合并
        for (BagView bag : bundles) {
            Map<ItemKey, Integer> firstSeen = new HashMap<>();
            for (int idx = 0; idx < bag.entries.size(); idx++) {
                ItemStack entry = bag.entries.get(idx);
                ItemKey key = ItemKey.of(entry);
                Integer first = firstSeen.putIfAbsent(key, idx);
                // 两个条目都已满堆时合并不了（vanilla 按 maxStackSize 合并），跳过防止死循环
                if (first != null && entry.getCount() < entry.getMaxStackSize()) {
                    return new Move(bag, idx, key, bag);
                }
            }
        }
        return null;
    }

    private static @Nullable Move planDirectCompaction(List<BagView> bundles) {
        Integer[] sources = new Integer[bundles.size()];
        Integer[] destinations = new Integer[bundles.size()];
        for (int i = 0; i < bundles.size(); i++) {
            sources[i] = i;
            destinations[i] = i;
        }
        java.util.Arrays.sort(sources, Comparator
                .comparing((Integer i) -> bundles.get(i).weightUsed)
                .thenComparingInt(i -> i));
        java.util.Arrays.sort(destinations, (a, b) -> {
            int cmp = bundles.get(b).weightUsed.compareTo(bundles.get(a).weightUsed);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });

        for (int srcIdx : sources) {
            BagView src = bundles.get(srcIdx);
            if (src.entries.isEmpty()) {
                continue;
            }
            for (int dstIdx : destinations) {
                if (dstIdx == srcIdx) {
                    continue;
                }
                BagView dst = bundles.get(dstIdx);
                if (dst.entries.isEmpty()) {
                    continue;
                }
                Fraction free = Fraction.ONE.subtract(dst.weightUsed);
                if (free.compareTo(src.weightUsed) < 0) {
                    continue;
                }
                for (int entryIdx = 0; entryIdx < src.entries.size(); entryIdx++) {
                    ItemKey key = ItemKey.of(src.entries.get(entryIdx));
                    if (dst.maxInsertable(key.proto()) > 0) {
                        return new Move(src, entryIdx, key, dst);
                    }
                }
            }
        }
        return null;
    }

    private static Comparator<ItemKey> keyComparator(SortMode mode, Map<ItemKey, Long> totals) {
        Comparator<ItemKey> byId = Comparator.comparing(k -> k.proto().typeHolder().getRegisteredName());
        return switch (mode) {
            case BY_CREATIVE -> Comparator.<ItemKey>comparingInt(k -> dev.lans.routinebags.client.CreativeOrder.orderOf(k.proto())).thenComparing(byId);
            case BY_ID -> byId;
            case BY_NAME -> Comparator.<ItemKey, String>comparing(k -> k.proto().getHoverName().getString()).thenComparing(byId);
            case BY_COUNT -> Comparator.<ItemKey>comparingLong(k -> -totals.get(k)).thenComparing(byId);
        };
    }
}
