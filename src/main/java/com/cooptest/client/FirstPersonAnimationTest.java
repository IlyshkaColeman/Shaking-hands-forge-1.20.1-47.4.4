package com.cooptest.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * STAGE 6 STUB — first-person arm animations.
 *
 * The Fabric original drives first-person arm rendering alongside the third-person
 * KosmX animation. Signatures here match every call site in CoopAnimationHandler so
 * the animation core compiles and third-person animations work now; the bodies are
 * filled in during Stage 6 (client rendering), which is where the held-item /
 * first-person renderer mixins land.
 */
@OnlyIn(Dist.CLIENT)
public final class FirstPersonAnimationTest {

    private FirstPersonAnimationTest() {}

    public static void init() { }

    public static void stop() { }

    public static void showBothHands() { }

    public static void playThrow() { }

    public static void playDapCharge() { }

    public static void playDapHit() { }

    public static void playPerfectDap() { }

    public static void playPush() { }

    public static void playHighFiveStart() { }

    public static void playHighFiveHit() { }

    public static void playHighFiveCombo() { }

    public static void playHug() { }

    public static void playKick() { }

    public static void playDropKick() { }

    public static void playSlap() { }
}
