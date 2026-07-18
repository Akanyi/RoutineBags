package dev.lans.routinebags.mixin.client;

import dev.lans.routinebags.client.RecipeBagSupport;
import java.util.Set;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RecipeCollection.class)
public abstract class RecipeCollectionMixin implements RecipeBagSupport.RecipeCollectionAccess {
    @Shadow
    @Final
    private Set<RecipeDisplayId> craftable;

    @Inject(method = "selectRecipes", at = @At("TAIL"))
    private void routinebags$includeBundleContents(StackedItemContents contents,
            java.util.function.Predicate<RecipeDisplay> selector, CallbackInfo ci) {
        RecipeBagSupport.extendCraftable((RecipeCollection) (Object) this, contents, selector);
    }

    @Override
    public void routinebags$markCraftable(RecipeDisplayId recipe) {
        this.craftable.add(recipe);
    }
}
