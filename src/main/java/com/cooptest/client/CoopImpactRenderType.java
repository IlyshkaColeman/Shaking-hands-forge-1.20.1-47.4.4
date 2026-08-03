package com.cooptest.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/**
 * Custom render layers that draw an entity as a solid white / black silhouette, using
 * two small core shaders. Ported from the Fabric mod (ShaderProgram + RenderLayer.of)
 * to Forge 1.20.1 (RegisterShadersEvent + RenderType.create). If the shaders fail to
 * load, the layers fall back to the normal entity-cutout layer (no silhouette, no
 * crash). Extends {@link RenderType} only to access the protected render-state shards.
 */
@OnlyIn(Dist.CLIENT)
public final class CoopImpactRenderType extends RenderType {

    private CoopImpactRenderType(String name, VertexFormat fmt, VertexFormat.Mode mode, int size,
                                 boolean crumbling, boolean sort, Runnable setup, Runnable clear) {
        super(name, fmt, mode, size, crumbling, sort, setup, clear);
    }

    private static ShaderInstance whiteShader;
    private static ShaderInstance blackShader;

    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(), "coopmoves:cooptest_entity_white", DefaultVertexFormat.NEW_ENTITY),
                    s -> whiteShader = s);
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(), "coopmoves:cooptest_entity_black", DefaultVertexFormat.NEW_ENTITY),
                    s -> blackShader = s);
        } catch (IOException e) {
            System.err.println("[CoopMoves] impact silhouette shaders failed to load: " + e.getMessage());
        }
    }

    public static RenderType getWhiteLayer(ResourceLocation texture) {
        if (whiteShader == null) return RenderType.entityCutoutNoCull(texture);
        return layer("cooptest_entity_white", texture, whiteShader);
    }

    public static RenderType getBlackLayer(ResourceLocation texture) {
        if (blackShader == null) return RenderType.entityCutoutNoCull(texture);
        return layer("cooptest_entity_black", texture, blackShader);
    }

    private static RenderType layer(String name, ResourceLocation texture, ShaderInstance shader) {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(new ShaderStateShard(() -> shader))
                .setTextureState(new TextureStateShard(texture, false, false))
                .setTransparencyState(NO_TRANSPARENCY)
                .setLightmapState(LIGHTMAP)
                .setOverlayState(OVERLAY)
                .createCompositeState(true);
        return create(name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, true, false, state);
    }
}
