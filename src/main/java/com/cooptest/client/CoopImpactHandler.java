package com.cooptest.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Drives the impact silhouette: while {@link #playing}, the LivingEntityRenderer mixin
 * draws players as a solid white/black silhouette, flipping each frame. Ported from the
 * Fabric mod. {@link #tick()} is called each client tick; {@link #start} on an impact.
 */
@OnlyIn(Dist.CLIENT)
public final class CoopImpactHandler {

    private CoopImpactHandler() {}

    public static volatile boolean playing = false;
    public static volatile boolean whiteFrame = true;

    private static long startMs = 0;
    private static int frameCount = 6;
    private static long frameDurationMs = 33L;

    public static void start(int frames, long durationEach) {
        frameCount = frames;
        frameDurationMs = durationEach;
        startMs = System.currentTimeMillis();
        whiteFrame = true;
        playing = true;
    }

    public static void tick() {
        if (!playing) return;
        long elapsed = System.currentTimeMillis() - startMs;
        int frameIdx = (int) (elapsed / frameDurationMs);
        if (frameIdx >= frameCount) {
            playing = false;
            whiteFrame = true;
            return;
        }
        whiteFrame = (frameIdx % 2) == 0;
    }
}
