package dev.lans.routinebags.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class ClientTickHandler {
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ServerBridge.tick();
            RecipeBagSupport.tick();
            while (Keybinds.OPEN_UNIFIED.get().consumeClick()) {
                if (client.player != null && client.screen == null) {
                    client.setScreen(new UnifiedBagScreen());
                }
            }
        });
    }

    private ClientTickHandler() {}
}
