package dev.lans.routinebags.interact;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import org.jspecify.annotations.Nullable;

/**
 * 交互原语。上层统一使用 InventoryMenu 的玩家背包槽位编号；这里在发包前
 * 翻译到当前打开菜单里的真实槽位。这样开着箱子/工作台时也只会点玩家背包部分，
 * 不会把容器槽位当成袋子误操作。
 */
public final class InvOps {

    public static boolean leftClick(int menuSlot) {
        return click(menuSlot, 0);
    }

    public static boolean rightClick(int menuSlot) {
        return click(menuSlot, 1);
    }

    private static boolean click(int menuSlot, int button) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) {
            return false;
        }
        AbstractContainerMenu menu = mc.player.containerMenu;
        int activeSlot = activeSlotForMenuSlot(menuSlot);
        if (activeSlot == -1) {
            return false;
        }
        mc.gameMode.handleContainerInput(menu.containerId, activeSlot, button, ContainerInput.PICKUP, mc.player);
        return true;
    }

    /**
     * 选中 bundle 的指定条目（决定下一次右键取出哪个）。
     * 镜像 BundleMouseActions 的做法：本地先 toggle 预测，再发包。
     * toggle 语义是“同索引再选=取消”，所以已选中时必须跳过，否则客户端和服务器会反向。
     */
    public static boolean selectBundleEntry(int menuSlot, int entryIndex) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        if (mc.player == null || conn == null) {
            return false;
        }
        AbstractContainerMenu menu = mc.player.containerMenu;
        int activeSlot = activeSlotForMenuSlot(menuSlot);
        if (activeSlot == -1) {
            return false;
        }
        ItemStack bundle = menu.getSlot(activeSlot).getItem();
        if (bundle.get(DataComponents.BUNDLE_CONTENTS) == null) {
            return false;
        }
        if (BundleItem.getSelectedItemIndex(bundle) == entryIndex) {
            return true;
        }
        BundleItem.toggleSelectedItem(bundle, entryIndex);
        conn.send(new ServerboundSelectBundleItemPacket(activeSlot, entryIndex));
        return true;
    }

    public static ItemStack carried() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? ItemStack.EMPTY : mc.player.containerMenu.getCarried();
    }

    public static ItemStack stackAt(int menuSlot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return ItemStack.EMPTY;
        }
        int activeSlot = activeSlotForMenuSlot(menuSlot);
        if (activeSlot != -1) {
            return mc.player.containerMenu.getSlot(activeSlot).getItem();
        }
        if (menuSlot >= 0 && menuSlot < mc.player.inventoryMenu.slots.size()) {
            return mc.player.inventoryMenu.getSlot(menuSlot).getItem();
        }
        return ItemStack.EMPTY;
    }

    public static @Nullable BundleContents bundleAt(int menuSlot) {
        return stackAt(menuSlot).get(DataComponents.BUNDLE_CONTENTS);
    }

    public static boolean canReachSlot(int menuSlot) {
        return activeSlotForMenuSlot(menuSlot) != -1;
    }

    public static boolean hasReachablePlayerInventory() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        for (int slot = InventoryMenu.INV_SLOT_START; slot < InventoryMenu.USE_ROW_SLOT_END; slot++) {
            if (activeSlotForMenuSlot(slot) != -1) {
                return true;
            }
        }
        return false;
    }

    private static int activeSlotForMenuSlot(int menuSlot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return -1;
        }
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == mc.player.inventoryMenu) {
            return isValidSlot(menu, menuSlot) ? menuSlot : -1;
        }
        int invIndex = invIndexForMenuSlot(menuSlot);
        if (invIndex == -1) {
            return -1;
        }
        Inventory inv = mc.player.getInventory();
        for (Slot slot : menu.slots) {
            if (slot.container == inv && slot.getContainerSlot() == invIndex && slot.isActive()) {
                return slot.index;
            }
        }
        return -1;
    }

    private static int invIndexForMenuSlot(int menuSlot) {
        if (menuSlot >= InventoryMenu.INV_SLOT_START && menuSlot < InventoryMenu.INV_SLOT_END) {
            return menuSlot;
        }
        if (menuSlot >= InventoryMenu.USE_ROW_SLOT_START && menuSlot < InventoryMenu.USE_ROW_SLOT_END) {
            return menuSlot - InventoryMenu.USE_ROW_SLOT_START;
        }
        if (menuSlot == InventoryMenu.SHIELD_SLOT) {
            return Inventory.SLOT_OFFHAND;
        }
        return -1;
    }

    private static boolean isValidSlot(AbstractContainerMenu menu, int slot) {
        return slot >= 0 && slot < menu.slots.size();
    }

    private InvOps() {}
}
