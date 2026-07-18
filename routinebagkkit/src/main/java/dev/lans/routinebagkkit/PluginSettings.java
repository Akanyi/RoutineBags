package dev.lans.routinebagkkit;

import org.bukkit.configuration.file.FileConfiguration;

final class PluginSettings {
    boolean serverSort;
    boolean serverStore;
    boolean serverTake;
    long cooldownMillis;
    int maxBags;
    int maxItemsPerRequest;

    static PluginSettings load(FileConfiguration config) {
        PluginSettings settings = new PluginSettings();
        settings.serverSort = config.getBoolean("features.serverSort", true);
        settings.serverStore = config.getBoolean("features.serverStore", true);
        settings.serverTake = config.getBoolean("features.serverTake", true);
        settings.cooldownMillis = Math.max(0L, config.getLong("limits.cooldownMillis", 750L));
        settings.maxBags = Math.max(1, config.getInt("limits.maxBags", 36));
        settings.maxItemsPerRequest = Math.max(1, config.getInt("limits.maxItemsPerRequest", 4096));
        return settings;
    }
}
