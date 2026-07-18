package dev.lans.routinebagkkit;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import java.util.ArrayList;
import java.util.List;
import java.security.MessageDigest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

final class StoreService {
    StoreResult store(Player player, Wire.StoreRequestV3 request, PluginSettings settings, int liveContainerId) {
        if (request.containerId() != liveContainerId || request.amount() <= 0) {
            return StoreResult.fail("gui.routinebags.status.server_store_failed");
        }
        return store(player, request.sourceSlot(), settings, request.containerId(),
                request.amount(), request.expectedHash());
    }

    private StoreResult store(Player player, int sourceSlot, PluginSettings settings, int expectedContainerId,
            int expectedAmount, byte[] expectedHash) {
        PlayerInventory inv = player.getInventory();
        sourceSlot = bukkitSlotForMenuSlot(sourceSlot);
        if (sourceSlot < 0 || sourceSlot >= inv.getSize()) {
            return StoreResult.fail("gui.routinebags.status.server_store_failed");
        }
        ItemStack source = inv.getItem(sourceSlot);
        if (isEmpty(source) || source.getAmount() <= 0 || source.getAmount() > settings.maxItemsPerRequest
                || !BundleWeights.canStore(source)) {
            return StoreResult.fail("gui.routinebags.cant_fit");
        }
        if (expectedHash != null && (source.getAmount() != expectedAmount
                || !MessageDigest.isEqual(identityHash(source), expectedHash))) {
            return StoreResult.fail("gui.routinebags.status.server_store_failed");
        }
        ItemStack originalSource = source.clone();

        List<Bag> bags = scan(inv, sourceSlot, settings.maxBags);
        if (bags.isEmpty()) {
            return StoreResult.fail("gui.routinebags.status.bags_full_generic");
        }

        ItemStack remaining = source.clone();
        int moved = 0;
        List<BagUpdate> updates = new ArrayList<>();
        for (Bag bag : bags) {
            if (remaining.getAmount() <= 0) {
                break;
            }
            int fit = BundleWeights.maxInsertable(bag.contents, remaining, remaining.getAmount());
            if (fit <= 0) {
                continue;
            }
            addStacks(bag.contents, remaining, fit);
            remaining.setAmount(remaining.getAmount() - fit);
            moved += fit;
            updates.add(new BagUpdate(bag.slot, bag.stack, BundleContents.bundleContents(bag.contents)));
        }

        if (moved <= 0) {
            return StoreResult.fail("gui.routinebags.status.bags_full_generic");
        }
        if (expectedContainerId >= 0 && PaperContainerId.current(player) != expectedContainerId) {
            return StoreResult.fail("gui.routinebags.status.server_store_failed");
        }
        if (!sameStack(inv.getItem(sourceSlot), originalSource)) {
            return StoreResult.fail("gui.routinebags.status.server_store_failed");
        }
        for (BagUpdate update : updates) {
            if (!sameStack(inv.getItem(update.slot), update.original)) {
                return StoreResult.fail("gui.routinebags.status.server_store_failed");
            }
        }

        ItemStack[] originalStorage = cloneContents(inv.getStorageContents());
        ItemStack originalOffhand = cloneOrNull(inv.getItem(40));
        ItemStack[] finalStorage = cloneContents(originalStorage);
        ItemStack finalOffhand = cloneOrNull(originalOffhand);
        boolean offhandChanged = sourceSlot == 40;
        for (BagUpdate update : updates) {
            ItemStack replacement = update.original.clone();
            replacement.setData(DataComponentTypes.BUNDLE_CONTENTS, update.contents);
            if (update.slot == 40) {
                finalOffhand = replacement;
                offhandChanged = true;
            } else {
                finalStorage[update.slot] = replacement;
            }
        }
        ItemStack finalSource = remaining.getAmount() > 0 ? remaining : null;
        if (sourceSlot == 40) {
            finalOffhand = finalSource;
        } else {
            finalStorage[sourceSlot] = finalSource;
        }
        try {
            inv.setStorageContents(finalStorage);
            if (offhandChanged) {
                inv.setItem(40, finalOffhand);
            }
        } catch (RuntimeException ex) {
            rollback(inv, originalStorage, originalOffhand, ex);
            throw ex;
        }
        player.updateInventory();
        return new StoreResult(true, moved, "gui.routinebags.status.server_stored");
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

    private static List<Bag> scan(PlayerInventory inv, int sourceSlot, int maxBags) {
        List<Bag> out = new ArrayList<>();
        for (int slot = 0; slot < 36 && out.size() < maxBags; slot++) {
            recognize(out, inv, slot, sourceSlot);
        }
        if (out.size() < maxBags) {
            recognize(out, inv, 40, sourceSlot);
        }
        return out;
    }

    private static void recognize(List<Bag> out, PlayerInventory inv, int slot, int sourceSlot) {
            if (slot == sourceSlot) {
                return;
            }
            ItemStack stack = inv.getItem(slot);
            if (stack == null || stack.isEmpty() || stack.getAmount() != 1) {
                return;
            }
            BundleContents contents = stack.getData(DataComponentTypes.BUNDLE_CONTENTS);
            if (contents != null) {
                out.add(new Bag(slot, stack.clone(), contents.contents().stream().map(ItemStack::clone).collect(java.util.stream.Collectors.toCollection(ArrayList::new))));
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

    private static void rollback(PlayerInventory inv, ItemStack[] storage, ItemStack offhand, RuntimeException cause) {
        try {
            inv.setStorageContents(cloneContents(storage));
            inv.setItem(40, cloneOrNull(offhand));
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

    private record BagUpdate(int slot, ItemStack original, BundleContents contents) {}
}
