package dev.lans.routinebagkkit;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

final class SortService {
    private static final int SORT_BY_CREATIVE = 0;
    private static final int SORT_BY_ID = 1;
    private static final int SORT_BY_NAME = 2;
    private static final int SORT_BY_COUNT = 3;

    int sort(Player player, int sortMode) {
        PlayerInventory inv = player.getInventory();
        List<Bag> bags = scan(inv);
        if (bags.isEmpty()) {
            return 0;
        }
        Map<ItemKey, Integer> totals = new LinkedHashMap<>();
        for (Bag bag : bags) {
            for (ItemStack stack : bag.contents) {
                if (isEmpty(stack)) {
                    continue;
                }
                totals.merge(ItemKey.of(stack), stack.getAmount(), Integer::sum);
            }
        }
        List<ItemKey> order = new ArrayList<>(totals.keySet());
        order.sort(keyComparator(sortMode, totals));
        List<List<ItemStack>> layout = buildLayout(bags, order, totals);
        applyLayout(inv, bags, layout);
        player.updateInventory();
        return totals.size();
    }

    private static List<Bag> scan(PlayerInventory inv) {
        List<Bag> out = new ArrayList<>();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack == null || stack.isEmpty() || stack.getAmount() != 1) {
                continue;
            }
            BundleContents contents = stack.getData(DataComponentTypes.BUNDLE_CONTENTS);
            if (contents != null) {
                List<ItemStack> copy = contents.contents().stream().map(ItemStack::clone).toList();
                out.add(new Bag(slot, stack.clone(), copy, usedWeight(copy)));
            }
        }
        return out;
    }

    private static List<List<ItemStack>> buildLayout(List<Bag> bags, List<ItemKey> order, Map<ItemKey, Integer> totals) {
        List<Integer> fillOrder = new ArrayList<>();
        for (int i = 0; i < bags.size(); i++) {
            fillOrder.add(i);
        }
        fillOrder.sort((a, b) -> {
            int cmp = Integer.compare(bags.get(b).usedUnits, bags.get(a).usedUnits);
            return cmp != 0 ? cmp : Integer.compare(bags.get(a).slot, bags.get(b).slot);
        });

        List<List<ItemStack>> layout = new ArrayList<>();
        int[] free = new int[bags.size()];
        for (int i = 0; i < bags.size(); i++) {
            layout.add(new ArrayList<>());
            free[i] = 64;
        }
        for (ItemKey key : order) {
            int remaining = totals.getOrDefault(key, 0);
            if (remaining <= 0) {
                continue;
            }
            int unit = unitWeight(key.proto);
            int wholeWeight = remaining * unit;
            int wholeTarget = -1;
            for (int i : fillOrder) {
                if (free[i] >= wholeWeight) {
                    wholeTarget = i;
                    break;
                }
            }
            if (wholeTarget != -1) {
                addStacks(layout.get(wholeTarget), key.proto, remaining);
                free[wholeTarget] -= wholeWeight;
                continue;
            }
            for (int i : fillOrder) {
                if (remaining <= 0) {
                    break;
                }
                int fit = free[i] / unit;
                int take = Math.min(fit, remaining);
                if (take > 0) {
                    addStacks(layout.get(i), key.proto, take);
                    free[i] -= take * unit;
                    remaining -= take;
                }
            }
        }
        return layout;
    }

    private static void applyLayout(PlayerInventory inv, List<Bag> bags, List<List<ItemStack>> layout) {
        for (int i = 0; i < bags.size(); i++) {
            Bag bag = bags.get(i);
            ItemStack live = inv.getItem(bag.slot);
            if (live == null || live.isEmpty() || live.getAmount() != 1 || live.getData(DataComponentTypes.BUNDLE_CONTENTS) == null) {
                continue;
            }
            live.setData(DataComponentTypes.BUNDLE_CONTENTS, BundleContents.bundleContents(layout.get(i)));
            inv.setItem(bag.slot, live);
        }
    }

    private static void addStacks(List<ItemStack> out, ItemStack proto, int count) {
        int max = Math.max(1, proto.getMaxStackSize());
        int remaining = count;
        while (remaining > 0) {
            int take = Math.min(max, remaining);
            ItemStack stack = proto.clone();
            stack.setAmount(take);
            out.add(stack);
            remaining -= take;
        }
    }

    private static Comparator<ItemKey> keyComparator(int sortMode, Map<ItemKey, Integer> totals) {
        Comparator<ItemKey> byId = Comparator.comparing(key -> key.materialKey);
        return switch (sortMode) {
            case SORT_BY_NAME -> Comparator.<ItemKey, String>comparing(key -> key.displayName).thenComparing(byId);
            case SORT_BY_COUNT -> Comparator.<ItemKey>comparingInt(key -> -totals.getOrDefault(key, 0)).thenComparing(byId);
            case SORT_BY_CREATIVE, SORT_BY_ID -> byId;
            default -> byId;
        };
    }

    private static int usedWeight(List<ItemStack> contents) {
        int total = 0;
        for (ItemStack stack : contents) {
            if (!isEmpty(stack)) {
                total += unitWeight(stack) * stack.getAmount();
            }
        }
        return Math.min(total, 64);
    }

    private static int unitWeight(ItemStack stack) {
        return Math.max(1, 64 / Math.max(1, stack.getMaxStackSize()));
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty() || stack.getAmount() <= 0;
    }

    private record Bag(int slot, ItemStack stack, List<ItemStack> contents, int usedUnits) {}

    private static final class ItemKey {
        private final ItemStack proto;
        private final String materialKey;
        private final String displayName;
        private final int hash;

        private ItemKey(ItemStack proto) {
            this.proto = proto;
            this.proto.setAmount(1);
            this.materialKey = proto.getType().key().asString();
            this.displayName = proto.displayName().toString();
            this.hash = this.proto.hashCode();
        }

        static ItemKey of(ItemStack stack) {
            ItemStack proto = stack.clone();
            proto.setAmount(1);
            return new ItemKey(proto);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ItemKey other && this.proto.isSimilar(other.proto);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }
}
