package com.cooptest.client;

import com.cooptest.DivineFlamCombo;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Divine Flame combo client cue and synchronized J-window input.
 * The server DivineStart signal is routed here; the input + HUD land with the
 * client-render / dap-input stage. {@link com.cooptest.DivineFlamCombo#sendJPress}
 * is called from that input handler later.
 */
@OnlyIn(Dist.CLIENT)
public final class DivineFlamComboClient {

    private DivineFlamComboClient() {}

    private static long comboEndTime = 0L;
    private static long inputWindowEndTime = 0L;
    private static boolean wasJDown = false;
    private static boolean pressed = false;

    public static void register() {
        MinecraftForge.EVENT_BUS.register(DivineFlamComboClient.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        boolean jDown = ChargedDapClientHandler.isFireComboKeyDown();
        if (jDown && !wasJDown && isInputWindowOpen() && !pressed) {
            pressed = true;
            comboEndTime = System.currentTimeMillis() + 3800L;
            DivineFlamCombo.sendJPress();
        }
        wasJDown = jDown;
    }

    public static void onDivineStart() {
        long now = System.currentTimeMillis();
        inputWindowEndTime = now + 1460L;
        comboEndTime = inputWindowEndTime;
        pressed = false;
    }

    public static boolean isInputWindowOpen() {
        return System.currentTimeMillis() < inputWindowEndTime;
    }

    public static boolean isLocalPlayerInCombo() {
        return System.currentTimeMillis() < comboEndTime;
    }
}
