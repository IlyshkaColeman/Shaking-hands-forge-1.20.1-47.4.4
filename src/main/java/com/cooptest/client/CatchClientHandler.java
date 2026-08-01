package com.cooptest.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client feedback for fall-catch: plays the catcher's catch animation. Ported from
 * Fabric to Forge 1.20.1 — the receiver is driven by FallCatchHandler.CatchAnimMsg
 * via DistExecutor.
 */
@OnlyIn(Dist.CLIENT)
public final class CatchClientHandler {

    private CatchClientHandler() {}

    private static final Map<UUID, Long> catcherAnimStart = new HashMap<>();
    private static final Map<UUID, Long> caughtAnimStart = new HashMap<>();
    private static final long CATCHER_ANIM_DURATION = 500;
    private static final long CAUGHT_ANIM_DURATION = 400;

    public static void register() { }

    public static void onCatchAnim(UUID catcherId, UUID caughtId) {
        long now = System.currentTimeMillis();
        catcherAnimStart.put(catcherId, now);
        caughtAnimStart.put(caughtId, now);
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            for (AbstractClientPlayer player : client.level.players()) {
                if (player.getUUID().equals(catcherId)) {
                    CoopAnimationHandler.playCatchAnimation(player);
                    break;
                }
            }
        }
    }

    public static float getCatcherAnimProgress(UUID playerId) {
        Long startTime = catcherAnimStart.get(playerId);
        if (startTime == null) return -1f;
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > CATCHER_ANIM_DURATION) { catcherAnimStart.remove(playerId); return -1f; }
        return (float) elapsed / CATCHER_ANIM_DURATION;
    }

    public static float getCaughtAnimProgress(UUID playerId) {
        Long startTime = caughtAnimStart.get(playerId);
        if (startTime == null) return -1f;
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > CAUGHT_ANIM_DURATION) { caughtAnimStart.remove(playerId); return -1f; }
        return (float) elapsed / CAUGHT_ANIM_DURATION;
    }

    public static void cleanup(UUID playerId) {
        catcherAnimStart.remove(playerId);
        caughtAnimStart.remove(playerId);
    }
}
