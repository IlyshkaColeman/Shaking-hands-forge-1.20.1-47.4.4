package com.cooptest.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * Dap Fusion / perfect-combo QTE client state. Ported from Fabric to Forge 1.20.1
 * (reduced). The server drives the QTE bar, phase, fused state and black-screen via
 * DapFusionHandler messages; the HUD timing bar, black-screen overlay and G/H/J
 * input routing land with the client-render / dap-input stage (Stage 6).
 * DapFusionHandler exposes sendGPress/sendUnfuse for that input handler.
 */
@OnlyIn(Dist.CLIENT)
public final class FusionClientHandler {

    private FusionClientHandler() {}

    private static boolean qteOpen = false;
    private static int phase = -1;
    private static boolean fused = false;
    private static boolean blackScreen = false;

    public static void onFusionQTE(UUID playerId, String button, int stage,
                                   long windowStartMs, long windowEndMs, boolean open, int type) {
        qteOpen = open;
    }

    public static void onFusionPhase(UUID p1, UUID p2, int ph) {
        phase = ph;
        if (ph == 99 || ph == 4) qteOpen = false;
    }

    public static void onFusionFused(boolean isFused) {
        fused = isFused;
    }

    public static void onBlackScreen(boolean active) {
        blackScreen = active;
    }

    public static boolean isQTEOpen() { return qteOpen; }

    public static boolean isGWindowOpen() { return phase == 0; }

    public static boolean isFused() { return fused; }

    public static boolean isBlackScreen() { return blackScreen; }

    public static void handleQTEHPress() { }
}
