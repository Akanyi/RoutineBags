package dev.lans.routinebags.network;

import dev.lans.routinebags.RoutineBags;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = RoutineBags.MOD_ID)
public final class ServerConnectionEvents {
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            RoutineBagsNetwork.sendHello(player);
        }
    }

    private ServerConnectionEvents() {}
}
