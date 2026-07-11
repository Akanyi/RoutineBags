package dev.lans.routinebagkkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RoutineBagkkitPlugin extends JavaPlugin implements Listener, PluginMessageListener {
    private final SortService sorter = new SortService();
    private final StoreService storeService = new StoreService();
    private final Map<UUID, Long> lastRequestAt = new HashMap<>();
    private PluginSettings settings;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.settings = PluginSettings.load(getConfig());
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, Wire.HELLO);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, Wire.SORT_RESULT);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, Wire.STORE_RESULT);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, Wire.SORT_REQUEST, this);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, Wire.STORE_REQUEST, this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("RoutineBagkkit enabled; sort=" + this.settings.serverSort + ", store=" + this.settings.serverStore);
    }

    @Override
    public void onDisable() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> sendHello(event.getPlayer()), 20L);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (Wire.SORT_REQUEST.equals(channel)) {
            handleSortRequest(player, message);
        } else if (Wire.STORE_REQUEST.equals(channel)) {
            handleStoreRequest(player, message);
        }
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

    private void handleStoreRequest(Player player, byte[] message) {
        if (!this.settings.serverStore || !allowed(player, "routinebagkkit.store") || isCoolingDown(player)) {
            sendStoreResult(player, false, 0, "gui.routinebags.status.server_store_failed");
            return;
        }
        int sourceSlot;
        try {
            sourceSlot = Wire.readStoreSlot(message);
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Bad store request from " + player.getName() + ": " + ex.getMessage());
            sendStoreResult(player, false, 0, "gui.routinebags.status.server_store_failed");
            return;
        }
        try {
            StoreService.StoreResult result = this.storeService.store(player, sourceSlot, this.settings);
            sendStoreResult(player, result.success(), result.moved(), result.messageKey());
        } catch (RuntimeException ex) {
            getLogger().warning("Store failed for " + player.getName() + ": " + ex.getMessage());
            sendStoreResult(player, false, 0, "gui.routinebags.status.server_store_failed");
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

    private void sendStoreResult(Player player, boolean success, int moved, String messageKey) {
        player.sendPluginMessage(this, Wire.STORE_RESULT, Wire.storeResult(success, moved, messageKey));
    }
}
