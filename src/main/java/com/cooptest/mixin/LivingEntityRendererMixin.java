package com.cooptest.mixin;

import com.cooptest.client.CoopImpactHandler;
import com.cooptest.client.CoopImpactRenderType;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Renders players as a solid white/black silhouette during the impact "hit freeze"
 * (driven by {@link CoopImpactHandler}). Overrides the render type only while the
 * effect is playing, so it is inert otherwise.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Inject(method = "getRenderType", at = @At("HEAD"), cancellable = true)
    private void coop$impactSilhouette(LivingEntity entity, boolean bodyVisible, boolean translucent,
                                       boolean glowing, CallbackInfoReturnable<RenderType> cir) {
        if (!CoopImpactHandler.playing) return;
        if (!(entity instanceof Player)) return;
        @SuppressWarnings({"unchecked", "rawtypes"})
        ResourceLocation tex = ((LivingEntityRenderer) (Object) this).getTextureLocation(entity);
        RenderType layer = CoopImpactHandler.whiteFrame
                ? CoopImpactRenderType.getWhiteLayer(tex)
                : CoopImpactRenderType.getBlackLayer(tex);
        if (layer != null) cir.setReturnValue(layer);
    }
}
