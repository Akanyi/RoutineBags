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
        for (int slot = 0; slot < 36; slot++) {
            recognize(out, inv, slot);
        }
        recognize(out, inv, 40);
        return out;
    }

    private static void recognize(List<Bag> out, PlayerInventory inv, int slot) {
        ItemStack stack = inv.getItem(slot);
        if (stack == null || stack.isEmpty() || stack.getAmount() != 1) {
            return;
        }
        BundleContents contents = stack.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (contents != null) {
            List<ItemStack> copy = contents.contents().stream().map(ItemStack::clone).toList();
            out.add(new Bag(slot, stack.clone(), copy));
        }
    }

    private static List<List<ItemStack>> buildLayout(List<Bag> bags, List<ItemKey> order, Map<ItemKey, Integer> totals) {
        List<Integer> fillOrder = new ArrayList<>();
        for (int i = 0; i < bags.size(); i++) {
            fillOrder.add(i);
        }
        fillOrder.sort((a, b) -> {
            int cmp = BundleWeights.compareContents(bags.get(b).contents, bags.get(a).contents);
            return cmp != 0 ? cmp : Integer.compare(bags.get(a).slot, bags.get(b).slot);
        });

        List<List<ItemStack>> layout = new ArrayList<>();
        for (int i = 0; i < bags.size(); i++) {
            layout.add(new ArrayList<>());
        }
        for (ItemKey key : order) {
            int remaining = totals.getOrDefault(key, 0);
            if (remaining <= 0) {
                continue;
            }
            int wholeTarget = -1;
            for (int i : fillOrder) {
                if (BundleWeights.maxInsertable(layout.get(i), key.proto, remaining) >= remaining) {
                    wholeTarget = i;
                    break;
                }
            }
            if (wholeTarget != -1) {
                addStacks(layout.get(wholeTarget), key.proto, remaining);
                continue;
            }
            for (int i : fillOrder) {
                if (remaining <= 0) {
                    break;
                }
                int take = BundleWeights.maxInsertable(layout.get(i), key.proto, remaining);
                if (take > 0) {
                    addStacks(layout.get(i), key.proto, take);
                    remaining -= take;
                }
            }
            if (remaining > 0) {
                throw new IllegalStateException("Bundle layout could not preserve every item");
            }
        }
        return layout;
    }

    private static void applyLayout(PlayerInventory inv, List<Bag> bags, List<List<ItemStack>> layout) {
        for (Bag bag : bags) {
            if (!sameStack(inv.getItem(bag.slot), bag.stack)) {
                throw new IllegalStateException("Bundle inventory changed while sorting");
            }
        }
        ItemStack[] originalStorage = cloneContents(inv.getStorageContents());
        ItemStack originalOffhand = cloneOrNull(inv.getItem(40));
        ItemStack[] finalStorage = cloneContents(originalStorage);
        ItemStack finalOffhand = cloneOrNull(originalOffhand);
        for (int i = 0; i < bags.size(); i++) {
            Bag bag = bags.get(i);
            ItemStack replacement = bag.stack.clone();
            replacement.setData(DataComponentTypes.BUNDLE_CONTENTS, BundleContents.bundleContents(layout.get(i)));
            if (bag.slot == 40) {
                finalOffhand = replacement;
            } else {
                finalStorage[bag.slot] = replacement;
            }
        }
        try {
            inv.setStorageContents(finalStorage);
            if (!sameStack(originalOffhand, finalOffhand)) {
                inv.setItem(40, finalOffhand);
            }
        } catch (RuntimeException ex) {
            try {
                inv.setStorageContents(cloneContents(originalStorage));
                inv.setItem(40, cloneOrNull(originalOffhand));
            } catch (RuntimeException rollbackFailure) {
                ex.addSuppressed(rollbackFailure);
            }
            throw ex;
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

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty() || stack.getAmount() <= 0;
    }

    private static boolean sameStack(ItemStack left, ItemStack right) {
        return isEmpty(left) ? isEmpty(right) : !isEmpty(right) && left.equals(right);
    }

    private static ItemStack cloneOrNull(ItemStack stack) {
        return isEmpty(stack) ? null : stack.clone();
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = cloneOrNull(contents[i]);
        }
        return copy;
    }

    private record Bag(int slot, ItemStack stack, List<ItemStack> contents) {}

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
