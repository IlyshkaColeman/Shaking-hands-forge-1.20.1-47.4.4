package com.cooptest.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * STAGE 4 STUB — charged-dap client logic (charge bar, QTE input, face-dap session).
 * Only the members referenced by the animation core / networking are declared here;
 * the full mechanic is ported in Stage 4 (Dap family).
 */
@OnlyIn(Dist.CLIENT)
public final class ChargedDapClientHandler {

    private ChargedDapClientHandler() {}

    private static boolean inFaceDapSession = false;
    private static boolean playerFrozen = false;

    public static void register() { }

    public static void cleanup(UUID playerId) { }

    /** Set by ChargedDapHandler.PerfectDapFreezePayload (sit / perfect-dap freeze). */
    public static void onPerfectDapFreeze(boolean frozen) {
        playerFrozen = frozen;
    }

    /** Read by MovementFreezeMixin (Stage 5) to lock local movement. */
    public static boolean isPlayerFrozen() {
        return playerFrozen;
    }

    public static void triggerDapBadBlock() { }

    public static void setInFaceDapSession(boolean active) {
        inFaceDapSession = active;
    }

    public static boolean isInFaceDapSession() {
        return inFaceDapSession;
    }
}
