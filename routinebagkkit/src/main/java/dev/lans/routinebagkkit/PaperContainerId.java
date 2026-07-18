package dev.lans.routinebagkkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.entity.Player;

final class PaperContainerId {
    private static final Method GET_HANDLE = method("org.bukkit.craftbukkit.entity.CraftPlayer", "getHandle");
    private static final Field CONTAINER_MENU = field("net.minecraft.world.entity.player.Player", "containerMenu");
    private static final Field CONTAINER_ID = field("net.minecraft.world.inventory.AbstractContainerMenu", "containerId");

    static int current(Player player) {
        if (GET_HANDLE == null || CONTAINER_MENU == null || CONTAINER_ID == null) {
            return -1;
        }
        try {
            Object handle = GET_HANDLE.invoke(player);
            Object menu = CONTAINER_MENU.get(handle);
            return CONTAINER_ID.getInt(menu);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return -1;
        }
    }

    private static Method method(String owner, String name) {
        try {
            Method method = Class.forName(owner).getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private static Field field(String owner, String name) {
        try {
            Field field = Class.forName(owner).getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private PaperContainerId() {}
}
