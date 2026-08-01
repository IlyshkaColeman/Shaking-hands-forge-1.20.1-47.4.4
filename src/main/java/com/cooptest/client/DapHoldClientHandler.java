package com.cooptest.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * STAGE 4 STUB — dap-hold (handshake hold) client logic.
 * isAnimationLocked() gates DAP_HIT / HIGHFIVE_HIT in the animation core; returning
 * false preserves the un-locked default until the mechanic is ported in Stage 4.
 */
@OnlyIn(Dist.CLIENT)
public final class DapHoldClientHandler {

    private DapHoldClientHandler() {}

    public static void register() { }

    public static boolean isAnimationLocked(UUID playerId) {
        return false;
    }
}
