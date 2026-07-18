package dev.lans.routinebags.client;

import dev.lans.routinebags.RoutineBags;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = RoutineBags.MOD_ID, value = Dist.CLIENT)
public final class ClientTickHandler {

    @SubscribeEvent
    static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ServerBridge.tick();
        RecipeBagSupport.tick();
        while (Keybinds.OPEN_UNIFIED.get().consumeClick()) {
            // IN_GAME 冲突上下文已保证没有屏幕打开，双保险再查一次
            if (mc.player != null && mc.screen == null) {
                mc.setScreen(new UnifiedBagScreen());
            }
        }
        if (mc.screen != null) {
            ContainerMounts.tick(mc.screen);
        }
    }

    private ClientTickHandler() {}
}
