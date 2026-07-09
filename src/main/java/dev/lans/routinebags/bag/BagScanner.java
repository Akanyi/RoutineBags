package dev.lans.routinebags.bag;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * 从玩家背包里认出所有“袋子”。识别基于数据组件而不是物品 ID 白名单，
 * 这样其他 mod 只要用原版组件存内容就能被自动兼容。
 */
public final class BagScanner {

    public static List<BagView> scan(LocalPlayer player, boolean includeReadOnly) {
        Inventory inv = player.getInventory();
        List<BagView> out = new ArrayList<>();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            recognize(out, inv.getItem(i), i, menuSlotForInv(i), includeReadOnly);
        }
        recognize(out, inv.getItem(Inventory.SLOT_OFFHAND), Inventory.SLOT_OFFHAND, InventoryMenu.SHIELD_SLOT, includeReadOnly);
        return out;
    }

    /**
     * Inventory 下标与 InventoryMenu 槽位是两套编号：快捷栏 0..8 在菜单里是 36..44，
     * 主背包 9..35 两边一致。所有点击都必须用菜单槽位号。
     */
    public static int menuSlotForInv(int invIndex) {
        return invIndex < Inventory.SELECTION_SIZE ? InventoryMenu.USE_ROW_SLOT_START + invIndex : invIndex;
    }

    private static void recognize(List<BagView> out, ItemStack stack, int invIndex, int menuSlot, boolean includeReadOnly) {
        if (stack.isEmpty()) {
            return;
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            // 点击覆写只在 count==1 时生效（BundleItem 的硬性前提），非 1 的按只读处理
            boolean mutable = stack.getCount() == 1;
            if (!mutable && !includeReadOnly) {
                return;
            }
            out.add(new BagView(invIndex, menuSlot, stack.copy(), BagKind.BUNDLE, mutable,
                    bundle.itemCopyStream().toList(), BagView.weightSafe(bundle), bundle.size(), -1));
            return;
        }
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null && includeReadOnly) {
            // 26.1 起箱子/熔炉/置物架等全都自带默认空 container 组件（破坏保留内容）。
            // 空的普通容器是建材不是收纳工具，只有潜影盒类才值得空着也展示。
            List<ItemStack> entries = container.nonEmptyItemCopyStream().toList();
            boolean shulkerLike = BagView.isShulkerLike(stack);
            if (stack.getCount() != 1 || (!shulkerLike && entries.isEmpty())) {
                return;
            }
            out.add(new BagView(invIndex, menuSlot, stack.copy(), BagKind.CONTAINER, false,
                    entries, org.apache.commons.lang3.math.Fraction.ZERO, entries.size(), shulkerLike ? 27 : -1));
        }
    }

    private BagScanner() {}
}
