package com.cooptest.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Heaven Dap client feedback. Drives {@link HeavenWhiteOverlay} from the HeavenDap
 * S2C signals. Ported to Forge 1.20.1 (visual only; sound ducking omitted).
 */
@OnlyIn(Dist.CLIENT)
public final class HeavenDapClientHandler {

    private HeavenDapClientHandler() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.register(HeavenDapClientHandler.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        HeavenWhiteOverlay.tick();
        CoopImpactHandler.tick();
    }

    /** Impact flash — begin the white overlay sequence. */
    public static void onHeavenImpact() { HeavenWhiteOverlay.start(); }

    public static void onHeavenDapStart() { if (!HeavenWhiteOverlay.isActive()) HeavenWhiteOverlay.start(); }

    public static void onHeavenDapEnd() { HeavenWhiteOverlay.stop(); }

    public static void onRestoreVolume() { /* sound ducking not ported */ }
}
