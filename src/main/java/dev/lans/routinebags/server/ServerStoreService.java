package dev.lans.routinebags.server;

import dev.lans.routinebags.RoutineBags;
import dev.lans.routinebags.bag.BagView;
import dev.lans.routinebags.network.ItemIdentity;
import dev.lans.routinebags.network.RoutineBagsNetwork;
import dev.lans.routinebags.network.RoutineBagsNetwork.StoreRequestV3Payload;
import dev.lans.routinebags.network.RoutineBagsNetwork.StoreResultV3Payload;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;

public final class ServerStoreService {
    private static final int MAX_CACHED_REQUESTS = 32;
    private static final Map<UUID, LinkedHashMap<Integer, CachedResult>> SUCCESS_CACHE = new HashMap<>();

    public static void handleStoreRequest(ServerPlayer player, StoreRequestV3Payload payload) {
        CachedResult cached = cachedResult(player.getUUID(), payload.requestId());
        if (cached != null) {
            RoutineBagsNetwork.sendStoreResultV3(player,
                    cached.request.equals(payload) ? cached.result : failed(payload.requestId()));
            return;
        }

        StoreResultV3Payload result;
        try {
            result = store(player, payload);
        } catch (RuntimeException e) {
            RoutineBags.LOGGER.warn("Server-side bundle store failed for {}", player.getName().getString(), e);
            result = failed(payload.requestId());
        }
        if (result.success()) {
            cacheSuccess(player.getUUID(), new CachedResult(payload, result));
        }
        RoutineBagsNetwork.sendStoreResultV3(player, result);
    }

    public static void clearPlayer(ServerPlayer player) {
        SUCCESS_CACHE.remove(player.getUUID());
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

    private static StoreResultV3Payload store(ServerPlayer player, StoreRequestV3Payload payload) {
        if (player.containerMenu.containerId != payload.containerId() || payload.amount() <= 0) {
            return failed(payload.requestId());
        }
        int invIndex = ServerBagScanner.invIndexForMenuSlot(payload.menuSlot());
        if (invIndex < 0) {
            return failed(payload.requestId());
        }
        Inventory inventory = player.getInventory();
        ItemStack source = inventory.getItem(invIndex);
        if (source.isEmpty() || source.get(DataComponents.BUNDLE_CONTENTS) != null
                || !BundleContents.canItemBeInBundle(source) || source.getCount() != payload.amount()
                || !MessageDigest.isEqual(ItemIdentity.hash(source, player.registryAccess()), payload.expectedHash())) {
            return failed(payload.requestId());
        }
        return store(player, payload.requestId(), payload.containerId(), invIndex, source);
    }

    private static StoreResultV3Payload store(ServerPlayer player, int requestId, int expectedContainerId,
            int invIndex, ItemStack source) {
        Inventory inventory = player.getInventory();
        ItemStack originalSource = source.copy();
        ItemStack remaining = source.copy();
        int moved = 0;
        List<BagUpdate> updates = new ArrayList<>();
        List<BagView> bags = ServerBagScanner.scanMutableBundles(player);
        for (BagView bag : bags) {
            if (bag.invIndex == invIndex || remaining.isEmpty()) {
                continue;
            }
            ItemStack liveBag = inventory.getItem(bag.invIndex);
            BundleContents liveContents = liveBag.get(DataComponents.BUNDLE_CONTENTS);
            if (liveBag.getCount() != 1 || liveContents == null) {
                continue;
            }
            BundleContents.Mutable mutable = new BundleContents.Mutable(liveContents);
            int inserted = mutable.tryInsert(remaining);
            if (inserted > 0) {
                updates.add(new BagUpdate(bag.invIndex, liveBag.copy(), mutable.toImmutable()));
                moved += inserted;
            }
        }
        if (moved == 0) {
            return failed(requestId);
        }
        if (expectedContainerId >= 0 && player.containerMenu.containerId != expectedContainerId) {
            return failed(requestId);
        }
        if (!ItemStack.matches(inventory.getItem(invIndex), originalSource)) {
            return failed(requestId);
        }
        for (BagUpdate update : updates) {
            ItemStack liveBag = inventory.getItem(update.invIndex);
            if (!ItemStack.matches(liveBag, update.originalBag)) {
                return failed(requestId);
            }
        }
        Map<Integer, ItemStack> originals = new HashMap<>();
        originals.put(invIndex, originalSource);
        for (BagUpdate update : updates) {
            originals.put(update.invIndex, update.originalBag);
        }
        try {
            for (BagUpdate update : updates) {
                ItemStack liveBag = inventory.getItem(update.invIndex).copy();
                liveBag.set(DataComponents.BUNDLE_CONTENTS, update.contents);
                inventory.setItem(update.invIndex, liveBag);
            }
            inventory.setItem(invIndex, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
        } catch (RuntimeException e) {
            for (Map.Entry<Integer, ItemStack> original : originals.entrySet()) {
                inventory.setItem(original.getKey(), original.getValue().copy());
            }
            throw e;
        }
        inventory.setChanged();
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastChanges();
        }
        return new StoreResultV3Payload(requestId, true, moved, "gui.routinebags.status.server_stored");
    }

    private static StoreResultV3Payload failed(int requestId) {
        return new StoreResultV3Payload(requestId, false, 0, "gui.routinebags.status.server_store_failed");
    }

    private record CachedResult(StoreRequestV3Payload request, StoreResultV3Payload result) {}

    private record BagUpdate(int invIndex, ItemStack originalBag, BundleContents contents) {}

    private ServerStoreService() {}
}
