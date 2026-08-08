package com.cooptest.mixin;

import com.cooptest.client.MechanicHudTextClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes legacy action-bar mechanic messages through the custom animated HUD.
 * This catches any remaining displayClientMessage(..., true) call without
 * forcing every handler to be rewritten at once.
 */
@Mixin(Gui.class)
public abstract class GuiOverlayMessageMixin {

    @Inject(method = "setOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void coop$stylizeActionBar(Component message, boolean animateColor, CallbackInfo ci) {
        if (MechanicHudTextClient.legacy(message)) {
            ci.cancel();
        }
    }
}
