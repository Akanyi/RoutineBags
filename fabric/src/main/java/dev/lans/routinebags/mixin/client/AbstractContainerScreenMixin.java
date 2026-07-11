package dev.lans.routinebags.mixin.client;

import dev.lans.routinebags.client.ContainerMounts;
import dev.lans.routinebags.client.MountedBagPanel;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    @Unique
    private MountedBagPanel routinebags$panel;

    @Inject(method = "init", at = @At("TAIL"))
    private void routinebags$init(CallbackInfo ci) {
        ContainerMounts.cleanup(this.routinebags$panel);
        this.routinebags$panel = ContainerMounts.create((AbstractContainerScreen<?>) (Object) this);
    }

    @Inject(method = "extractContents", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;popMatrix()Lorg/joml/Matrix3x2fStack;"))
    private void routinebags$render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ContainerMounts.render((AbstractContainerScreen<?>) (Object) this, this.routinebags$panel,
                graphics, mouseX, mouseY, partialTick);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void routinebags$mouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (ContainerMounts.mouseClicked(this.routinebags$panel, event, doubleClick)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void routinebags$mouseReleased(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (ContainerMounts.mouseReleased(this.routinebags$panel, event)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void routinebags$mouseDragged(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        if (ContainerMounts.mouseDragged(this.routinebags$panel, event)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void routinebags$mouseScrolled(double x, double y, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
        if (ContainerMounts.mouseScrolled(this.routinebags$panel, x, y, scrollX, scrollY)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void routinebags$keyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (ContainerMounts.keyPressed((AbstractContainerScreen<?>) (Object) this, this.routinebags$panel, event)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void routinebags$tick(CallbackInfo ci) {
        ContainerMounts.tick((AbstractContainerScreen<?>) (Object) this, this.routinebags$panel);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void routinebags$removed(CallbackInfo ci) {
        ContainerMounts.cleanup(this.routinebags$panel);
        this.routinebags$panel = null;
    }
}
