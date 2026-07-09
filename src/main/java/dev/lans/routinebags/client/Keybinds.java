package dev.lans.routinebags.client;

import com.mojang.blaze3d.platform.InputConstants;

import dev.lans.routinebags.RoutineBags;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.util.Lazy;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = RoutineBags.MOD_ID, value = Dist.CLIENT)
public final class Keybinds {

    public static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(RoutineBags.MOD_ID, "main"));

    // Lazy 包一层：KeyMapping 的构造会碰 GL 侧静态状态，等注册事件真来了再实例化
    public static final Lazy<KeyMapping> OPEN_UNIFIED = Lazy.of(() -> new KeyMapping(
            "key.routinebags.open_unified",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            CATEGORY));

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(OPEN_UNIFIED.get());
    }

    private Keybinds() {}
}
