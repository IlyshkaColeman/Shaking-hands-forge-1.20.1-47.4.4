package com.cooptest.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * STAGE 6 STUB — Heaven Dap client feedback (white screen overlay, sound ducking,
 * impact flash). Signatures match the HeavenDapPayloads receivers so the Heaven-dap
 * server logic can drive them once the ChargedDap core is ported; the visual/audio
 * bodies land with the client-render stage (HeavenWhiteOverlay etc.).
 */
@OnlyIn(Dist.CLIENT)
public final class HeavenDapClientHandler {

    private HeavenDapClientHandler() {}

    public static void onHeavenDapStart() { }

    public static void onHeavenDapEnd() { }

    public static void onRestoreVolume() { }

    public static void onHeavenImpact() { }
}
