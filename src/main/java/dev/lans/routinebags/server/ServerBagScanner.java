package dev.lans.routinebags.server;

import java.util.ArrayList;
import java.util.List;

import dev.lans.routinebags.bag.BagKind;
import dev.lans.routinebags.bag.BagView;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;

final class ServerBagScanner {
    static List<BagView> scanMutableBundles(ServerPlayer player) {
        Inventory inv = player.getInventory();
        List<BagView> out = new ArrayList<>();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            recognize(out, inv.getItem(i), i, menuSlotForInv(i));
        }
        recognize(out, inv.getItem(Inventory.SLOT_OFFHAND), Inventory.SLOT_OFFHAND, InventoryMenu.SHIELD_SLOT);
        return out;
    }

    static int menuSlotForInv(int invIndex) {
        if (invIndex == Inventory.SLOT_OFFHAND) {
            return InventoryMenu.SHIELD_SLOT;
        }
        return invIndex < Inventory.SELECTION_SIZE ? InventoryMenu.USE_ROW_SLOT_START + invIndex : invIndex;
    }

    private static void recognize(List<BagView> out, ItemStack stack, int invIndex, int menuSlot) {
        if (stack.isEmpty() || stack.getCount() != 1) {
            return;
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            out.add(new BagView(invIndex, menuSlot, stack.copy(), BagKind.BUNDLE, true,
                    bundle.itemCopyStream().toList(), BagView.weightSafe(bundle), bundle.size(), -1));
        }
    }

    private ServerBagScanner() {}
}
