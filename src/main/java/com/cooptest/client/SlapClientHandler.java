package com.cooptest.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * Client cues for {@link com.cooptest.SlapHandler}: snap the victim's camera pitch
 * (back-slap) or yaw (front-slap) and close any open screen. Ported to Forge 1.20.1.
 * These are invoked from the CoopNetwork message handlers; each broadcast carries the
 * target player id and only applies on that client.
 */
@OnlyIn(Dist.CLIENT)
public final class SlapClientHandler {

    private SlapClientHandler() {}

    private static long slapCooldownEnd = 0;

    public static boolean isOnSlapCooldown() { return System.currentTimeMillis() < slapCooldownEnd; }
    public static void triggerSlapCooldown() { slapCooldownEnd = System.currentTimeMillis() + 500L; }

    public static void onCameraFlick(UUID playerId, float pitchDelta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.getUUID().equals(playerId)) return;
        mc.player.setXRot(Math.min(90f, mc.player.getXRot() + pitchDelta));
        triggerSlapCooldown();
    }

    public static void onCameraYawFlick(UUID playerId, float yawDelta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.getUUID().equals(playerId)) return;
        mc.player.setYRot(mc.player.getYRot() + yawDelta);
    }

    public static void onScreenClose(UUID playerId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.getUUID().equals(playerId)) return;
        if (mc.screen != null) mc.setScreen(null);
    }
}
