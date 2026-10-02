package dev.lans.routinebags.mixin.client;

import dev.lans.routinebags.client.ContainerMounts;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.lans.mobundle.client.StorageChestScreen", remap = false)
abstract class MoBundleStorageScreenMixin {
    @Inject(method = "handleExclusiveMouseScroll", at = @At("HEAD"), cancellable = true, require = 0)
    private void routinebags$scrollMountedPanel(double x, double y, double scrollX, double scrollY,
                                               CallbackInfoReturnable<Boolean> callback) {
        // moBundle 的 HIGHEST 事件会吃掉整屏滚轮；在它决定箱体滚动前先处理浮动面板。
        if (ContainerMounts.mouseScrolled((Screen) (Object) this, x, y, scrollX, scrollY)) {
            callback.setReturnValue(true);
        }
    }
}
