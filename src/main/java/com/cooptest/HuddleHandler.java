package com.cooptest;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * STAGE 4 STUB — group "huddle" mechanic (multi-player QTE huddle). Consulted by the
 * central QTE dispatcher (QTEManager). Returns neutral values until the huddle group
 * is fully ported.
 */
public final class HuddleHandler {

    private HuddleHandler() {}

    public static void register() { }

    public static void registerMessages() { }

    public static void tick(MinecraftServer server) { }

    /** Returns true if this handler consumed the QTE button press. */
    public static boolean onButtonPress(ServerPlayer player, String button) { return false; }

    public static boolean isInHuddle(UUID playerId) { return false; }

    public static void cleanup(UUID playerId) { }
}
