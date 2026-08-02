package com.cooptest;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * STAGE 4 STUB — high-five hug hold (hold F after a high-five to hug). Triggered by
 * HighFiveHandler's combo window. Ported fully with the HighFive-hug group.
 */
public final class HighFiveHugHandler {

    private HighFiveHugHandler() {}

    public static void register() { }

    public static void registerMessages() { }

    public static void tick(MinecraftServer server) { }

    public static void startHugHold(ServerPlayer p1, ServerPlayer p2) { }

    public static boolean isInHug(UUID playerId) { return false; }

    public static void cleanup(UUID playerId) { }
}
