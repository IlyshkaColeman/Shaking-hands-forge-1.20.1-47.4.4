package com.cooptest;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks recently launched players to draw a short particle trail. Ported from
 * Fabric to Forge 1.20.1. The Fabric END_SERVER_TICK registration becomes a
 * {@link #tick(MinecraftServer)} call driven by CoopServerTick.
 */
public final class LaunchedPlayerTracker {

    private LaunchedPlayerTracker() {}

    private static final HashMap<UUID, Integer> launchedTicks = new HashMap<>();
    private static final int TRAIL_DURATION = 20; // 1 second of particle trail

    public static void markPlayerAsLaunched(UUID playerId) {
        launchedTicks.put(playerId, 0);
    }

    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Integer>> iterator = launchedTicks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            int ticks = entry.getValue();
            if (ticks < TRAIL_DURATION) {
                PoseEffects.playLaunchTrailEffects(player);
                entry.setValue(ticks + 1);
            } else {
                iterator.remove();
            }
        }
    }

    public static void cleanup(UUID playerId) {
        launchedTicks.remove(playerId);
    }
}
