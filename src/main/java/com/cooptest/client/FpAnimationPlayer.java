package com.cooptest.client;

import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;

/**
 * A {@link KeyframeAnimationPlayer} that reports a first-person mode, so KosmX renders
 * the coop animation on the local player's arms in first person too (clap, holding a
 * player, dap, hug, push, ...). The Fabric mod achieved this via ZigyTheBird PAL's
 * controller FP API; KosmX classic instead reads first-person mode from the playing
 * {@code IAnimation}, which this subclass overrides.
 */
public class FpAnimationPlayer extends KeyframeAnimationPlayer {

    private final FirstPersonMode fpMode;
    private final FirstPersonConfiguration fpConfig;

    public FpAnimationPlayer(KeyframeAnimation animation, FirstPersonMode mode, FirstPersonConfiguration config) {
        super(animation);
        this.fpMode = mode;
        this.fpConfig = config;
    }

    @Override
    public FirstPersonMode getFirstPersonMode(float tickDelta) {
        return fpMode;
    }

    @Override
    public FirstPersonConfiguration getFirstPersonConfiguration(float tickDelta) {
        return fpConfig;
    }
}
