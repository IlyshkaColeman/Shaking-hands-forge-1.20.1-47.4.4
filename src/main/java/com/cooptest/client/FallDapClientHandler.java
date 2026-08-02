package com.cooptest.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client feedback for Fall Dap / squash. Ported from Fabric to Forge 1.20.1 — the
 * receivers are driven by FallDapHandler messages via DistExecutor.
 */
@OnlyIn(Dist.CLIENT)
public final class FallDapClientHandler {

    private FallDapClientHandler() {}

    private static final Map<UUID, Integer> fallDapStates = new HashMap<>();

    public static final int STATE_NONE = 0;
    public static final int STATE_CHARGING = 1;
    public static final int STATE_FALLING = 2;
    public static final int STATE_HIT = 3;

    public static void register() { }

    public static void onFallDapAnim(UUID playerId, int state) {
        fallDapStates.put(playerId, state);
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        for (AbstractClientPlayer player : client.level.players()) {
            if (player.getUUID().equals(playerId)) {
                switch (state) {
                    case STATE_CHARGING -> CoopAnimationHandler.playFallDapChargeStart(player);
                    case STATE_FALLING -> CoopAnimationHandler.playFallDapFalling(player);
                    case STATE_HIT -> CoopAnimationHandler.playFallDapHit(player);
                    case STATE_NONE -> fallDapStates.remove(playerId);
                }
                break;
            }
        }
    }

    public static void onSquashAnim(UUID playerId) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        for (AbstractClientPlayer player : client.level.players()) {
            if (player.getUUID().equals(playerId)) {
                CoopAnimationHandler.playSquashed(player);
                break;
            }
        }
    }

    public static int getFallDapState(UUID playerId) {
        return fallDapStates.getOrDefault(playerId, STATE_NONE);
    }

    public static boolean isInFallDap(UUID playerId) {
        int state = fallDapStates.getOrDefault(playerId, STATE_NONE);
        return state == STATE_CHARGING || state == STATE_FALLING;
    }

    public static void cleanup(UUID playerId) {
        fallDapStates.remove(playerId);
    }
}
