package com.cooptest.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Compatibility facade for first-person arm animations.
 *
 * The Fabric original drives first-person arm rendering alongside the third-person
 * KosmX animation. Signatures here match every call site in CoopAnimationHandler so
 * the animation core remains source-compatible. Actual Forge first-person rendering
 * is handled by FpAnimationPlayer and CoopAnim.
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
