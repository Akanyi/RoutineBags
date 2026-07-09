package dev.lans.routinebags.bag;

import java.util.List;

import org.apache.commons.lang3.math.Fraction;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Bees;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/**
 * 一个收纳袋在某个瞬间的快照。entries 的下标即 bundle 的条目索引，
 * 交互层选中条目时直接用它，所以这里绝不能对 entries 重排序。
 */
public final class BagView {
    /** 袋子容量的分数分母基准：原版 bundle 满容量 = 1，展示时换算成 64 单位 */
    public static final int DISPLAY_UNITS = 64;

    public final int invIndex;
    public final int menuSlot;
    public final ItemStack bagStack;
    public final BagKind kind;
    public final boolean mutable;
    public final List<ItemStack> entries;
    public final Fraction weightUsed;
    public final int slotsUsed;
    public final int slotCapacity;

    public BagView(int invIndex, int menuSlot, ItemStack bagStack, BagKind kind, boolean mutable,
            List<ItemStack> entries, Fraction weightUsed, int slotsUsed, int slotCapacity) {
        this.invIndex = invIndex;
        this.menuSlot = menuSlot;
        this.bagStack = bagStack;
        this.kind = kind;
        this.mutable = mutable;
        this.entries = entries;
        this.weightUsed = weightUsed;
        this.slotsUsed = slotsUsed;
        this.slotCapacity = slotCapacity;
    }

    public Component displayName() {
        return this.bagStack.getHoverName();
    }

    /** 已用容量（0..1），容器物品按槽位折算，用于容量条渲染 */
    public float fillFraction() {
        if (this.kind == BagKind.BUNDLE) {
            return Math.min(this.weightUsed.floatValue(), 1.0F);
        }
        return this.slotCapacity > 0 ? Math.min((float) this.slotsUsed / this.slotCapacity, 1.0F) : 0.0F;
    }

    public int usedUnits() {
        return Mth.mulAndTruncate(this.weightUsed, DISPLAY_UNITS);
    }

    /**
     * 指定物品还能塞进多少个。与原版 BundleContents.Mutable#getMaxAmountToAdd
     * 用同一套 Fraction 运算，保证和服务器判定一字不差。
     */
    public int maxInsertable(ItemStack stack) {
        if (this.kind != BagKind.BUNDLE || !this.mutable || !BundleContents.canItemBeInBundle(stack)) {
            return 0;
        }
        Fraction free = Fraction.ONE.subtract(this.weightUsed);
        return Math.max(free.divideBy(unitWeight(stack)).intValue(), 0);
    }

    /**
     * 单个物品的重量。逻辑复刻自 BundleContents#getWeight（那是 private 的）：
     * 嵌套 bundle = 内容重量 + 1/16，带蜂的蜂箱视为占满，其余为 1/最大堆叠。
     */
    public static Fraction unitWeight(ItemStack stack) {
        BundleContents nested = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (nested != null) {
            return weightSafe(nested).add(Fraction.getFraction(1, 16));
        }
        Bees bees = stack.get(DataComponents.BEES);
        if (bees != null && !bees.bees().isEmpty()) {
            return Fraction.ONE;
        }
        return Fraction.getFraction(1, stack.getMaxStackSize());
    }

    /** weight() 溢出报错时按占满处理，与 BundleItem#getWeightSafe 的口径一致 */
    public static Fraction weightSafe(BundleContents contents) {
        return contents.weight().result().orElse(Fraction.ONE);
    }

    public static boolean isShulkerLike(ItemStack stack) {
        return stack.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock;
    }
}
