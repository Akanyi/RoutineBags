package dev.lans.routinebags.server;

import dev.lans.routinebags.RoutineBags;
import dev.lans.routinebags.network.ItemIdentity;
import dev.lans.routinebags.network.RoutineBagsNetwork;
import dev.lans.routinebags.network.RoutineBagsNetwork.TakeRequestPayload;
import dev.lans.routinebags.network.RoutineBagsNetwork.TakeResultPayload;
import dev.lans.routinebags.network.RoutineBagsNetwork.TakeTarget;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.MessageDigest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;

public final class ServerTakeService {
    private static final int MAX_ITEMS_PER_REQUEST = 4096;
    private static final int MAX_CACHED_REQUESTS = 32;
    private static final long MIN_REQUEST_INTERVAL_TICKS = 2;
    private static final Map<UUID, LinkedHashMap<Integer, CachedResult>> SUCCESS_CACHE = new HashMap<>();
    private static final Map<UUID, Long> LAST_REQUEST_TICK = new HashMap<>();

    public static void handleTakeRequest(ServerPlayer player, TakeRequestPayload payload) {
        CachedResult cached = cachedResult(player.getUUID(), payload.requestId());
        if (cached != null) {
            RoutineBagsNetwork.sendTakeResult(player,
                    cached.request.equals(payload) ? cached.result : failed(payload.requestId()));
            return;
        }
        UUID playerId = player.getUUID();
        long currentTick = player.level().getGameTime();
        Long lastTick = LAST_REQUEST_TICK.get(playerId);
        if (lastTick != null && currentTick - lastTick < MIN_REQUEST_INTERVAL_TICKS) {
            RoutineBagsNetwork.sendTakeResult(player, failed(payload.requestId()));
            return;
        }
        LAST_REQUEST_TICK.put(playerId, currentTick);

        TakeResultPayload result;
        try {
            result = take(player, payload);
        } catch (RuntimeException e) {
            RoutineBags.LOGGER.warn("Server-side bundle take failed for {}", player.getName().getString(), e);
            result = failed(payload.requestId());
        }
        if (result.success()) {
            cacheSuccess(player.getUUID(), new CachedResult(payload, result));
        }
        RoutineBagsNetwork.sendTakeResult(player, result);
    }

    public static void clearPlayer(ServerPlayer player) {
        SUCCESS_CACHE.remove(player.getUUID());
        LAST_REQUEST_TICK.remove(player.getUUID());
    }

    private static CachedResult cachedResult(UUID playerId, int requestId) {
        Map<Integer, CachedResult> cache = SUCCESS_CACHE.get(playerId);
        return cache == null ? null : cache.get(requestId);
    }

