package com.cooptest.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * STAGE 6 STUB — Dap Fusion / perfect-combo QTE bar client rendering + input.
 * The server drives the bar via DapFusionHandler.FusionQTEPayload; the HUD bar and
 * G/H/J routing land with the client-render / dap-input stage. Query methods return
 * neutral values so other handlers can consult them safely.
 */
@OnlyIn(Dist.CLIENT)
public final class FusionClientHandler {

    private FusionClientHandler() {}

    private static boolean qteOpen = false;

    public static void onFusionQTE(UUID playerId, String button, int center,
                                   long halfWidth, long period, boolean active, int mode) {
        qteOpen = active;
    }

    public static boolean isQTEOpen() { return qteOpen; }

    public static boolean isGWindowOpen() { return false; }

    public static void handleQTEHPress() { }
}
