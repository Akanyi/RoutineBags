package dev.lans.routinebagkkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class RoutineBagkkitPlugin extends JavaPlugin implements Listener, PluginMessageListener {
    private static final int MAX_CACHED_REQUESTS = 32;
    private final SortService sorter = new SortService();
    private final StoreService storeService = new StoreService();
    private final TakeService takeService = new TakeService();
    private final Map<UUID, Long> lastRequestAt = new HashMap<>();
    private final Map<UUID, LinkedHashMap<Integer, CachedStoreResult>> storeSuccessCache = new HashMap<>();
    private final Map<UUID, LinkedHashMap<Integer, CachedTakeResult>> takeSuccessCache = new HashMap<>();
    private PluginSettings settings;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.settings = PluginSettings.load(getConfig());
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, Wire.HELLO);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, Wire.SORT_RESULT);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, Wire.STORE_RESULT_V3);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, Wire.TAKE_RESULT);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, Wire.SORT_REQUEST, this);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, Wire.STORE_REQUEST_V3, this);
        if (this.settings.serverTake) {
            Bukkit.getMessenger().registerIncomingPluginChannel(this, Wire.TAKE_REQUEST, this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("RoutineBagkkit enabled; sort=" + this.settings.serverSort + ", store="
                + this.settings.serverStore + ", take=" + this.settings.serverTake);
    }

    @Override
    public void onDisable() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(this);
        this.lastRequestAt.clear();
        this.storeSuccessCache.clear();
        this.takeSuccessCache.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> sendHello(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        this.lastRequestAt.remove(playerId);
        this.storeSuccessCache.remove(playerId);
        this.takeSuccessCache.remove(playerId);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (Wire.SORT_REQUEST.equals(channel)) {
            handleSortRequest(player, message);
        } else if (Wire.STORE_REQUEST_V3.equals(channel)) {
            handleStoreRequestV3(player, message);
        } else if (Wire.TAKE_REQUEST.equals(channel)) {
            handleTakeRequest(player, message);
        }
    }

    private void handleStoreRequestV3(Player player, byte[] message) {
        Wire.StoreRequestV3 request;
        try {
            request = Wire.readStoreRequestV3(message);
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Bad v3 store request from " + player.getName() + ": " + ex.getMessage());
            return;
        }
        CachedStoreResult cached = cachedStoreResult(player.getUniqueId(), request.requestId());
        if (cached != null) {
            sendStoreResultV3(player, cached.request.equals(request)
                    ? cached.result
                    : StoreService.StoreResult.fail("gui.routinebags.status.server_store_failed"), request.requestId());
            return;
        }
        if (!this.settings.serverStore || !allowed(player, "routinebagkkit.store") || isCoolingDown(player)) {
            sendStoreResultV3(player, new StoreService.StoreResult(false, 0, "gui.routinebags.status.server_store_failed"), request.requestId());
            return;
        }
        int containerId = PaperContainerId.current(player);
        if (containerId < 0 || containerId != request.containerId()) {
            sendStoreResultV3(player, StoreService.StoreResult.fail("gui.routinebags.status.server_store_failed"),
                    request.requestId());
            return;
        }
        StoreService.StoreResult result;
        try {
            result = this.storeService.store(player, request, this.settings, containerId);
        } catch (RuntimeException ex) {
            getLogger().warning("V3 store failed for " + player.getName() + ": " + ex.getMessage());
            result = StoreService.StoreResult.fail("gui.routinebags.status.server_store_failed");
        }
        if (result.success()) {
            cacheStoreSuccess(player.getUniqueId(), new CachedStoreResult(request, result));
        }
        sendStoreResultV3(player, result, request.requestId());
    }

    private void handleTakeRequest(Player player, byte[] message) {
        Wire.TakeRequest request;
        try {
            request = Wire.readTakeRequest(message);
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Bad take request from " + player.getName() + ": " + ex.getMessage());
            return;
        }
        CachedTakeResult cached = cachedTakeResult(player.getUniqueId(), request.requestId());
        if (cached != null) {
            sendTakeResult(player, cached.request.equals(request)
                    ? cached.result
                    : TakeService.TakeResult.fail(request.requestId()));
            return;
        }
        if (!this.settings.serverTake || !allowed(player, "routinebagkkit.take") || isCoolingDown(player)) {
            sendTakeResult(player, TakeService.TakeResult.fail(request.requestId()));
            return;
        }
        int containerId = PaperContainerId.current(player);
        if (containerId < 0 || containerId != request.containerId()) {
            sendTakeResult(player, TakeService.TakeResult.fail(request.requestId()));
            return;
        }
        TakeService.TakeResult result;
        try {
            result = this.takeService.take(player, request, this.settings, containerId);
        } catch (RuntimeException ex) {
            getLogger().warning("Take failed for " + player.getName() + ": " + ex.getMessage());
            result = TakeService.TakeResult.fail(request.requestId());
        }
        if (result.success()) {
            cacheTakeSuccess(player.getUniqueId(), new CachedTakeResult(request, result));
        }
        sendTakeResult(player, result);
    }

    private void handleSortRequest(Player player, byte[] message) {
        if (!this.settings.serverSort || !allowed(player, "routinebagkkit.sort") || isCoolingDown(player)) {
            sendSortResult(player, false, 0, "gui.routinebags.status.server_sort_failed");
            return;
        }
        int sortMode;
        try {
            sortMode = Wire.readSortMode(message);
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Bad sort request from " + player.getName() + ": " + ex.getMessage());
            sendSortResult(player, false, 0, "gui.routinebags.status.server_sort_failed");
            return;
        }
        try {
            int itemTypes = this.sorter.sort(player, sortMode);
            sendSortResult(player, true, itemTypes, "gui.routinebags.status.server_sorted");
        } catch (RuntimeException ex) {
            getLogger().warning("Sort failed for " + player.getName() + ": " + ex.getMessage());
            sendSortResult(player, false, 0, "gui.routinebags.status.server_sort_failed");
        }
    }

    private boolean allowed(Player player, String permission) {
        return player.hasPermission("routinebagkkit.use") && player.hasPermission(permission);
    }

    private boolean isCoolingDown(Player player) {
        long now = System.currentTimeMillis();
        long last = this.lastRequestAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < this.settings.cooldownMillis) {
            return true;
        }
        this.lastRequestAt.put(player.getUniqueId(), now);
        return false;
    }

    private void sendHello(Player player) {
        if (player.isOnline()) {
            player.sendPluginMessage(this, Wire.HELLO, Wire.helloPayload(this.settings.serverSort, this.settings.serverStore));
        }
    }

    private void sendSortResult(Player player, boolean success, int moves, String messageKey) {
        player.sendPluginMessage(this, Wire.SORT_RESULT, Wire.sortResult(success, moves, messageKey));
    }

    private void sendStoreResultV3(Player player, StoreService.StoreResult result, int requestId) {
        player.sendPluginMessage(this, Wire.STORE_RESULT_V3,
                Wire.storeResultV3(requestId, result.success(), result.moved(), result.messageKey()));
    }

    private CachedStoreResult cachedStoreResult(UUID playerId, int requestId) {
        Map<Integer, CachedStoreResult> cache = this.storeSuccessCache.get(playerId);
        return cache == null ? null : cache.get(requestId);
    }

    private void cacheStoreSuccess(UUID playerId, CachedStoreResult result) {
        LinkedHashMap<Integer, CachedStoreResult> cache = this.storeSuccessCache.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>());
        cache.put(result.request.requestId(), result);
        trimCache(cache);
    }

    private CachedTakeResult cachedTakeResult(UUID playerId, int requestId) {
        Map<Integer, CachedTakeResult> cache = this.takeSuccessCache.get(playerId);
        return cache == null ? null : cache.get(requestId);
    }

    private void cacheTakeSuccess(UUID playerId, CachedTakeResult result) {
        LinkedHashMap<Integer, CachedTakeResult> cache = this.takeSuccessCache.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>());
        cache.put(result.request.requestId(), result);
        trimCache(cache);
    }

    private static <T> void trimCache(LinkedHashMap<Integer, T> cache) {
        while (cache.size() > MAX_CACHED_REQUESTS) {
            cache.remove(cache.keySet().iterator().next());
        }
    }

    private void sendTakeResult(Player player, TakeService.TakeResult result) {
        player.sendPluginMessage(this, Wire.TAKE_RESULT,
                Wire.takeResult(result.requestId(), result.success(), result.moved(), result.messageKey()));
    }

    private record CachedStoreResult(Wire.StoreRequestV3 request, StoreService.StoreResult result) {}

    private record CachedTakeResult(Wire.TakeRequest request, TakeService.TakeResult result) {}
}
