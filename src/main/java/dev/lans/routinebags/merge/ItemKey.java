package dev.lans.routinebags.merge;

import net.minecraft.world.item.ItemStack;

/**
 * 聚合用的物品身份：物品 + 全部组件（不含数量）。
 * 直接复用原版的 isSameItemSameComponents / hashItemAndComponents，
 * 保证“什么算同一种物品”与游戏本体判定完全一致（附魔、耐久、自定义名都会分开）。
 */
public final class ItemKey {
    private final ItemStack proto;
    private final int hash;

    private ItemKey(ItemStack proto) {
        this.proto = proto;
        this.hash = ItemStack.hashItemAndComponents(proto);
    }

    public static ItemKey of(ItemStack stack) {
        return new ItemKey(stack.copyWithCount(1));
    }

    public ItemStack proto() {
        return this.proto;
    }

    public boolean matches(ItemStack stack) {
        return ItemStack.isSameItemSameComponents(this.proto, stack);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ItemKey other && ItemStack.isSameItemSameComponents(this.proto, other.proto);
    }

    @Override
    public int hashCode() {
        return this.hash;
    }
}
