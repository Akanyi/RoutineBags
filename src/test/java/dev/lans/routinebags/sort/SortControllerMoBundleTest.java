package dev.lans.routinebags.sort;

import static org.junit.jupiter.api.Assertions.*;

import dev.lans.routinebags.SortMode;
import dev.lans.routinebags.bag.BagKind;
import dev.lans.routinebags.bag.BagView;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SortControllerMoBundleTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void emeraldCompactionExceedsVanillaCapacityWithoutLosingItems() throws Exception {
        requireMoBundle();
        ItemStack first = bag("emerald_bag", emeralds(64));
        ItemStack second = bag("emerald_bag", emeralds(32));
        List<ItemStack> bags = List.of(first, second);
        SortController controller = new SortController();
        int moves = simulate(controller, bags);
        assertTrue(moves > 0 && moves < 10);
        assertEquals(96, total(bags));
        assertTrue(contents(first).isEmpty() || contents(second).isEmpty());
    }

    @Test
    void applicationEnergyCompactionPreservesSixteenSlotLimit() throws Exception {
        requireMoBundle();
        List<ItemStack> entries = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            ItemStack entry = emeralds(1);
            entry.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("type-" + i));
            entries.add(entry);
        }
        ItemStack first = bag("app_energy", entries.toArray(ItemStack[]::new));
        ItemStack second = bag("app_energy", emeralds(1));
        List<ItemStack> bags = List.of(first, second);
        simulate(new SortController(), bags);
        assertEquals(17, total(bags));
        assertFalse(contents(first).isEmpty());
        assertFalse(contents(second).isEmpty());
        assertTrue(contents(first).size() <= 16 && contents(second).size() <= 16);
    }

    @Test
    void partialEntriesInOneSpecializedBagAreMerged() throws Exception {
        requireMoBundle();
        ItemStack bag = bag("emerald_bag", emeralds(12), emeralds(20));
        simulate(new SortController(), List.of(bag));
        assertEquals(1, contents(bag).size());
        assertEquals(32, contents(bag).items().getFirst().count());
    }

    private static int simulate(SortController controller, List<ItemStack> stacks) throws Exception {
        Class<?> storage = Class.forName("dev.lans.mobundle.bundle.BundleStorage");
        var insert = storage.getMethod("insert", ItemStack.class, ItemStack.class, int.class,
                net.minecraft.server.level.ServerLevel.class);
        int originalTotal = total(stacks);
        for (int moves = 0; moves < 50; moves++) {
            List<BagView> bags = new ArrayList<>();
            for (int index = 0; index < stacks.size(); index++) {
                ItemStack stack = stacks.get(index);
                BundleContents contents = contents(stack);
                bags.add(new BagView(index, 36 + index, stack.copy(), BagKind.BUNDLE, true,
                        contents.itemCopyStream().toList(), BagView.weightSafe(contents), contents.size(), -1));
            }
            SortController.Move move = controller.planSpecialized(bags, SortMode.BY_COUNT);
            if (move == null) return moves;
            ItemStack source = stacks.get(move.src().invIndex);
            ItemStack destination = stacks.get(move.dst().invIndex);
            BundleContents.Mutable mutable = new BundleContents.Mutable(contents(source));
            mutable.toggleSelectedItem(move.entryIdx());
            ItemStack extracted = mutable.removeOne();
            source.set(DataComponents.BUNDLE_CONTENTS, mutable.toImmutable());
            Object result = insert.invoke(null, destination.copy(), extracted.copy(), extracted.getCount(), null);
            int inserted = (int) result.getClass().getMethod("inserted").invoke(result);
            assertTrue(inserted > 0, "规划出的搬运必须被实际 moBundle 准入规则接受");
            destination.set(DataComponents.BUNDLE_CONTENTS,
                    (BundleContents) result.getClass().getMethod("contents").invoke(result));
            if (inserted < extracted.getCount()) {
                ItemStack remainder = extracted.copyWithCount(extracted.getCount() - inserted);
                Object returned = insert.invoke(null, source.copy(), remainder, remainder.getCount(), null);
                assertEquals(remainder.getCount(), returned.getClass().getMethod("inserted").invoke(returned));
                source.set(DataComponents.BUNDLE_CONTENTS,
                        (BundleContents) returned.getClass().getMethod("contents").invoke(returned));
            }
            assertEquals(originalTotal, total(stacks), "每一步都必须守恒");
        }
        fail("整理未在 50 步内收敛");
        return -1;
    }

    private static void requireMoBundle() {
        try {
            Class.forName("dev.lans.mobundle.bundle.BundleStorage");
        } catch (ClassNotFoundException e) {
            Assumptions.abort("联动用例通过 -PtestMoBundle=<现成 JAR> 启用");
        }
    }

    private static BundleContents contents(ItemStack bag) {
        return bag.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
    }

    private static int total(List<ItemStack> bags) {
        return bags.stream().flatMap(bag -> contents(bag).itemCopyStream()).mapToInt(ItemStack::getCount).sum();
    }

    private static ItemStack emeralds(int count) {
        return new ItemStack(Holder.direct(Items.EMERALD,
                DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build()), count);
    }

    private static ItemStack bag(String type, ItemStack... entries) {
        ItemStack bag = new ItemStack(Holder.direct(Items.BUNDLE, DataComponentMap.builder()
                .set(DataComponents.MAX_STACK_SIZE, 1).set(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).build()));
        ResourceKey<Enchantment> key = ResourceKey.create(Registries.ENCHANTMENT,
                Identifier.fromNamespaceAndPath("mobundle", type));
        Holder.Reference<Enchantment> holder = Holder.Reference.createStandAlone(new HolderOwner<>() {}, key);
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(holder, 1);
        bag.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
        bag.set(DataComponents.BUNDLE_CONTENTS,
                new BundleContents(List.of(entries).stream().map(ItemStackTemplate::fromNonEmptyStack).toList()));
        return bag;
    }
}
