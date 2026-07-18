package dev.lans.routinebagkkit;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

final class TakeService {
    TakeResult take(Player player, Wire.TakeRequest request, PluginSettings settings, int liveContainerId) {
        if (request.containerId() != liveContainerId
                || (request.destination() != 0 && request.destination() != 1)
                || request.targets().isEmpty() || request.targets().size() > 128) {
            return TakeResult.fail(request.requestId());
        }
        ItemStack originalCursor = player.getItemOnCursor().clone();
        if (request.destination() == 0 && !isEmpty(originalCursor)) {
            return TakeResult.fail(request.requestId());
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack[] originalStorage = cloneContents(inventory.getStorageContents());
        ItemStack originalOffhand = cloneOrNull(inventory.getItem(40));
        Map<Integer, PlannedBag> planned = new HashMap<>();
        List<ItemStack> extracted = new ArrayList<>();
        Map<Integer, Integer> lastEntryBySlot = new HashMap<>();
        int total = 0;
        for (Wire.TakeTarget target : request.targets()) {
            if (target.amount() <= 0 || target.amount() > settings.maxItemsPerRequest) {
                return TakeResult.fail(request.requestId());
            }
            total = Math.addExact(total, target.amount());
            if (total > settings.maxItemsPerRequest) {
                return TakeResult.fail(request.requestId());
            }
            int slot = bukkitSlotForMenuSlot(target.bagMenuSlot());
            int previousEntry = lastEntryBySlot.getOrDefault(slot, Integer.MAX_VALUE);
            if (slot < 0 || target.entryIndex() >= previousEntry) {
                return TakeResult.fail(request.requestId());
            }
            lastEntryBySlot.put(slot, target.entryIndex());
            PlannedBag bag = planned.computeIfAbsent(slot, ignored -> readBag(inventory, slot));
            if (bag == null || target.entryIndex() < 0 || target.entryIndex() >= bag.contents.size()) {
                return TakeResult.fail(request.requestId());
            }
            ItemStack entry = bag.contents.get(target.entryIndex());
            if (isEmpty(entry) || target.amount() > entry.getAmount()
                    || !MessageDigest.isEqual(identityHash(entry), target.expectedHash())) {
                return TakeResult.fail(request.requestId());
            }
            ItemStack taken = entry.clone();
            taken.setAmount(target.amount());
            extracted.add(taken);
            int left = entry.getAmount() - target.amount();
            if (left == 0) {
                bag.contents.remove(target.entryIndex());
            } else {
                entry.setAmount(left);
            }
        }

        ItemStack[] finalStorage = cloneContents(originalStorage);
        if (request.destination() == 1 && !planInventoryDelivery(inventory, finalStorage, extracted)) {
            return inventoryFull(request.requestId());
        }
        ItemStack finalOffhand = cloneOrNull(originalOffhand);
        for (PlannedBag bag : planned.values()) {
            ItemStack live = inventory.getItem(bag.slot);
            if (!sameStack(live, bag.original)) {
                return TakeResult.fail(request.requestId());
            }
            ItemStack replacement = bag.original.clone();
            replacement.setData(DataComponentTypes.BUNDLE_CONTENTS, BundleContents.bundleContents(bag.contents));
            if (bag.slot == 40) {
                finalOffhand = replacement;
            } else {
                finalStorage[bag.slot] = replacement;
            }
        }
        if (PaperContainerId.current(player) != request.containerId()
                || !inventoryMatches(inventory, originalStorage, originalOffhand)
                || !sameStack(player.getItemOnCursor(), originalCursor)) {
            return TakeResult.fail(request.requestId());
        }

        ItemStack finalCursor = originalCursor;
        if (request.destination() == 0) {
            finalCursor = cursorStack(extracted);
            if (isEmpty(finalCursor)) {
                return inventoryFull(request.requestId());
            }
        }
        try {
            inventory.setStorageContents(finalStorage);
            if (!sameStack(originalOffhand, finalOffhand)) {
                inventory.setItem(40, finalOffhand);
            }
            if (request.destination() == 0) {
                player.setItemOnCursor(finalCursor);
            }
        } catch (RuntimeException ex) {
            rollback(player, originalStorage, originalOffhand, originalCursor, ex);
            throw ex;
        }
        player.updateInventory();
        return new TakeResult(request.requestId(), true, total, "gui.routinebags.status.server_taken");
    }

    private static PlannedBag readBag(PlayerInventory inventory, int slot) {
        ItemStack stack = inventory.getItem(slot);
        BundleContents contents = isEmpty(stack) ? null : stack.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (contents == null || stack.getAmount() != 1) {
            return null;
        }
        return new PlannedBag(slot, stack.clone(), contents.contents().stream().map(ItemStack::clone)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
    }

    private static byte[] identityHash(ItemStack stack) {
        try {
            ItemStack single = stack.clone();
            single.setAmount(1);
            return MessageDigest.getInstance("SHA-256").digest(single.serializeAsBytes());
        } catch (java.security.NoSuchAlgorithmException exception) {
            return new byte[0];
        }
    }

    private static boolean planInventoryDelivery(PlayerInventory inventory, ItemStack[] storage, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            int remaining = stack.getAmount();
            for (ItemStack slot : storage) {
                if (remaining == 0) {
                    break;
                }
                if (!isEmpty(slot) && slot.getMaxStackSize() > 1 && slot.isSimilar(stack)) {
                    int capacity = Math.min(slot.getMaxStackSize(), inventory.getMaxStackSize());
                    int move = Math.min(remaining, Math.max(capacity - slot.getAmount(), 0));
                    slot.setAmount(slot.getAmount() + move);
                    remaining -= move;
                }
            }
            for (int i = 0; i < storage.length && remaining > 0; i++) {
                if (isEmpty(storage[i])) {
                    int move = Math.min(remaining, Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize()));
                    ItemStack placed = stack.clone();
                    placed.setAmount(move);
                    storage[i] = placed;
                    remaining -= move;
                }
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    private static ItemStack cursorStack(List<ItemStack> stacks) {
        if (stacks.isEmpty()) {
            return null;
        }
        ItemStack first = stacks.getFirst();
        long amount = 0;
        for (ItemStack stack : stacks) {
            if (!first.isSimilar(stack)) {
                return null;
            }
            amount += stack.getAmount();
        }
        if (amount > first.getMaxStackSize()) {
            return null;
        }
        ItemStack carried = first.clone();
        carried.setAmount((int) amount);
        return carried;
    }

    private static boolean inventoryMatches(PlayerInventory inventory, ItemStack[] storage, ItemStack offhand) {
        ItemStack[] live = inventory.getStorageContents();
        if (live.length != storage.length) {
            return false;
        }
        for (int i = 0; i < storage.length; i++) {
            if (!sameStack(live[i], storage[i])) {
                return false;
            }
        }
        return sameStack(inventory.getItem(40), offhand);
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

    private static void rollback(Player player, ItemStack[] storage, ItemStack offhand,
            ItemStack cursor, RuntimeException cause) {
        try {
            player.getInventory().setStorageContents(cloneContents(storage));
            player.getInventory().setItem(40, cloneOrNull(offhand));
            player.setItemOnCursor(cursor.clone());
        } catch (RuntimeException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private static int bukkitSlotForMenuSlot(int menuSlot) {
        if (menuSlot >= 9 && menuSlot < 36) {
            return menuSlot;
        }
        if (menuSlot >= 36 && menuSlot < 45) {
            return menuSlot - 36;
        }
        return menuSlot == 45 ? 40 : -1;
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty() || stack.getAmount() <= 0;
    }

    record TakeResult(int requestId, boolean success, int moved, String messageKey) {
        static TakeResult fail(int requestId) {
            return new TakeResult(requestId, false, 0, "gui.routinebags.status.server_take_failed");
        }
    }

    private static TakeResult inventoryFull(int requestId) {
        return new TakeResult(requestId, false, 0, "gui.routinebags.status.inv_full");
    }

    private record PlannedBag(int slot, ItemStack original, List<ItemStack> contents) {}
}
