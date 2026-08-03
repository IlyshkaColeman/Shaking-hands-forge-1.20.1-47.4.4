package com.cooptest.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Spin (helicopter) client state + HUD. Ported from Fabric to Forge 1.20.1. The spin
 * trigger lives in GrabInputHandler (sneak while thrown-airborne); this mirrors the
 * spinning flag, plays the spin animation, and shows the SPIN / LAUNCH cue.
 */
@OnlyIn(Dist.CLIENT)
public final class SpinClientHandler {

    private SpinClientHandler() {}

    private static boolean localSpinning = false;
    private static boolean localHasRider = false;
    private static boolean launchFlashActive = false;
    private static long launchFlashStart = 0L;
    private static final long LAUNCH_FLASH_MS = 400L;
    private static final Map<UUID, Boolean> spinningPlayers = new HashMap<>();

    public static final IGuiOverlay HUD = (gui, g, partial, w, h) -> renderHud(g, w, h);

    public static void register() { }

    public static void onSync(UUID id, boolean spinning) {
        spinningPlayers.put(id, spinning);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.player.getUUID().equals(id)) {
            localSpinning = spinning;
            if (spinning) FirstPersonAnimationTest.showBothHands();
            else { localHasRider = false; FirstPersonAnimationTest.stop(); }
        }
        if (mc.level != null) {
            var p = mc.level.getPlayerByUUID(id);
            if (p instanceof net.minecraft.client.player.AbstractClientPlayer acp && spinning)
                CoopAnimationHandler.playSpinAnimation(acp);
        }
    }

    public static void onLaunch(UUID spinnerId, UUID riderId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        UUID localId = mc.player.getUUID();
        boolean isSpinner = localId.equals(spinnerId);
        if (isSpinner || localId.equals(riderId)) {
            launchFlashActive = true;
            launchFlashStart = System.currentTimeMillis();
            if (isSpinner) localHasRider = false;
        }
        if (isSpinner) localHasRider = true;
    }

    private static void renderHud(GuiGraphics g, int sw, int sh) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (launchFlashActive) {
            long e = System.currentTimeMillis() - launchFlashStart;
            if (e > LAUNCH_FLASH_MS) launchFlashActive = false;
            else {
                int a = (int) ((1f - (float) e / LAUNCH_FLASH_MS) * 200) << 24;
                int c = a | 0x00FFFF;
                g.fill(0, 0, sw, 6, c);
                g.fill(0, sh - 6, sw, sh, c);
            }
        }
        if (!localSpinning) return;
        float pulse = (float) (Math.sin(System.currentTimeMillis() / 180.0) * 0.2 + 0.8);
        int a = (int) (pulse * 200) << 24;
        String label = localHasRider ? "↻ SPINNING [SHIFT] LAUNCH!" : "↻ SPINNING";
        int lx = (sw - mc.font.width(label)) / 2;
        g.drawString(mc.font, Component.literal((localHasRider ? "§e§l" : "§b") + label), lx, sh / 2 - 30, a | 0xFFFFFF, true);
    }

    public static boolean isLocalPlayerSpinning() { return localSpinning; }
    public static boolean isPlayerSpinning(UUID id) { return spinningPlayers.getOrDefault(id, false); }
    public static void forceStopLocalSpin() { localSpinning = false; }

    public static void cleanup(UUID id) {
        spinningPlayers.remove(id);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getUUID().equals(id)) { localSpinning = false; localHasRider = false; }
    }
}
