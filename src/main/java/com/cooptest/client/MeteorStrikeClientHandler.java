package com.cooptest.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * STAGE 6 STUB — Meteor Strike client feedback (ability HUD, crosshair, firing).
 * Tracks the ability window from the server messages; the HUD overlay and the
 * "press dap key to fire" wiring land with the client-render / dap-input stage.
 */
@OnlyIn(Dist.CLIENT)
public final class MeteorStrikeClientHandler {

    private MeteorStrikeClientHandler() {}

    private static long abilityExpiry = 0L;
    private static long countdownMs = -1L;

    public static void onGrant(long expiryMs) {
        abilityExpiry = expiryMs;
    }

    public static void onStatus(long remainingAbilityMs, long countdown) {
        countdownMs = countdown;
    }

    public static void onExpired() {
        abilityExpiry = 0L;
        countdownMs = -1L;
    }

    public static boolean hasAbility() {
        return System.currentTimeMillis() < abilityExpiry;
    }

    /** Fire the meteor (called from the dap input handler in a later stage). */
    public static void fire() {
        com.cooptest.MeteorStrikeHandler.sendMeteorFire();
    }
}
