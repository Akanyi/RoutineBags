package dev.lans.routinebags.merge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.lans.routinebags.SortMode;
import dev.lans.routinebags.bag.BagView;
import net.minecraft.world.item.ItemStack;

/**
 * 跨袋聚合：同种物品（ItemKey 相同）合并成一个条目，总数不设 64 上限——
 * 这就是“聚合显示型超堆叠”。每个条目记着来源分布，tooltip 和取出操作都靠它。
 */
public final class AggregatedIndex {

    /** 某条目在某个袋子里的分布 */
    public record Source(int bagOrdinal, int count, boolean mutable) {}

    public static final class Entry {
        public final ItemKey key;
        public final ItemStack display;
        public long total;
        public final List<Source> sources = new ArrayList<>();
        public boolean anyMutable;
        final String searchText;

        Entry(ItemKey key) {
            this.key = key;
            this.display = key.proto();
            this.searchText = (this.display.getHoverName().getString() + " "
                    + this.display.typeHolder().getRegisteredName()).toLowerCase(Locale.ROOT);
        }

        public boolean matchesQuery(String lowerQuery) {
            return lowerQuery.isEmpty() || this.searchText.contains(lowerQuery);
        }
    }

    public static List<Entry> build(List<BagView> bags, SortMode mode) {
        Map<ItemKey, Entry> byKey = new LinkedHashMap<>();
        for (int ordinal = 0; ordinal < bags.size(); ordinal++) {
            BagView bag = bags.get(ordinal);
            // 同袋内同种物品可能碎成多个条目（bundle 是 LIFO 列表），聚合前先按袋内合计
            Map<ItemKey, Integer> inBag = new LinkedHashMap<>();
            for (ItemStack stack : bag.entries) {
                if (!stack.isEmpty()) {
                    inBag.merge(ItemKey.of(stack), stack.getCount(), Integer::sum);
                }
            }
            for (Map.Entry<ItemKey, Integer> e : inBag.entrySet()) {
                Entry entry = byKey.computeIfAbsent(e.getKey(), Entry::new);
                entry.total += e.getValue();
                entry.sources.add(new Source(ordinal, e.getValue(), bag.mutable));
                entry.anyMutable |= bag.mutable;
            }
        }
        List<Entry> list = new ArrayList<>(byKey.values());
        list.sort(comparator(mode));
        return list;
    }

    public static Comparator<Entry> comparator(SortMode mode) {
        Comparator<Entry> byId = Comparator.comparing(e -> e.display.typeHolder().getRegisteredName());
        return switch (mode) {
            case BY_CREATIVE -> Comparator.<Entry>comparingInt(e -> dev.lans.routinebags.client.CreativeOrder.orderOf(e.display)).thenComparing(byId);
            case BY_ID -> byId;
            case BY_NAME -> Comparator.<Entry, String>comparing(e -> e.display.getHoverName().getString()).thenComparing(byId);
            case BY_COUNT -> Comparator.<Entry>comparingLong(e -> -e.total).thenComparing(byId);
        };
    }

    /** 大数字缩写：格子里画不下 “114514”，画 “114k” */
    public static String formatCount(long n) {
        if (n < 10000) {
            return Long.toString(n);
        }
        if (n < 1000000) {
            long k10 = n / 100; // 保留一位小数
            return k10 % 10 == 0 ? (k10 / 10) + "k" : (k10 / 10) + "." + (k10 % 10) + "k";
        }
        return (n / 1000000) + "M";
    }

    private AggregatedIndex() {}
}
