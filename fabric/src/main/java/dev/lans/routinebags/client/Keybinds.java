package dev.lans.routinebags.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lans.routinebags.RoutineBags;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.function.Supplier;

public final class Keybinds {
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(RoutineBags.MOD_ID, "main"));

    private static final KeyMapping OPEN_UNIFIED_MAPPING = new KeyMapping(
            "key.routinebags.open_unified",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            CATEGORY);
    public static final Supplier<KeyMapping> OPEN_UNIFIED = () -> OPEN_UNIFIED_MAPPING;

    public static void register() {
        KeyMappingHelper.registerKeyMapping(OPEN_UNIFIED_MAPPING);
    }

    private Keybinds() {}
}
