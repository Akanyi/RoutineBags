package dev.lans.routinebagkkit;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

final class StoreService {
    StoreResult store(Player player, int sourceSlot, PluginSettings settings) {
        PlayerInventory inv = player.getInventory();
        sourceSlot = bukkitSlotForMenuSlot(sourceSlot);
        if (sourceSlot < 0 || sourceSlot >= inv.getSize()) {
            return StoreResult.fail("gui.routinebags.status.server_store_failed");
        }
        ItemStack source = inv.getItem(sourceSlot);
        if (isEmpty(source) || source.getAmount() <= 0 || source.getAmount() > settings.maxItemsPerRequest || !canStore(source)) {
            return StoreResult.fail("gui.routinebags.cant_fit");
        }

        List<Bag> bags = scan(inv, sourceSlot, settings.maxBags);
        if (bags.isEmpty()) {
            return StoreResult.fail("gui.routinebags.status.bags_full_generic");
        }

        ItemStack remaining = source.clone();
        int moved = 0;
        for (Bag bag : bags) {
            if (remaining.getAmount() <= 0) {
                break;
            }
            int fit = Math.min(maxInsertable(bag.contents, remaining), remaining.getAmount());
            if (fit <= 0) {
                continue;
            }
            addStacks(bag.contents, remaining, fit);
            remaining.setAmount(remaining.getAmount() - fit);
            moved += fit;
            writeBag(inv, bag);
        }

        if (moved <= 0) {
            return StoreResult.fail("gui.routinebags.status.bags_full_generic");
        }
        if (remaining.getAmount() > 0) {
            inv.setItem(sourceSlot, remaining);
        } else {
            inv.setItem(sourceSlot, null);
        }
        player.updateInventory();
        return new StoreResult(true, moved, "gui.routinebags.status.server_stored");
    }

    private static List<Bag> scan(PlayerInventory inv, int sourceSlot, int maxBags) {
        List<Bag> out = new ArrayList<>();
        for (int slot = 0; slot < inv.getSize() && out.size() < maxBags; slot++) {
            if (slot == sourceSlot) {
                continue;
            }
            ItemStack stack = inv.getItem(slot);
            if (stack == null || stack.isEmpty() || stack.getAmount() != 1) {
                continue;
            }
            BundleContents contents = stack.getData(DataComponentTypes.BUNDLE_CONTENTS);
            if (contents != null) {
                out.add(new Bag(slot, stack.clone(), contents.contents().stream().map(ItemStack::clone).collect(java.util.stream.Collectors.toCollection(ArrayList::new))));
            }
        }
        return out;
    }

    private static void writeBag(PlayerInventory inv, Bag bag) {
        ItemStack live = inv.getItem(bag.slot);
        if (live == null || live.isEmpty() || live.getAmount() != 1 || live.getData(DataComponentTypes.BUNDLE_CONTENTS) == null) {
            return;
        }
        live.setData(DataComponentTypes.BUNDLE_CONTENTS, BundleContents.bundleContents(bag.contents));
        inv.setItem(bag.slot, live);
    }

    private static int maxInsertable(List<ItemStack> contents, ItemStack stack) {
        int free = 64 - usedUnits(contents);
        int unit = unitWeight(stack);
        return Math.max(free / unit, 0);
    }

    private static int usedUnits(List<ItemStack> contents) {
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

    private static boolean canStore(ItemStack stack) {
        if (isEmpty(stack)) {
            return false;
        }
        if (stack.getData(DataComponentTypes.BUNDLE_CONTENTS) != null) {
            return false;
        }
        return unitWeight(stack) <= 64;
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

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty() || stack.getAmount() <= 0;
    }

    private static int bukkitSlotForMenuSlot(int menuSlot) {
        if (menuSlot >= 9 && menuSlot < 36) {
            return menuSlot;
        }
        if (menuSlot >= 36 && menuSlot < 45) {
            return menuSlot - 36;
        }
        if (menuSlot == 45) {
            return 40;
        }
        return -1;
    }

    record StoreResult(boolean success, int moved, String messageKey) {
        static StoreResult fail(String messageKey) {
            return new StoreResult(false, 0, messageKey);
        }
    }

    private record Bag(int slot, ItemStack stack, List<ItemStack> contents) {}
}
