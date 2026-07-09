package dev.lans.routinebags.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.lans.routinebags.RoutineBags;
import dev.lans.routinebags.SortMode;
import dev.lans.routinebags.bag.BagView;
import dev.lans.routinebags.merge.ItemKey;
import dev.lans.routinebags.network.RoutineBagsNetwork;
import dev.lans.routinebags.network.RoutineBagsNetwork.SortRequestPayload;
import dev.lans.routinebags.network.RoutineBagsNetwork.SortResultPayload;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import org.apache.commons.lang3.math.Fraction;

public final class ServerSortService {
    public static void handleSortRequest(ServerPlayer player, SortRequestPayload payload) {
        SortMode mode = SortMode.byOrdinal(payload.sortModeOrdinal());
        SortResultPayload result;
        try {
            result = sortBundles(player, mode);
        } catch (RuntimeException e) {
            RoutineBags.LOGGER.warn("Server-side bundle sort failed for {}", player.getName().getString(), e);
            result = new SortResultPayload(false, 0, "gui.routinebags.status.server_sort_failed");
        }
        RoutineBagsNetwork.sendSortResult(player, result);
    }

    private static SortResultPayload sortBundles(ServerPlayer player, SortMode mode) {
        List<BagView> bundles = ServerBagScanner.scanMutableBundles(player);
        if (bundles.isEmpty()) {
            return new SortResultPayload(true, 0, "gui.routinebags.status.server_sorted");
        }
        Map<ItemKey, Long> totals = new LinkedHashMap<>();
        for (BagView bag : bundles) {
            for (ItemStack stack : bag.entries) {
                totals.merge(ItemKey.of(stack), (long) stack.getCount(), Long::sum);
            }
        }
        if (totals.isEmpty()) {
            clearBundles(player, bundles);
            return new SortResultPayload(true, 0, "gui.routinebags.status.server_sorted");
        }

        List<ItemKey> order = new ArrayList<>(totals.keySet());
        order.sort(keyComparator(mode, totals));
        List<List<ItemStack>> layout = buildLayout(bundles, order, totals);
        applyLayout(player, bundles, layout);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        return new SortResultPayload(true, totals.size(), "gui.routinebags.status.server_sorted");
    }

    private static List<List<ItemStack>> buildLayout(List<BagView> bundles, List<ItemKey> order, Map<ItemKey, Long> totals) {
        List<Integer> fillOrder = new ArrayList<>();
        for (int i = 0; i < bundles.size(); i++) {
            fillOrder.add(i);
        }
        fillOrder.sort((a, b) -> {
            int cmp = bundles.get(b).weightUsed.compareTo(bundles.get(a).weightUsed);
            return cmp != 0 ? cmp : Integer.compare(bundles.get(a).invIndex, bundles.get(b).invIndex);
        });

        List<List<ItemStack>> layout = new ArrayList<>();
        Fraction[] free = new Fraction[bundles.size()];
        for (int i = 0; i < bundles.size(); i++) {
            layout.add(new ArrayList<>());
            free[i] = Fraction.ONE;
        }
        for (ItemKey key : order) {
            long remainingLong = totals.getOrDefault(key, 0L);
            if (remainingLong <= 0) {
                continue;
            }
            int remaining = (int) Math.min(remainingLong, Integer.MAX_VALUE);
            Fraction weight = BagView.unitWeight(key.proto());
            Fraction wholeWeight = weight.multiplyBy(Fraction.getFraction(remaining, 1));
            int wholeTarget = -1;
            for (int i : fillOrder) {
                if (free[i].compareTo(wholeWeight) >= 0) {
                    wholeTarget = i;
                    break;
                }
            }
            if (wholeTarget != -1) {
                addStacks(layout.get(wholeTarget), key.proto(), remaining);
                free[wholeTarget] = free[wholeTarget].subtract(wholeWeight);
                continue;
            }
            for (int i : fillOrder) {
                if (remaining <= 0) {
                    break;
                }
                int fit = Math.max(free[i].divideBy(weight).intValue(), 0);
                int take = Math.min(fit, remaining);
                if (take > 0) {
                    addStacks(layout.get(i), key.proto(), take);
                    free[i] = free[i].subtract(weight.multiplyBy(Fraction.getFraction(take, 1)));
                    remaining -= take;
                }
            }
        }
        return layout;
    }

    private static void addStacks(List<ItemStack> out, ItemStack proto, int count) {
        int max = proto.getMaxStackSize();
        int remaining = count;
        while (remaining > 0) {
            int take = Math.min(max, remaining);
            out.add(proto.copyWithCount(take));
            remaining -= take;
        }
    }

    private static void applyLayout(ServerPlayer player, List<BagView> bundles, List<List<ItemStack>> layout) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < bundles.size(); i++) {
            BagView bag = bundles.get(i);
            ItemStack live = inv.getItem(bag.invIndex);
            if (bag.invIndex == Inventory.SLOT_OFFHAND) {
                live = inv.getItem(Inventory.SLOT_OFFHAND);
            }
            if (live.isEmpty() || live.getCount() != 1 || live.get(DataComponents.BUNDLE_CONTENTS) == null) {
                continue;
            }
            BundleContents.Mutable contents = new BundleContents.Mutable(BundleContents.EMPTY);
            for (int entry = layout.get(i).size() - 1; entry >= 0; entry--) {
                contents.tryInsert(layout.get(i).get(entry).copy());
            }
            live.set(DataComponents.BUNDLE_CONTENTS, contents.toImmutable());
            inv.setItem(bag.invIndex, live);
        }
    }

    private static void clearBundles(ServerPlayer player, List<BagView> bundles) {
        Inventory inv = player.getInventory();
        for (BagView bag : bundles) {
            ItemStack live = inv.getItem(bag.invIndex);
            if (!live.isEmpty() && live.getCount() == 1 && live.get(DataComponents.BUNDLE_CONTENTS) != null) {
                live.set(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
                inv.setItem(bag.invIndex, live);
            }
        }
        inv.setChanged();
        player.inventoryMenu.broadcastChanges();
    }

    private static Comparator<ItemKey> keyComparator(SortMode mode, Map<ItemKey, Long> totals) {
        Comparator<ItemKey> byId = Comparator.comparing(k -> k.proto().typeHolder().getRegisteredName());
        return switch (mode) {
            case BY_CREATIVE, BY_ID -> byId;
            case BY_NAME -> Comparator.<ItemKey, String>comparing(k -> k.proto().getHoverName().getString()).thenComparing(byId);
            case BY_COUNT -> Comparator.<ItemKey>comparingLong(k -> -totals.get(k)).thenComparing(byId);
        };
    }

    private ServerSortService() {}
}
