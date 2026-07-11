package dev.lans.routinebags;

import dev.lans.routinebags.client.ClientTickHandler;
import dev.lans.routinebags.client.Keybinds;
import dev.lans.routinebags.network.RoutineBagsNetwork;
import net.fabricmc.api.ClientModInitializer;

public final class RoutineBagsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientConfig.load();
        Keybinds.register();
        RoutineBagsNetwork.register();
        ClientTickHandler.register();
    }
}
