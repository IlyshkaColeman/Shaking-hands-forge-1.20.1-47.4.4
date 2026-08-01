package com.cooptest.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * Client receiver for grab-state broadcasts. Updates GrabClientState so
 * renderers / input handlers see who is holding whom.
 */
@OnlyIn(Dist.CLIENT)
public final class GrabClientNetworking {

    private GrabClientNetworking() {}

    public static void register() { }

    public static void onGrabState(UUID holder, UUID held, boolean start) {
        GrabClientState.setGrabState(holder, held, start);
    }
}
