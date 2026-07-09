package dev.lans.routinebagkkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

public final class RoutineBagkkitPlugin extends JavaPlugin implements Listener, PluginMessageListener {
    private final SortService sorter = new SortService();

    @Override
    public void onEnable() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, Wire.HELLO);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, Wire.SORT_RESULT);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, Wire.SORT_REQUEST, this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("RoutineBagkkit enabled; listening on " + Wire.SORT_REQUEST);
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
        if (!Wire.SORT_REQUEST.equals(channel)) {
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

    private void sendHello(Player player) {
        if (player.isOnline()) {
            player.sendPluginMessage(this, Wire.HELLO, Wire.helloPayload());
        }
    }

    private void sendSortResult(Player player, boolean success, int moves, String messageKey) {
        player.sendPluginMessage(this, Wire.SORT_RESULT, Wire.sortResult(success, moves, messageKey));
    }
}
