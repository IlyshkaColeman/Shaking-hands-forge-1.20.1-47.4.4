package com.cooptest.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * STAGE 6 STUB — Divine Flame combo client cue (window prompt / J-press wiring).
 * The server DivineStart signal is routed here; the input + HUD land with the
 * client-render / dap-input stage. {@link com.cooptest.DivineFlamCombo#sendJPress}
 * is called from that input handler later.
 */
@OnlyIn(Dist.CLIENT)
public final class DivineFlamComboClient {

    private DivineFlamComboClient() {}

    public static void onDivineStart() { }
}
