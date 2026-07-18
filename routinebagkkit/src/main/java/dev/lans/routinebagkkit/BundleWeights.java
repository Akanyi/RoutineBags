package dev.lans.routinebagkkit;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import java.math.BigInteger;
import java.util.List;
import org.bukkit.Tag;
import org.bukkit.block.Beehive;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

final class BundleWeights {
    private static final Fraction ONE = Fraction.of(1, 1);
    private static final Fraction NESTED_BUNDLE_OVERHEAD = Fraction.of(1, 16);
    private static final int MAX_NESTING_DEPTH = 64;

    static boolean canStore(ItemStack stack) {
        return !isEmpty(stack) && stack.getData(DataComponentTypes.BUNDLE_CONTENTS) == null
                && !Tag.ITEMS_SHULKER_BOXES.isTagged(stack.getType());
    }

    static int maxInsertable(List<ItemStack> contents, ItemStack stack, int limit) {
        if (limit <= 0 || isEmpty(stack)) {
            return 0;
        }
        Fraction used = contentsWeight(contents, 0);
        if (used.compareTo(ONE) >= 0) {
            return 0;
        }
        BigInteger fit = ONE.subtract(used).divideFloor(itemWeight(stack, 0));
        return fit.min(BigInteger.valueOf(limit)).intValue();
    }

    static int compareContents(List<ItemStack> left, List<ItemStack> right) {
        return contentsWeight(left, 0).compareTo(contentsWeight(right, 0));
    }

    private static Fraction contentsWeight(List<ItemStack> contents, int depth) {
        Fraction total = Fraction.ZERO;
        for (ItemStack stack : contents) {
            if (!isEmpty(stack)) {
                total = total.add(itemWeight(stack, depth).multiply(stack.getAmount()));
            }
        }
        return total;
    }

    private static Fraction itemWeight(ItemStack stack, int depth) {
        if (depth > MAX_NESTING_DEPTH) {
            throw new IllegalArgumentException("Bundle nesting is too deep");
        }
        BundleContents nested = stack.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (nested != null) {
            return NESTED_BUNDLE_OVERHEAD.add(contentsWeight(nested.contents(), depth + 1));
        }
        if (hasBees(stack)) {
            return ONE;
        }
        return Fraction.of(1, Math.max(1, stack.getMaxStackSize()));
    }

    private static boolean hasBees(ItemStack stack) {
        if (!Tag.BEEHIVES.isTagged(stack.getType()) || !(stack.getItemMeta() instanceof BlockStateMeta meta)) {
            return false;
        }
        return meta.getBlockState() instanceof Beehive beehive && beehive.getEntityCount() > 0;
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty() || stack.getAmount() <= 0;
    }

    private record Fraction(BigInteger numerator, BigInteger denominator) implements Comparable<Fraction> {
        private static final Fraction ZERO = of(0, 1);

        private Fraction {
            if (denominator.signum() <= 0) {
                throw new IllegalArgumentException("Fraction denominator must be positive");
            }
            BigInteger divisor = numerator.gcd(denominator);
            numerator = numerator.divide(divisor);
            denominator = denominator.divide(divisor);
        }

        static Fraction of(int numerator, int denominator) {
            return new Fraction(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
        }

        Fraction add(Fraction other) {
            return new Fraction(this.numerator.multiply(other.denominator).add(other.numerator.multiply(this.denominator)),
                    this.denominator.multiply(other.denominator));
        }

        Fraction subtract(Fraction other) {
            return new Fraction(this.numerator.multiply(other.denominator).subtract(other.numerator.multiply(this.denominator)),
                    this.denominator.multiply(other.denominator));
        }

        Fraction multiply(int value) {
            return new Fraction(this.numerator.multiply(BigInteger.valueOf(value)), this.denominator);
        }

        BigInteger divideFloor(Fraction other) {
            return this.numerator.multiply(other.denominator).divide(this.denominator.multiply(other.numerator));
        }

        @Override
        public int compareTo(Fraction other) {
            return this.numerator.multiply(other.denominator).compareTo(other.numerator.multiply(this.denominator));
        }
    }

    private BundleWeights() {}
}
