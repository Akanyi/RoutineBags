package dev.lans.routinebags.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import dev.lans.routinebags.ClientConfig;
import dev.lans.routinebags.RoutineBags;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = RoutineBags.MOD_ID, value = Dist.CLIENT)
public final class ContainerMounts {
    private static final Map<Screen, MountedBagPanel> PANELS = new WeakHashMap<>();
    private static final Field LEFT_POS = field(AbstractContainerScreen.class, "leftPos");
    private static final Field TOP_POS = field(AbstractContainerScreen.class, "topPos");
    private static final Field IMAGE_WIDTH = field(AbstractContainerScreen.class, "imageWidth");
    private static final Field IMAGE_HEIGHT = field(AbstractContainerScreen.class, "imageHeight");
    private static final Field RENDERABLES = field(Screen.class, "renderables");

    @SubscribeEvent
    static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!ClientConfig.MOUNT_IN_CONTAINER_SCREENS.get()) {
            return;
        }
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> containerScreen) || isUnsupported(containerScreen)) {
            return;
        }
        MountedBagPanel panel = new MountedBagPanel(containerScreen.getMenu());
        PANELS.put(containerScreen, panel);
        layoutPanel(containerScreen, panel);
        event.addListener(panel);
        renderables(containerScreen).remove(panel);
    }

    @SubscribeEvent
    static void onContainerForeground(ContainerScreenEvent.Render.Foreground event) {
        AbstractContainerScreen<?> screen = event.getContainerScreen();
        MountedBagPanel panel = PANELS.get(screen);
        if (panel != null) {
            panel.setSuppressed(recipeBookVisible(screen));
            int left = intField(screen, LEFT_POS, 0);
            int top = intField(screen, TOP_POS, 0);
            event.getGuiGraphics().pose().pushMatrix();
            event.getGuiGraphics().pose().translate(-left, -top);
            panel.extractRenderState(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), 0.0F);
            event.getGuiGraphics().pose().popMatrix();
        }
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
        if (panel != null && panel.isLayoutAvailable() && !recipeBookVisible(event.getScreen())
                && panel.matchesToggleKey(event.getKeyCode(), event.getScanCode(), event.getModifiers())) {
            panel.toggleOpen();
            if (event.getScreen() instanceof AbstractContainerScreen<?> containerScreen) {
                layoutPanel(containerScreen, panel);
            }
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        MountedBagPanel panel = PANELS.get(event.getScreen());
        if (panel != null && panel.consumeMouseReleased(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        MountedBagPanel panel = PANELS.get(event.getScreen());
        if (panel != null && panel.consumeMouseDragged(event.getMouseX(), event.getMouseY(), event.getMouseButton())) {
            event.setCanceled(true);
        }
    }

    public static void tick(Screen screen) {
        MountedBagPanel panel = PANELS.get(screen);
        if (panel != null) {
            panel.setSuppressed(recipeBookVisible(screen));
            if (screen instanceof AbstractContainerScreen<?> containerScreen) {
                layoutPanel(containerScreen, panel);
            }
            panel.tick();
        }
    }

    public static boolean hasActiveOperation(AbstractContainerMenu menu) {
        for (MountedBagPanel panel : PANELS.values()) {
            if (panel.isMountedTo(menu) && panel.hasActiveOperation()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnsupported(AbstractContainerScreen<?> screen) {
        return screen instanceof CreativeModeInventoryScreen;
    }

    private static boolean recipeBookVisible(Screen screen) {
        try {
            Method getter = screen.getClass().getMethod("getRecipeBookComponent");
            Object component = getter.invoke(screen);
            return component != null && (boolean) component.getClass().getMethod("isVisible").invoke(component);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static void layoutPanel(AbstractContainerScreen<?> screen, MountedBagPanel panel) {
        int imageW = intField(screen, IMAGE_WIDTH, 176);
        int imageH = intField(screen, IMAGE_HEIGHT, 166);
        int left = intField(screen, LEFT_POS, (screen.width - imageW) / 2);
        int top = intField(screen, TOP_POS, (screen.height - imageH) / 2);
        panel.layout(screen.width, screen.height, left, top, imageW, imageH);
    }

    @SuppressWarnings("unchecked")
    private static List<Renderable> renderables(Screen screen) {
        if (RENDERABLES == null) {
            return List.of();
        }
        try {
            return (List<Renderable>) RENDERABLES.get(screen);
        } catch (IllegalAccessException ignored) {
            return List.of();
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
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

    private ContainerMounts() {}
}
