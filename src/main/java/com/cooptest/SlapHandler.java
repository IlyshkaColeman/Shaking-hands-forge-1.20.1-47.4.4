package com.cooptest;

import net.minecraft.server.level.ServerPlayer;

/**
 * STAGE 5 STUB — the charge-release "slap" (empty-hand full-charge release with no
 * dap partner nearby triggers a slap on a looked-at target). Consulted by
 * {@link ChargedDapHandler#onChargeRelease}. Returns false until the Slap mechanic
 * (camera-flick client payloads + attack) is ported; the release then falls through
 * to the normal whiff/dap path.
 *
 * Not to be confused with {@link FireSlapHandler}, which is the already-ported
 * fire-dap slap variant.
 */
public final class SlapHandler {

    private SlapHandler() {}

    public static void register() { }

    public static void registerMessages() { }

    /** @return true if a slap was performed (consumes the release). */
    public static boolean checkSlapOnRelease(ServerPlayer player) { return false; }
}
