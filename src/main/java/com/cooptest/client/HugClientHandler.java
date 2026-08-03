package com.cooptest.client;

import com.cooptest.CoopMovesConfig;
import com.cooptest.CoopNetwork;
import com.cooptest.HighFiveHugHandler;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Client hug input (F). While held, streams {@link HighFiveHugHandler.HugHoldMsg} to
 * the server, which resolves the hug once both partners hold long enough and close.
 * Ported from Fabric to Forge 1.20.1.
 */
@OnlyIn(Dist.CLIENT)
public final class HugClientHandler {

    private HugClientHandler() {}

    private static KeyMapping hugKey;

    public static void registerKeyBindings(RegisterKeyMappingsEvent event) {
        hugKey = new KeyMapping("key.coopmoves.hug", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F, "category.coopmoves");
        event.register(hugKey);
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(HugClientHandler.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (hugKey == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!CoopMovesConfig.get().enableHighFiveHug) return;
        if (hugKey.isDown()) CoopNetwork.sendToServer(new HighFiveHugHandler.HugHoldMsg());
    }

    public static boolean isLocalPlayerInHug() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        var state = CoopAnimationHandler.getAnimState(mc.player.getUUID());
        return state == CoopAnimationHandler.AnimState.HUG_START
                || state == CoopAnimationHandler.AnimState.HUGGING
                || state == CoopAnimationHandler.AnimState.HUGGING2;
    }
}
