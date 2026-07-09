package dev.lans.routinebags.client;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

import dev.lans.routinebags.ClientConfig;
import dev.lans.routinebags.RoutineBags;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = RoutineBags.MOD_ID, value = Dist.CLIENT)
public final class ContainerMounts {
    private static final Map<Screen, MountedBagPanel> PANELS = new WeakHashMap<>();
    private static final Field LEFT_POS = field("leftPos");
    private static final Field TOP_POS = field("topPos");
    private static final Field IMAGE_WIDTH = field("imageWidth");
    private static final Field IMAGE_HEIGHT = field("imageHeight");

    @SubscribeEvent
    static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!ClientConfig.MOUNT_IN_CONTAINER_SCREENS.get()) {
            return;
        }
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        MountedBagPanel panel = new MountedBagPanel(containerScreen.getMenu());
        panel.layout(containerScreen.width, containerScreen.height, intField(containerScreen, LEFT_POS, centeredLeft(containerScreen)),
                intField(containerScreen, TOP_POS, centeredTop(containerScreen)), intField(containerScreen, IMAGE_WIDTH, 176),
                intField(containerScreen, IMAGE_HEIGHT, 166));
        PANELS.put(containerScreen, panel);
        event.addListener(panel);
    }

    @SubscribeEvent
    static void onScreenClosing(ScreenEvent.Closing event) {
        MountedBagPanel panel = PANELS.remove(event.getScreen());
        if (panel != null) {
            panel.cleanup();
        }
    }

    @SubscribeEvent
    static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        MountedBagPanel panel = PANELS.get(event.getScreen());
        if (panel != null && panel.matchesToggleKey(event.getKeyCode(), event.getScanCode(), event.getModifiers())) {
            panel.toggleOpen();
            event.setCanceled(true);
        }
    }

    public static void tick(Screen screen) {
        MountedBagPanel panel = PANELS.get(screen);
        if (panel != null) {
            if (screen instanceof AbstractContainerScreen<?> containerScreen) {
                panel.layout(containerScreen.width, containerScreen.height, intField(containerScreen, LEFT_POS, centeredLeft(containerScreen)),
                        intField(containerScreen, TOP_POS, centeredTop(containerScreen)), intField(containerScreen, IMAGE_WIDTH, 176),
                        intField(containerScreen, IMAGE_HEIGHT, 166));
            }
            panel.tick();
        }
    }

    private static Field field(String name) {
        try {
            Field field = AbstractContainerScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static int intField(AbstractContainerScreen<?> screen, Field field, int fallback) {
        if (field == null) {
            return fallback;
        }
        try {
            return field.getInt(screen);
        } catch (IllegalAccessException ignored) {
            return fallback;
        }
    }

    private static int centeredLeft(AbstractContainerScreen<?> screen) {
        return (screen.width - 176) / 2;
    }

    private static int centeredTop(AbstractContainerScreen<?> screen) {
        return (screen.height - 166) / 2;
    }

    private ContainerMounts() {}
}