    private static void cacheSuccess(UUID playerId, CachedResult result) {
        LinkedHashMap<Integer, CachedResult> cache = SUCCESS_CACHE.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>());
        cache.put(result.request.requestId(), result);
        while (cache.size() > MAX_CACHED_REQUESTS) {
            cache.remove(cache.keySet().iterator().next());
        }
    }

    private static TakeResultPayload take(ServerPlayer player, TakeRequestPayload payload) {
        if (payload.containerId() != player.containerMenu.containerId
                || (payload.destination() != TakeRequestPayload.DESTINATION_CURSOR
                && payload.destination() != TakeRequestPayload.DESTINATION_INVENTORY)
                || payload.targets().isEmpty() || payload.targets().size() > TakeRequestPayload.MAX_TARGETS) {
            return failed(payload.requestId());
        }
        int expectedContainerId = payload.containerId();
        ItemStack originalCarried = player.containerMenu.getCarried().copy();
        if (payload.destination() == TakeRequestPayload.DESTINATION_CURSOR && !originalCarried.isEmpty()) {
            return failed(payload.requestId());
        }

        Inventory inventory = player.getInventory();
        List<ItemStack> originalStorage = copyStacks(inventory.getNonEquipmentItems());
        ItemStack originalOffhand = inventory.getItem(Inventory.SLOT_OFFHAND).copy();
        List<PlannedBag> plannedBags = new ArrayList<>();
        List<ItemStack> extracted = new ArrayList<>();
        Map<Integer, Integer> lastEntryBySlot = new HashMap<>();
        int requestedTotal = 0;
        for (TakeTarget target : payload.targets()) {
            int invIndex = ServerBagScanner.invIndexForMenuSlot(target.bagMenuSlot());
            int previousEntry = lastEntryBySlot.getOrDefault(invIndex, Integer.MAX_VALUE);
            if (target.amount() <= 0 || invIndex < 0 || target.entryIndex() >= previousEntry) {
                return failed(payload.requestId());
            }
            lastEntryBySlot.put(invIndex, target.entryIndex());
            requestedTotal = Math.addExact(requestedTotal, target.amount());
            if (requestedTotal > MAX_ITEMS_PER_REQUEST) {
                return failed(payload.requestId());
            }
            PlannedBag bag = plannedBag(inventory, plannedBags, target.bagMenuSlot());
            if (bag == null || target.entryIndex() < 0 || target.entryIndex() >= bag.entries.size()) {
                return failed(payload.requestId());
            }
            ItemStackTemplate template = bag.entries.get(target.entryIndex());
            if (target.amount() > template.count()
                    || !MessageDigest.isEqual(ItemIdentity.hash(template.create(), player.registryAccess()),
                            target.expectedHash())) {
                return failed(payload.requestId());
            }
            extracted.add(template.create().copyWithCount(target.amount()));
            int left = template.count() - target.amount();
            if (left == 0) {
                bag.entries.remove(target.entryIndex());
            } else {
                bag.entries.set(target.entryIndex(), template.withCount(left));
            }
        }

        List<ItemStack> finalStorage = copyStacks(originalStorage);
        if (payload.destination() == TakeRequestPayload.DESTINATION_INVENTORY
                && !planInventoryDelivery(inventory, finalStorage, extracted)) {
            return inventoryFull(payload.requestId());
        }
        ItemStack finalOffhand = originalOffhand.copy();
        for (PlannedBag bag : plannedBags) {
            ItemStack live = inventory.getItem(bag.invIndex);
            if (!ItemStack.matches(live, bag.original)) {
                return failed(payload.requestId());
            }
            ItemStack replacement = bag.original.copy();
            replacement.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.copyOf(bag.entries)));
            if (bag.invIndex == Inventory.SLOT_OFFHAND) {
                finalOffhand = replacement;
            } else {
                finalStorage.set(bag.invIndex, replacement);
            }
        }
        if (player.containerMenu.containerId != expectedContainerId
                || !inventoryMatches(inventory, originalStorage, originalOffhand)
                || !ItemStack.matches(player.containerMenu.getCarried(), originalCarried)) {
            return failed(payload.requestId());
        }

        ItemStack finalCarried = originalCarried;
        if (payload.destination() == TakeRequestPayload.DESTINATION_CURSOR) {
            finalCarried = cursorStack(extracted);
            if (finalCarried.isEmpty()) {
                return inventoryFull(payload.requestId());
            }
        }
        try {
            applyInventory(inventory, originalStorage, originalOffhand, finalStorage, finalOffhand);
            if (payload.destination() == TakeRequestPayload.DESTINATION_CURSOR) {
                player.containerMenu.setCarried(finalCarried);
            }
        } catch (RuntimeException e) {
            rollback(player, originalStorage, originalOffhand, originalCarried, e);
            throw e;
        }
        inventory.setChanged();
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastChanges();
        }
        return new TakeResultPayload(payload.requestId(), true, requestedTotal, "gui.routinebags.status.server_taken");
    }

    private static PlannedBag plannedBag(Inventory inventory, List<PlannedBag> bags, int menuSlot) {
        int invIndex = ServerBagScanner.invIndexForMenuSlot(menuSlot);
        if (invIndex < 0) {
            return null;
        }
        for (PlannedBag bag : bags) {
            if (bag.invIndex == invIndex) {
                return bag;
            }
        }
        ItemStack live = inventory.getItem(invIndex);
        BundleContents contents = live.get(DataComponents.BUNDLE_CONTENTS);
        if (live.getCount() != 1 || contents == null) {
            return null;
        }
        PlannedBag bag = new PlannedBag(invIndex, live.copy(), new ArrayList<>(contents.items()));
        bags.add(bag);
        return bag;
    }

    private static boolean planInventoryDelivery(Inventory inventory, List<ItemStack> storage, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            ItemStack remaining = stack.copy();
            for (ItemStack slot : storage) {
                if (remaining.isEmpty()) {
                    break;
                }
                if (!slot.isEmpty() && slot.isStackable() && ItemStack.isSameItemSameComponents(slot, remaining)) {
                    int move = Math.min(remaining.getCount(), inventory.getMaxStackSize(slot) - slot.getCount());
                    slot.grow(move);
                    remaining.shrink(move);
                }
            }
            for (int i = 0; i < storage.size() && !remaining.isEmpty(); i++) {
                if (storage.get(i).isEmpty()) {
                    int move = Math.min(remaining.getCount(), inventory.getMaxStackSize(remaining));
                    storage.set(i, remaining.copyWithCount(move));
                    remaining.shrink(move);
                }
            }
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static ItemStack cursorStack(List<ItemStack> stacks) {
        if (stacks.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack first = stacks.getFirst();
        long count = 0;
        for (ItemStack stack : stacks) {
            if (!ItemStack.isSameItemSameComponents(first, stack)) {
                return ItemStack.EMPTY;
            }
            count += stack.getCount();
        }
        return count > first.getMaxStackSize() ? ItemStack.EMPTY : first.copyWithCount((int) count);
    }

    private static boolean inventoryMatches(Inventory inventory, List<ItemStack> storage, ItemStack offhand) {
        for (int i = 0; i < storage.size(); i++) {
            if (!ItemStack.matches(inventory.getItem(i), storage.get(i))) {
                return false;
            }
        }
        return ItemStack.matches(inventory.getItem(Inventory.SLOT_OFFHAND), offhand);
    }

    private static void applyInventory(Inventory inventory, List<ItemStack> originalStorage, ItemStack originalOffhand,
            List<ItemStack> finalStorage, ItemStack finalOffhand) {
        for (int i = 0; i < finalStorage.size(); i++) {
            if (!ItemStack.matches(originalStorage.get(i), finalStorage.get(i))) {
                inventory.setItem(i, finalStorage.get(i).copy());
            }
        }
        if (!ItemStack.matches(originalOffhand, finalOffhand)) {
            inventory.setItem(Inventory.SLOT_OFFHAND, finalOffhand.copy());
        }
    }

    private static void rollback(ServerPlayer player, List<ItemStack> storage, ItemStack offhand,
            ItemStack carried, RuntimeException cause) {
        try {
            for (int i = 0; i < storage.size(); i++) {
                player.getInventory().setItem(i, storage.get(i).copy());
            }
            player.getInventory().setItem(Inventory.SLOT_OFFHAND, offhand.copy());
            player.containerMenu.setCarried(carried.copy());
        } catch (RuntimeException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        return stacks.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static TakeResultPayload failed(int requestId) {
        return new TakeResultPayload(requestId, false, 0, "gui.routinebags.status.server_take_failed");
    }

    private static TakeResultPayload inventoryFull(int requestId) {
        return new TakeResultPayload(requestId, false, 0, "gui.routinebags.status.inv_full");
    }

    private record CachedResult(TakeRequestPayload request, TakeResultPayload result) {}

    private static final class PlannedBag {
        final int invIndex;
        final ItemStack original;
        final List<ItemStackTemplate> entries;

        PlannedBag(int invIndex, ItemStack original, List<ItemStackTemplate> entries) {
            this.invIndex = invIndex;
            this.original = original;
            this.entries = entries;
        }
    }

    private ServerTakeService() {}
}
