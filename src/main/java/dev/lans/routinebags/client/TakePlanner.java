package dev.lans.routinebags.client;

import dev.lans.routinebags.bag.BagKind;
import dev.lans.routinebags.bag.BagScanner;
import dev.lans.routinebags.bag.BagView;
import dev.lans.routinebags.merge.ItemKey;
import dev.lans.routinebags.network.ItemIdentity;
import dev.lans.routinebags.network.RoutineBagsNetwork.TakeTarget;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

public final class TakePlanner {
    public static List<TakeTarget> plan(ItemKey key, int requested) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || requested <= 0) {
            return List.of();
        }
        List<TakeTarget> targets = new ArrayList<>();
        int remaining = requested;
        for (BagView bag : BagScanner.scan(minecraft.player, false)) {
            if (bag.kind != BagKind.BUNDLE || !bag.mutable) {
                continue;
            }
            for (int entryIndex = 0; entryIndex < bag.entries.size() && remaining > 0; entryIndex++) {
                ItemStack entry = bag.entries.get(entryIndex);
                if (!key.matches(entry)) {
                    continue;
                }
                int amount = Math.min(remaining, entry.getCount());
                byte[] expectedHash = ItemIdentity.hash(entry, minecraft.level.registryAccess());
                if (expectedHash.length != ItemIdentity.HASH_SIZE) {
                    return List.of();
                }
                targets.add(new TakeTarget(bag.menuSlot, entryIndex, amount, expectedHash));
                remaining -= amount;
            }
            if (remaining == 0) {
                break;
            }
        }
        if (remaining != 0 || targets.size() > dev.lans.routinebags.network.RoutineBagsNetwork.TakeRequestPayload.MAX_TARGETS) {
            return List.of();
        }
        targets.sort(Comparator.comparingInt(TakeTarget::bagMenuSlot)
                .thenComparing(Comparator.comparingInt(TakeTarget::entryIndex).reversed()));
        return List.copyOf(targets);
    }

    private TakePlanner() {}
}
