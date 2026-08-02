package com.cooptest;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * STAGE 4 STUB — high-five QTE-hug (the QTE that can turn a high-five into a hug).
 * Consulted by the central QTE dispatcher (QTEManager) and by HighFiveHandler's
 * combo window. Returns neutral values until the HighFive-hug group is fully ported.
 */
public final class HighFiveQTEHugHandler {

    private HighFiveQTEHugHandler() {}

    public static void register() { }

    public static void registerMessages() { }

    public static void tick(MinecraftServer server) { }

    /** Returns true if this handler consumed the QTE button press. */
    public static boolean onButtonPress(ServerPlayer player, String button) { return false; }

    public static boolean isInHugSession(UUID playerId) { return false; }

    public static void startHugQTE(ServerPlayer p1, ServerPlayer p2) { }

    public static void cleanup(UUID playerId) { }
}
