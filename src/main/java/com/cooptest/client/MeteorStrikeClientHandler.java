package com.cooptest.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * Meteor Strike client feedback. The ability is granted (after a dap fusion) via the
 * server messages routed here; while active, a HUD prompt shows and pressing the dap
 * key (G) fires the meteor. Ported to Forge 1.20.1.
 */
@OnlyIn(Dist.CLIENT)
public final class MeteorStrikeClientHandler {

    private MeteorStrikeClientHandler() {}

    private static long abilityExpiry = 0L;
    private static long countdownMs = -1L;

    public static final IGuiOverlay HUD = (gui, g, partial, w, h) -> renderHud(g, w, h);

    public static void onGrant(long expiryMs) { abilityExpiry = expiryMs; }

    public static void onStatus(long remainingAbilityMs, long countdown) { countdownMs = countdown; }

    public static void onExpired() { abilityExpiry = 0L; countdownMs = -1L; }

    public static boolean hasAbility() { return System.currentTimeMillis() < abilityExpiry; }

    /** Fire the meteor (called from the dap key handler while the ability is active). */
    public static void fire() { com.cooptest.MeteorStrikeHandler.sendMeteorFire(); }

    private static void renderHud(GuiGraphics g, int sw, int sh) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (!hasAbility()) return;
        long remaining = abilityExpiry - System.currentTimeMillis();
        float pulse = (float) (Math.sin(System.currentTimeMillis() / 150.0) * 0.25 + 0.75);
        int alpha = (int) (pulse * 255) << 24;
        String label = "METEOR READY  [G]  " + (remaining / 1000 + 1) + "s";
        HudTextRenderer.drawCenterImpact(g, label, sw / 2, sh / 2 - 44,
                alpha | 0xFFFFFF, alpha | 0xFF3322);
    }
}
