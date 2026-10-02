package dev.lans.routinebags.bag;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Locale;

import dev.lans.routinebags.RoutineBags;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.apache.commons.lang3.math.Fraction;
import org.jspecify.annotations.Nullable;

/** 只查询和模拟 moBundle 的规则，实际存取仍走它自己的容器点击。 */
public final class MoBundleCompat {
    private static final @Nullable Api API = loadApi();

    public static @Nullable String specialization(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BundleItem)) return null;
        if (stack.has(DataComponents.ENTITY_DATA)
                || API != null && Boolean.TRUE.equals(invoke(API.occupied, stack))) return "SACK_OCCUPIED";
        Object found = API == null ? null : invoke(API.find, stack);
        if (found instanceof Optional<?> optional && optional.orElse(null) instanceof Enum<?> value) return value.name();
        // 接口版本不匹配时仍认出专精标记，避免退回原版容量后误写特殊库存。
        for (var entry : stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet()) {
            var key = entry.getKey().unwrapKey().orElse(null);
            if (entry.getIntValue() > 0 && key != null && key.identifier().getNamespace().equals("mobundle")) {
                return key.identifier().getPath().toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    public static boolean isSpecialized(ItemStack stack) {
        return specialization(stack) != null;
    }

    public static boolean supportsItems(ItemStack stack) {
        String type = specialization(stack);
        if (type == null) return true;
        if (API == null) return false;
        return switch (type) {
            case "GLUTTON", "TOOL_BAG", "EMERALD_BAG", "APP_ENERGY", "COMPRESSION", "SACK" -> true;
            case "ENDER_BAG" -> sharedChannel(stack) != null;
            default -> false;
        };
    }

    public static @Nullable BundleContents contents(ItemStack stack) {
        if ("ENDER_BAG".equals(specialization(stack)) && API != null) {
            Object contents = invoke(API.visible, stack);
            return contents instanceof BundleContents value ? value : null;
        }
        return stack.get(DataComponents.BUNDLE_CONTENTS);
    }

    public static @Nullable String sharedChannel(ItemStack stack) {
        if (API == null) return null;
        Object channel = invoke(API.channel, stack);
        return channel instanceof String value ? value : null;
    }

    public static float fullness(ItemStack stack) {
        Object result = API == null ? null : invoke(API.fullness, stack);
        return result instanceof Fraction value ? Math.clamp(value.floatValue(), 0.0F, 1.0F) : 1.0F;
    }

    public static int usedCapacity(ItemStack stack) {
        Object result = API == null ? null : invoke(API.used, stack);
        return result instanceof Integer value ? value : 0;
    }

    public static int capacity(ItemStack stack) {
        if (API == null) return 64;
        Object found = invoke(API.find, stack);
        Object type = found instanceof Optional<?> optional ? optional.orElse(null) : null;
        Object result = type == null ? null : invoke(API.capacity, stack, type);
        return result instanceof Integer value ? value : 64;
    }

    public static int maxInsertable(ItemStack bag, ItemStack source) {
        String type = specialization(bag);
        if (type == null || API == null || !supportsItems(bag) || bag.getCount() != 1) return 0;
        // 压缩结果依赖服务端配方；只承诺未压缩时装得下的量，点击后交给服务端压缩。
        if ("COMPRESSION".equals(type) || "SACK".equals(type)) {
            BundleContents contents = contents(bag);
            return contents == null || !BundleContents.canItemBeInBundle(source) ? 0
                    : Math.max(Fraction.ONE.subtract(BagView.weightSafe(contents))
                            .divideBy(BagView.unitWeight(source)).intValue(), 0);
        }
        Object result = invoke(API.insert, bag.copy(), source.copyWithCount(source.getMaxStackSize()),
                source.getMaxStackSize(), null);
        Object count = result == null ? null : invokeOn(API.inserted, result);
        return count instanceof Integer value ? value : 0;
    }

    public static boolean canCompact(ItemStack stack) {
        String type = specialization(stack);
        // 共享端口不是独立库存，压缩袋搬运会改变身份，不能参与恒定总量的整理规划。
        return supportsItems(stack) && !"ENDER_BAG".equals(type) && !"COMPRESSION".equals(type);
    }

    private static @Nullable Object invoke(Method method, Object... args) {
        return invokeOn(method, null, args);
    }

    private static @Nullable Object invokeOn(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static @Nullable Api loadApi() {
        try {
            Class<?> specialization = Class.forName("dev.lans.mobundle.enchantment.BagSpecialization");
            Class<?> storage = Class.forName("dev.lans.mobundle.bundle.BundleStorage");
            Class<?> ender = Class.forName("dev.lans.mobundle.bundle.EnderBagStorage");
            Class<?> result = Class.forName("dev.lans.mobundle.bundle.BundleStorage$InsertResult");
            Class<?> level = Class.forName("net.minecraft.server.level.ServerLevel");
            return new Api(specialization.getMethod("find", ItemStack.class),
                    Class.forName("dev.lans.mobundle.bundle.SackEntities").getMethod("isOccupied", ItemStack.class),
                    storage.getMethod("fullness", ItemStack.class), storage.getMethod("usedCapacity", ItemStack.class),
                    storage.getMethod("maximumCapacity", ItemStack.class, specialization),
                    storage.getMethod("insert", ItemStack.class, ItemStack.class, int.class, level),
                    result.getMethod("inserted"), ender.getMethod("visibleContents", ItemStack.class),
                    ender.getMethod("channel", ItemStack.class));
        } catch (ClassNotFoundException e) {
            return null;
        } catch (ReflectiveOperationException | LinkageError e) {
            RoutineBags.LOGGER.warn("moBundle 兼容接口不可用", e);
            return null;
        }
    }

    private record Api(Method find, Method occupied, Method fullness, Method used, Method capacity,
                       Method insert, Method inserted, Method visible, Method channel) {}

    private MoBundleCompat() {}
}
