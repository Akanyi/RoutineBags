package dev.lans.routinebags.client;

import java.util.HashMap;
import java.util.Map;

import dev.lans.routinebags.merge.ItemKey;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 创造模式搜索页的全物品顺序缓存——玩家心里的“创造顺序”就是它。
 * 标签页内容是惰性构建的（要 FeatureFlag + 注册表上下文），首次用到时触发重建；
 * tryRebuildTabContents 自带参数缓存，没变化就是空转，每 tick 调也不心疼。
 */
public final class CreativeOrder {

    private static final Map<ItemKey, Integer> BY_KEY = new HashMap<>();
    private static final Map<Item, Integer> BY_ITEM = new HashMap<>();
    private static boolean built;

    /** 越小越靠前；不在创造清单里的（如命令方块）排最后 */
    public static int orderOf(ItemStack stack) {
        ensureBuilt();
        Integer exact = BY_KEY.get(ItemKey.of(stack));
        if (exact != null) {
            return exact;
        }
        // 带组件的变体（附魔书、药水等）找不到精确匹配时退回物品本体的位置
        Integer byItem = BY_ITEM.get(stack.getItem());
        return byItem != null ? byItem : Integer.MAX_VALUE;
    }

    private static void ensureBuilt() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        boolean rebuilt = CreativeModeTabs.tryRebuildTabContents(
                mc.level.enabledFeatures(), mc.player.canUseGameMasterBlocks(), mc.level.registryAccess());
        if (built && !rebuilt) {
            return;
        }
        BY_KEY.clear();
        BY_ITEM.clear();
        int i = 0;
        for (ItemStack stack : CreativeModeTabs.searchTab().getDisplayItems()) {
            BY_KEY.putIfAbsent(ItemKey.of(stack), i);
            BY_ITEM.putIfAbsent(stack.getItem(), i);
            i++;
        }
        built = true;
    }

    private CreativeOrder() {}
}
