package dev.lans.routinebags.mixin.client;

import dev.lans.routinebags.client.ContainerMounts;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
abstract class MountedPanelHoverMixin {
    @Inject(method = "getHoveredSlot(DD)Lnet/minecraft/world/inventory/Slot;", at = @At("HEAD"), cancellable = true)
    private void routinebags$hideCoveredSlot(double x, double y, CallbackInfoReturnable<Slot> callback) {
        // 浮动面板可以盖住原槽位，底下的悬停物品描述不能覆盖面板自己的描述。
        if (ContainerMounts.isMouseOver((AbstractContainerScreen<?>) (Object) this, x, y)) {
            callback.setReturnValue(null);
        }
    }
}
