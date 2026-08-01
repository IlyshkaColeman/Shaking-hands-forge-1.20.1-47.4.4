package com.cooptest.client;

import com.cooptest.SitHandler;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Client detector for the "hold F to help a friend up" part of Sit. Ported from
 * Fabric to Forge 1.20.1 (raw F key via InputConstants.isKeyDown; ClientTickEvents
 * -> TickEvent.ClientTickEvent). Sends the F-hold edge to the server.
 */
@OnlyIn(Dist.CLIENT)
public final class SitClientHandler {

    private SitClientHandler() {}

    private static boolean wasFHeld = false;

    public static void register() {
        MinecraftForge.EVENT_BUS.register(SitClientHandler.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.screen != null) return;
        boolean fHeld = InputConstants.isKeyDown(client.getWindow().getWindow(), GLFW.GLFW_KEY_F);
        if (fHeld != wasFHeld) {
            wasFHeld = fHeld;
            SitHandler.sendFHold(fHeld);
        }
    }
}
