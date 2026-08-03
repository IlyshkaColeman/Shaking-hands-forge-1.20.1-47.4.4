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
 * Ground-pound client state + HUD label. Ported from Fabric to Forge 1.20.1. The dive
 * trigger (sneak while airborne) lives in GrabInputHandler; this handler only mirrors
 * the diving flag from the server and renders the "GROUND POUND" cue.
 */
@OnlyIn(Dist.CLIENT)
public final class GroundPoundClientHandler {

    private GroundPoundClientHandler() {}

    private static boolean localDiving = false;
    private static long diveStartMs = 0L;
    private static final Map<UUID, Boolean> divingPlayers = new HashMap<>();

    /** HUD overlay (registered from CoopMovesClient). */
    public static final IGuiOverlay HUD = (gui, g, partial, w, h) -> renderHud(g, w, h);

    public static void register() { }

    public static void onSync(UUID id, boolean diving) {
        divingPlayers.put(id, diving);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.player.getUUID().equals(id)) {
            localDiving = diving;
            if (diving) { diveStartMs = System.currentTimeMillis(); FirstPersonAnimationTest.stop(); }
            else diveStartMs = 0L;
        }
    }

    private static void renderHud(GuiGraphics g, int sw, int sh) {
        if (!localDiving) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        long elapsed = System.currentTimeMillis() - diveStartMs;
        int a = Math.min(220, (int) (elapsed / 8)) << 24;
        String label = "⬇ GROUND POUND";
        int lx = (sw - mc.font.width(label)) / 2;
        g.drawString(mc.font, Component.literal("§c§l" + label), lx, sh / 2 - 30, a | 0xFFFFFF, true);
    }

    public static boolean isLocalPlayerDiving() { return localDiving; }
    public static boolean isPlayerDiving(UUID id) { return divingPlayers.getOrDefault(id, false); }

    public static void cleanup(UUID id) {
        divingPlayers.remove(id);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getUUID().equals(id)) { localDiving = false; diveStartMs = 0L; }
    }
}
