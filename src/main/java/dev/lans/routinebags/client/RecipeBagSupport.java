package dev.lans.routinebags.client;

import dev.lans.routinebags.ClientConfig;
import dev.lans.routinebags.bag.BagKind;
import dev.lans.routinebags.bag.BagScanner;
import dev.lans.routinebags.bag.BagView;
import dev.lans.routinebags.merge.ItemKey;
import dev.lans.routinebags.interact.InvOps;
import dev.lans.routinebags.interact.Moves;
import dev.lans.routinebags.interact.StepRunner;
import dev.lans.routinebags.network.RoutineBagsNetwork.TakeRequestPayload;
import dev.lans.routinebags.network.RoutineBagsNetwork.TakeTarget;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

public final class RecipeBagSupport {
    private static PendingRecipe pending;
    private static final StepRunner localRunner = new StepRunner();

    public static void extendCraftable(RecipeCollection collection, StackedItemContents ignoredOriginalContents,
            Predicate<net.minecraft.world.item.crafting.display.RecipeDisplay> selector) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(minecraft.player.containerMenu instanceof AbstractCraftingMenu craftingMenu)) {
            return;
        }
        if (craftingMenu.getInputGridSlots().stream().anyMatch(slot -> slot.hasItem())) {
            return;
        }
        List<BagView> bags = craftingBags();
        if (bags.isEmpty()) {
            return;
        }
        StackedItemContents combined = new StackedItemContents();
        minecraft.player.getInventory().fillStackedContents(combined);
        craftingMenu.fillCraftSlotsStackedContents(combined);
        for (BagView bag : bags) {
            for (ItemStack entry : bag.entries) {
                combined.accountSimpleStack(entry);
            }
        }
        for (RecipeDisplayEntry entry : collection.getRecipes()) {
            if (selector.test(entry.display()) && !collection.isCraftable(entry.id())
                    && safeRequirements(entry) != null && entry.canCraft(combined)) {
                ((RecipeCollectionAccess) collection).routinebags$markCraftable(entry.id());
            }
        }
    }

    public static boolean interceptPlace(RecipeCollection collection, RecipeDisplayId recipe, boolean useMaxItems) {
        Minecraft minecraft = Minecraft.getInstance();
        if (pending != null) {
            return true;
        }
        if (minecraft.player == null
                || !(minecraft.player.containerMenu instanceof AbstractCraftingMenu craftingMenu)) {
            return false;
        }
        return startPlace(craftingMenu, findEntry(collection, recipe), useMaxItems);
    }

    /**
     * 可选客户端模组可通过反射调用这个入口，不需要在编译期依赖 Routine Bags。
     */
    public static boolean canCraftWithBags(AbstractCraftingMenu craftingMenu, RecipeDisplayEntry display) {
        Minecraft minecraft = Minecraft.getInstance();
        if (pending != null || ServerBridge.hasOperationInFlight()
                || ContainerMounts.hasActiveOperation(craftingMenu) || !canStartPlace(minecraft, craftingMenu)) {
            return false;
        }
        List<Ingredient> requirements = safeRequirements(display);
        if (requirements == null || requirements.isEmpty()) {
            return false;
        }
        ExtractionPlan plan = planExtraction(requirements, 1);
        return plan != null && !plan.missing.isEmpty();
    }

    /**
     * 开始异步补料；成功启动后由 tick() 在材料同步完成时发送原版配方放置请求。
     */
    public static boolean placeRecipeWithBags(AbstractCraftingMenu craftingMenu, RecipeDisplayEntry display,
            boolean useMaxItems) {
        if (pending != null) {
            return false;
        }
        return startPlace(craftingMenu, display, useMaxItems);
    }

    private static boolean startPlace(AbstractCraftingMenu craftingMenu, RecipeDisplayEntry display,
            boolean useMaxItems) {
        Minecraft minecraft = Minecraft.getInstance();
        if (ServerBridge.hasOperationInFlight() || ContainerMounts.hasActiveOperation(craftingMenu)
                || !canStartPlace(minecraft, craftingMenu)) {
            return false;
        }
        List<Ingredient> requirements = safeRequirements(display);
        if (requirements == null || requirements.isEmpty()) {
            return false;
        }
        if (canCraftFromInventory(requirements) && !useMaxItems) {
            return false;
        }
        ExtractionPlan plan = bestExtractionPlan(requirements, useMaxItems);
        if (plan == null || plan.missing.isEmpty()) {
            return false;
        }
        if (!plan.serverTargets.isEmpty()) {
            List<TakeTarget> targets = new ArrayList<>(plan.serverTargets);
            targets.sort(java.util.Comparator.comparingInt(TakeTarget::bagMenuSlot)
                    .thenComparing(java.util.Comparator.comparingInt(TakeTarget::entryIndex).reversed()));
            int requestId = ServerBridge.requestTake(TakeRequestPayload.DESTINATION_INVENTORY, targets);
            if (requestId >= 0) {
                pending = new PendingRecipe(requestId, minecraft.player.containerMenu, display.id(), requirements,
                        useMaxItems, 120);
                return true;
            }
            return false;
        }
        if (plan.localTargets.isEmpty()) {
            return false;
        }
        localRunner.clear();
        for (LocalTarget target : plan.localTargets) {
            Moves.bundleAmountToInventory(localRunner, target.menuSlot, target.entryIndex, target.key, target.amount);
        }
        pending = new PendingRecipe(-1, minecraft.player.containerMenu, display.id(), requirements,
                useMaxItems, 0);
        return true;
    }

    private static boolean canStartPlace(Minecraft minecraft, AbstractCraftingMenu craftingMenu) {
        return minecraft.player != null && minecraft.gameMode != null
                && minecraft.player.containerMenu == craftingMenu
                && minecraft.player.containerMenu.getCarried().isEmpty()
                && craftingMenu.getInputGridSlots().stream().noneMatch(slot -> slot.hasItem());
    }

    public static boolean isBusy() {
        return pending != null || localRunner.busy();
    }

    private static ExtractionPlan bestExtractionPlan(List<Ingredient> requirements, boolean useMaxItems) {
        int crafts = useMaxItems ? maximumCrafts(requirements) : 1;
        for (int amount = crafts; amount >= 1; amount--) {
            ExtractionPlan plan = planExtraction(requirements, amount);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    private static ExtractionPlan planExtraction(List<Ingredient> requirements, int crafts) {
        if (choose(requirements, inventoryAvailable(), crafts) != null) {
            return new ExtractionPlan(Map.of(), List.of(), List.of());
        }
        List<ItemKey> chosen = chooseItems(requirements, crafts);
        if (chosen == null) {
            return null;
        }
        Map<ItemKey, Integer> missing = missingFromInventory(chosen);
        if (missing.isEmpty()) {
            return new ExtractionPlan(missing, List.of(), List.of());
        }
        if (ServerBridge.canTakeOnServer()) {
            List<TakeTarget> targets = planTakeTargets(missing);
            return !targets.isEmpty() && canDeliverExact(missing)
                    ? new ExtractionPlan(missing, targets, List.of()) : null;
        }
        List<LocalTarget> targets = planLocal(missing);
        return !targets.isEmpty() && canDeliverLocal(targets)
                ? new ExtractionPlan(missing, List.of(), targets) : null;
    }

    private static boolean canDeliverExact(Map<ItemKey, Integer> missing) {
        List<ItemStack> inventory = inventorySnapshot();
        for (Map.Entry<ItemKey, Integer> entry : missing.entrySet()) {
            if (!deliver(inventory, entry.getKey(), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean canDeliverLocal(List<LocalTarget> targets) {
        List<ItemStack> inventory = inventorySnapshot();
        for (LocalTarget target : targets) {
            if (!deliver(inventory, target.key, target.amount)) {
                return false;
            }
        }
        return true;
    }

    private static List<ItemStack> inventorySnapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return List.of();
        }
        return minecraft.player.getInventory().getNonEquipmentItems().stream()
                .map(ItemStack::copy)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static boolean deliver(List<ItemStack> inventory, ItemKey key, int amount) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        int remaining = amount;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.isStackable() && key.matches(stack)) {
                int max = minecraft.player.getInventory().getMaxStackSize(stack);
                int moved = Math.min(remaining, max - stack.getCount());
                stack.grow(moved);
                remaining -= moved;
            }
        }
        for (int i = 0; i < inventory.size() && remaining > 0; i++) {
            if (inventory.get(i).isEmpty()) {
                int max = minecraft.player.getInventory().getMaxStackSize(key.proto());
                int moved = Math.min(remaining, max);
                inventory.set(i, key.proto().copyWithCount(moved));
                remaining -= moved;
            }
        }
        return remaining == 0;
    }

    public static void tick() {
        if (pending == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null) {
            ServerBridge.cancelTakeRequest(pending.requestId);
            localRunner.clear();
            pending = null;
            return;
        }
        if (pending.cancelling) {
            finishLocalCancellation();
            return;
        }
        if (minecraft.player.containerMenu != pending.menu) {
            if (pending.requestId >= 0) {
                ServerBridge.cancelTakeRequest(pending.requestId);
                pending = null;
            } else {
                pending.cancelling = true;
                localRunner.cancelSafely(net.minecraft.network.chat.Component.translatable("gui.routinebags.status.aborted"));
                finishLocalCancellation();
            }
            return;
        }
        if (pending.requestId >= 0) {
            var result = ServerBridge.takeTakeResult(pending.requestId);
            if (result != null) {
                PendingRecipe ready = pending;
                pending = null;
                if (result.success() && canFinishPlace(minecraft, ready)) {
                    minecraft.gameMode.handlePlaceRecipe(ready.menu.containerId, ready.recipe, ready.useMaxItems);
                }
                return;
            }
            if (--pending.ticksLeft <= 0) {
                ServerBridge.cancelTakeRequest(pending.requestId);
                pending = null;
            }
            return;
        }

        localRunner.tick(ClientConfig.OPS_PER_TICK.get(), ClientConfig.STEP_DELAY_TICKS.get());
        if (localRunner.takeAbortMessage() != null) {
            pending = null;
            return;
        }
        if (!localRunner.busy()) {
            PendingRecipe ready = pending;
            pending = null;
            if (canFinishPlace(minecraft, ready)) {
                minecraft.gameMode.handlePlaceRecipe(ready.menu.containerId, ready.recipe, ready.useMaxItems);
            }
        }
    }

    private static void finishLocalCancellation() {
        localRunner.tick(ClientConfig.OPS_PER_TICK.get(), ClientConfig.STEP_DELAY_TICKS.get());
        if (localRunner.takeAbortMessage() != null || !localRunner.busy()) {
            pending = null;
        }
    }

    private static boolean canFinishPlace(Minecraft minecraft, PendingRecipe ready) {
        return minecraft.player != null && minecraft.player.containerMenu == ready.menu
                && ready.menu.getCarried().isEmpty()
                && ready.menu instanceof AbstractCraftingMenu craftingMenu
                && craftingMenu.getInputGridSlots().stream().noneMatch(slot -> slot.hasItem())
                && canCraftFromInventory(ready.requirements);
    }

    private static RecipeDisplayEntry findEntry(RecipeCollection collection, RecipeDisplayId id) {
        for (RecipeDisplayEntry entry : collection.getRecipes()) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    private static List<Ingredient> safeRequirements(RecipeDisplayEntry entry) {
        if (entry == null || entry.craftingRequirements().isEmpty()) {
            return null;
        }
        List<Ingredient> requirements = entry.craftingRequirements().get();
        for (Ingredient ingredient : requirements) {
            // Fabric only exposes vanilla ingredients; NeoForge custom ingredients can be component-sensitive.
            try {
                var isCustom = Ingredient.class.getMethod("isCustom");
                if ((boolean) isCustom.invoke(ingredient)) {
                    return null;
                }
            } catch (NoSuchMethodException ignored) {
                // Vanilla/Fabric ingredients are holder-set based and safe for this planner.
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
        return requirements;
    }

    private static boolean canCraftFromInventory(List<Ingredient> requirements) {
        return canAssign(requirements, inventoryAvailable());
    }

    private static LinkedHashMap<ItemKey, Integer> inventoryAvailable() {
        LinkedHashMap<ItemKey, Integer> available = new LinkedHashMap<>();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            for (ItemStack stack : minecraft.player.getInventory().getNonEquipmentItems()) {
                account(available, stack);
            }
        }
        return available;
    }

    private static List<ItemKey> chooseItems(List<Ingredient> requirements, int crafts) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || crafts <= 0) {
            return null;
        }
        LinkedHashMap<ItemKey, Integer> available = new LinkedHashMap<>();
        for (ItemStack stack : minecraft.player.getInventory().getNonEquipmentItems()) {
            account(available, stack);
        }
        for (BagView bag : craftingBags()) {
            for (ItemStack stack : bag.entries) {
                account(available, stack);
            }
        }
        return choose(requirements, available, crafts);
    }

    private static int maximumCrafts(List<Ingredient> requirements) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return 1;
        }
        LinkedHashMap<ItemKey, Integer> available = new LinkedHashMap<>();
        for (ItemStack stack : minecraft.player.getInventory().getNonEquipmentItems()) {
            account(available, stack);
        }
        for (BagView bag : craftingBags()) {
            for (ItemStack entry : bag.entries) {
                account(available, entry);
            }
        }
        int low = 1;
        int high = maxIngredientStackSize(requirements);
        int result = 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (choose(requirements, new LinkedHashMap<>(available), middle) != null) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result;
    }

    private static int maxIngredientStackSize(List<Ingredient> requirements) {
        int maxCrafts = Integer.MAX_VALUE;
        for (Ingredient ingredient : requirements) {
            int ingredientMax = ingredient.items()
                    .mapToInt(item -> item.value().getDefaultMaxStackSize())
                    .max()
                    .orElse(0);
            if (ingredientMax == 0) {
                return 1;
            }
            maxCrafts = Math.min(maxCrafts, ingredientMax);
        }
        return maxCrafts == Integer.MAX_VALUE ? 1 : maxCrafts;
    }

    private static List<LocalTarget> planLocal(Map<ItemKey, Integer> missing) {
        List<LocalTarget> targets = new ArrayList<>();
        List<BagView> bags = craftingBags().stream().filter(bag -> InvOps.canReachSlot(bag.menuSlot)).toList();
        for (Map.Entry<ItemKey, Integer> need : missing.entrySet()) {
            int remaining = need.getValue();
            for (BagView bag : bags) {
                for (int index = 0; index < bag.entries.size() && remaining > 0; index++) {
                    ItemStack entry = bag.entries.get(index);
                    if (need.getKey().matches(entry)) {
                        int amount = Math.min(remaining, entry.getCount());
                        targets.add(new LocalTarget(bag.menuSlot, index, need.getKey(), amount));
                        remaining -= amount;
                    }
                }
                if (remaining <= 0) {
                    break;
                }
            }
            if (remaining > 0) {
                return List.of();
            }
        }
        targets.sort(java.util.Comparator.comparingInt((LocalTarget target) -> target.menuSlot)
                .thenComparing(java.util.Comparator.comparingInt((LocalTarget target) -> target.entryIndex).reversed()));
        return List.copyOf(targets);
    }

    private static List<TakeTarget> planTakeTargets(Map<ItemKey, Integer> missing) {
        List<TakeTarget> targets = new ArrayList<>();
        for (Map.Entry<ItemKey, Integer> entry : missing.entrySet()) {
            List<TakeTarget> planned = TakePlanner.plan(entry.getKey(), entry.getValue());
            if (planned.isEmpty() || targets.size() + planned.size() > TakeRequestPayload.MAX_TARGETS) {
                return List.of();
            }
            targets.addAll(planned);
        }
        return targets;
    }

    private static boolean assign(List<Ingredient> requirements, int index, List<ItemKey> candidates,
            Map<ItemKey, Integer> available, List<ItemKey> chosen, int amount) {
        if (index == requirements.size()) {
            return true;
        }
        Ingredient ingredient = requirements.get(index);
        for (ItemKey candidate : candidates) {
            int count = available.getOrDefault(candidate, 0);
            if (count < amount || amount > candidate.proto().getMaxStackSize()
                    || !ingredient.acceptsItem(candidate.proto().typeHolder())) {
                continue;
            }
            available.put(candidate, count - amount);
            int chosenStart = chosen.size();
            for (int i = 0; i < amount; i++) {
                chosen.add(candidate);
            }
            if (assign(requirements, index + 1, candidates, available, chosen, amount)) {
                return true;
            }
            while (chosen.size() > chosenStart) {
                chosen.removeLast();
            }
            available.put(candidate, count);
        }
        return false;
    }

    private static Map<ItemKey, Integer> missingFromInventory(List<ItemKey> chosen) {
        Map<ItemKey, Integer> needs = new LinkedHashMap<>();
        for (ItemKey key : chosen) {
            needs.merge(key, 1, Integer::sum);
        }
        for (ItemStack stack : Minecraft.getInstance().player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty() || !Inventory.isUsableForCrafting(stack)) {
                continue;
            }
            ItemKey key = ItemKey.of(stack);
            int need = needs.getOrDefault(key, 0);
            if (need > 0) {
                int left = need - Math.min(need, stack.getCount());
                if (left == 0) {
                    needs.remove(key);
                } else {
                    needs.put(key, left);
                }
            }
        }
        return needs;
    }

    private static int inventoryCount(ItemKey key) {
        int count = 0;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return 0;
        }
        for (ItemStack stack : minecraft.player.getInventory().getNonEquipmentItems()) {
            if (Inventory.isUsableForCrafting(stack) && key.matches(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void account(Map<ItemKey, Integer> available, ItemStack stack) {
        if (!stack.isEmpty() && Inventory.isUsableForCrafting(stack)) {
            available.merge(ItemKey.of(stack), stack.getCount(), Integer::sum);
        }
    }

    private static boolean canAssign(List<Ingredient> requirements, Map<ItemKey, Integer> available) {
        return choose(requirements, new LinkedHashMap<>(available)) != null;
    }

    private static List<ItemKey> choose(List<Ingredient> requirements, Map<ItemKey, Integer> available) {
        return choose(requirements, available, 1);
    }

    private static List<ItemKey> choose(List<Ingredient> requirements, Map<ItemKey, Integer> available, int amount) {
        List<ItemKey> candidates = new ArrayList<>(available.keySet());
        candidates.sort((left, right) -> Integer.compare(inventoryCount(right), inventoryCount(left)));
        List<ItemKey> chosen = new ArrayList<>();
        return assign(requirements, 0, candidates, available, chosen, amount) ? List.copyOf(chosen) : null;
    }

    private static List<BagView> craftingBags() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return List.of();
        }
        boolean serverTake = ServerBridge.canTakeOnServer();
        return BagScanner.scan(minecraft.player, false).stream()
                .filter(bag -> bag.kind == BagKind.BUNDLE && bag.mutable)
                .filter(bag -> serverTake || InvOps.canReachSlot(bag.menuSlot))
                .toList();
    }

    public interface RecipeCollectionAccess {
        void routinebags$markCraftable(RecipeDisplayId recipe);
    }

    private static final class PendingRecipe {
        final int requestId;
        final net.minecraft.world.inventory.AbstractContainerMenu menu;
        final RecipeDisplayId recipe;
        final List<Ingredient> requirements;
        final boolean useMaxItems;
        int ticksLeft;
        boolean cancelling;

        PendingRecipe(int requestId, net.minecraft.world.inventory.AbstractContainerMenu menu,
                RecipeDisplayId recipe, List<Ingredient> requirements, boolean useMaxItems, int ticksLeft) {
            this.requestId = requestId;
            this.menu = menu;
            this.recipe = recipe;
            this.requirements = List.copyOf(requirements);
            this.useMaxItems = useMaxItems;
            this.ticksLeft = ticksLeft;
        }
    }

    private record ExtractionPlan(Map<ItemKey, Integer> missing, List<TakeTarget> serverTargets,
            List<LocalTarget> localTargets) {
        ExtractionPlan {
            missing = Map.copyOf(missing);
            serverTargets = List.copyOf(serverTargets);
            localTargets = List.copyOf(localTargets);
        }
    }

    private record LocalTarget(int menuSlot, int entryIndex, ItemKey key, int amount) {}

    private RecipeBagSupport() {}
}
