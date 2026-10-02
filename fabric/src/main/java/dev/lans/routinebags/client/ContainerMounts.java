package dev.lans.routinebags.client;

import java.lang.reflect.Field;

import dev.lans.routinebags.ClientConfig;
import dev.lans.routinebags.mixin.client.AbstractContainerScreenAccessor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;

public final class ContainerMounts {
    private static final Field RECIPE_BOOK = field(AbstractRecipeBookScreen.class, "recipeBookComponent");
    private static MountedBagPanel activePanel;

    public static MountedBagPanel create(AbstractContainerScreen<?> screen) {
        if (!ClientConfig.MOUNT_IN_CONTAINER_SCREENS.get() || screen instanceof CreativeModeInventoryScreen) {
            return null;
        }
        activePanel = new MountedBagPanel(screen.getMenu());
        return activePanel;
    }

    public static void tick(AbstractContainerScreen<?> screen, MountedBagPanel panel) {
        if (panel == null) {
            return;
        }
        panel.setSuppressed(recipeBookVisible(screen));
        layout(screen, panel);
        panel.tick();
    }

    public static void render(AbstractContainerScreen<?> screen, MountedBagPanel panel,
                              GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (panel == null) {
            return;
        }
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
        panel.setSuppressed(recipeBookVisible(screen));
        layout(screen, panel);
        graphics.pose().pushMatrix();
        graphics.pose().translate(-accessor.routinebags$getLeftPos(), -accessor.routinebags$getTopPos());
        panel.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.pose().popMatrix();
    }

    public static boolean mouseClicked(MountedBagPanel panel, MouseButtonEvent event, boolean doubleClick) {
        return panel != null && panel.mouseClicked(event, doubleClick);
    }

    public static boolean mouseReleased(MountedBagPanel panel, MouseButtonEvent event) {
        return panel != null && panel.consumeMouseReleased(event.x(), event.y(), event.button());
    }

    public static boolean mouseDragged(MountedBagPanel panel, MouseButtonEvent event) {
        return panel != null && panel.consumeMouseDragged(event.x(), event.y(), event.button());
    }

    public static boolean mouseScrolled(MountedBagPanel panel, double x, double y, double scrollX, double scrollY) {
        return panel != null && panel.mouseScrolled(x, y, scrollX, scrollY);
    }

    public static boolean keyPressed(AbstractContainerScreen<?> screen, MountedBagPanel panel, KeyEvent event) {
        if (panel == null) {
            return false;
        }
        layout(screen, panel);
        panel.setSuppressed(recipeBookVisible(screen));
        return panel.handleToggleKey(event.key(), event.scancode(), event.modifiers());
    }

    public static void cleanup(MountedBagPanel panel) {
        if (panel != null) {
            panel.cleanup();
        }
        if (activePanel == panel) {
            activePanel = null;
        }
    }

    public static boolean hasActiveOperation(AbstractContainerMenu menu) {
        return activePanel != null && activePanel.isMountedTo(menu) && activePanel.hasActiveOperation();
    }

    public static boolean isMouseOver(Screen screen, double x, double y) {
        return screen instanceof AbstractContainerScreen<?> container && activePanel != null
                && activePanel.isMountedTo(container.getMenu()) && activePanel.isMouseOver(x, y);
    }

    private static void layout(AbstractContainerScreen<?> screen, MountedBagPanel panel) {
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
        panel.layout(screen.width, screen.height,
                accessor.routinebags$getLeftPos(), accessor.routinebags$getTopPos(),
                accessor.routinebags$getImageWidth(), accessor.routinebags$getImageHeight());
    }

    private static boolean recipeBookVisible(AbstractContainerScreen<?> screen) {
        if (!(screen instanceof AbstractRecipeBookScreen<?>) || RECIPE_BOOK == null) {
            return false;
        }
        try {
            return ((RecipeBookComponent<?>) RECIPE_BOOK.get(screen)).isVisible();
        } catch (IllegalAccessException ignored) {
            return false;
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

    private ContainerMounts() {}
}
