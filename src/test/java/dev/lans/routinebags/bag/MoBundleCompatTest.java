package dev.lans.routinebags.bag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.food.Foods;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

class MoBundleCompatTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void ordinaryBundlesWorkWithoutTheOptionalMod() {
        ItemStack bag = bundle(null);
        BagView view = view(bag);
        assertTrue(MoBundleCompat.supportsItems(bag));
        assertFalse(MoBundleCompat.isSpecialized(bag));
        assertEquals(64, view.capacityUnits());
        assertEquals(64, view.maxInsertable(stack(Items.STONE, 1)));
    }

    @Test
    void emeraldCapacityAndAdmissionComeFromTheInstalledMod() {
        requireMoBundle();
        ItemStack bag = bundle("emerald_bag");
        setContents(bag, stack(Items.EMERALD, 64));
        BagView view = view(bag);
        assertEquals(1024, view.capacityUnits());
        assertEquals(64, view.usedUnits());
        assertEquals(0.0625F, view.fillFraction(), 0.00001F);
        assertEquals(64, view.maxInsertable(stack(Items.EMERALD, 1)));
        assertEquals(0, view.maxInsertable(stack(Items.STONE, 1)));
        assertEquals(64, bag.get(DataComponents.BUNDLE_CONTENTS).items().getFirst().count());
    }

    @Test
    void gluttonSupportsTwoStacksButRejectsNonFood() {
        requireMoBundle();
        ItemStack bag = bundle("glutton");
        ItemStack food = stack(Items.APPLE, 64);
        food.set(DataComponents.FOOD, Foods.APPLE);
        setContents(bag, food);
        BagView view = view(bag);
        assertEquals(128, view.capacityUnits());
        assertEquals(0.5F, view.fillFraction(), 0.00001F);
        assertEquals(64, view.maxInsertable(food));
        assertEquals(0, view.maxInsertable(stack(Items.STONE, 1)));
    }

    @Test
    void applicationEnergySlotLimitIsRespectedBeforeSendingClicks() {
        requireMoBundle();
        ItemStack bag = bundle("app_energy");
        List<ItemStack> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            ItemStack item = stack(Items.STONE, 1);
            item.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("type-" + i));
            entries.add(item);
        }
        setContents(bag, entries.toArray(ItemStack[]::new));
        assertEquals(1024, view(bag).capacityUnits());
        assertEquals(0, view(bag).maxInsertable(stack(Items.DIRT, 1)));
        assertTrue(view(bag).maxInsertable(entries.getFirst()) > 0);
    }

    @Test
    void sharedEnderSnapshotIsReadWithoutTouchingPhysicalContents() throws Exception {
        requireMoBundle();
        ItemStack bag = bundle("ender_bag");
        assertFalse(MoBundleCompat.supportsItems(bag));
        setMoComponent(bag, "ENDER_CHANNEL", "minecraft:dirt|minecraft:stone|minecraft:stone");
        BundleContents contents = contents(stack(Items.DIAMOND, 12));
        setMoComponent(bag, "ENDER_CONTENTS", contents);
        assertTrue(MoBundleCompat.supportsItems(bag));
        assertSame(contents, MoBundleCompat.contents(bag));
        assertEquals(128, view(bag).capacityUnits());
        assertFalse(MoBundleCompat.canCompact(bag));
        assertNotNull(MoBundleCompat.sharedChannel(bag));
        assertTrue(bag.get(DataComponents.BUNDLE_CONTENTS).isEmpty());
    }

    @Test
    void sharedPortsAreCountedOnceAndWritablePortsWin() throws Exception {
        requireMoBundle();
        ItemStack first = bundle("ender_bag");
        setMoComponent(first, "ENDER_CHANNEL", "minecraft:dirt|minecraft:stone|minecraft:stone");
        setMoComponent(first, "ENDER_CONTENTS", contents(stack(Items.DIAMOND, 12)));
        ItemStack second = first.copy();
        first.setCount(2);
        List<BagView> bags = new java.util.ArrayList<>();
        BagScanner.recognize(bags, first, 0, 36, true);
        BagScanner.recognize(bags, second, 1, 37, true);
        assertEquals(1, bags.size());
        assertTrue(bags.getFirst().mutable);
        assertEquals(37, bags.getFirst().menuSlot);
        assertEquals(12, bags.getFirst().entries.getFirst().getCount());
    }

    @Test
    void resourceBagsAndOccupiedSacksDoNotBecomeItemInventories() {
        requireMoBundle();
        for (String type : List.of("experience_bag", "air_bag", "portal_bag")) {
            ItemStack bag = bundle(type);
            assertFalse(MoBundleCompat.supportsItems(bag));
            assertEquals(0, MoBundleCompat.maxInsertable(bag, stack(Items.STONE, 1)));
        }
        ItemStack sack = bundle("sack");
        assertTrue(MoBundleCompat.supportsItems(sack));
        sack.set(DataComponents.ENTITY_DATA, TypedEntityData.of(EntityType.PIG, new CompoundTag()));
        assertFalse(MoBundleCompat.supportsItems(sack));
        assertFalse(MoBundleCompat.canCompact(sack));
    }

    private static void requireMoBundle() {
        boolean installed;
        try {
            Class.forName("dev.lans.mobundle.enchantment.BagSpecialization");
            installed = true;
        } catch (ClassNotFoundException e) {
            installed = false;
        }
        Assumptions.assumeTrue(installed, "联动用例通过 -PtestMoBundle=<现成 JAR> 启用");
    }

    private static ItemStack bundle(String type) {
        ItemStack bag = new ItemStack(Holder.direct(Items.BUNDLE, DataComponentMap.builder()
                .set(DataComponents.MAX_STACK_SIZE, 1).set(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).build()));
        if (type != null) {
            ResourceKey<Enchantment> key = ResourceKey.create(Registries.ENCHANTMENT,
                    Identifier.fromNamespaceAndPath("mobundle", type));
            Holder.Reference<Enchantment> holder = Holder.Reference.createStandAlone(new HolderOwner<>() {}, key);
            ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            enchantments.set(holder, 1);
            bag.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
        }
        return bag;
    }

    private static ItemStack stack(Item item, int count) {
        return new ItemStack(Holder.direct(item, DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build()), count);
    }

    private static BundleContents contents(ItemStack... stacks) {
        return new BundleContents(List.of(stacks).stream().map(ItemStackTemplate::fromNonEmptyStack).toList());
    }

    private static void setContents(ItemStack bag, ItemStack... stacks) {
        bag.set(DataComponents.BUNDLE_CONTENTS, contents(stacks));
    }

    @SuppressWarnings("unchecked")
    private static <T> void setMoComponent(ItemStack bag, String name, T value) throws Exception {
        Object deferred = Class.forName("dev.lans.mobundle.registry.ModDataComponents").getField(name).get(null);
        bag.set((DataComponentType<T>) ((java.util.function.Supplier<?>) deferred).get(), value);
    }

    private static BagView view(ItemStack bag) {
        BundleContents contents = MoBundleCompat.contents(bag);
        return new BagView(0, 36, bag, BagKind.BUNDLE, true, contents.itemCopyStream().toList(),
                BagView.weightSafe(contents), contents.size(), -1);
    }
}
