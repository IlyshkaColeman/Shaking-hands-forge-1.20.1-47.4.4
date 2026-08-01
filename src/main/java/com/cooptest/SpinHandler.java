package com.cooptest;

import net.minecraft.server.MinecraftServer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * STAGE 4 STUB — spin (helicopter) mechanic.
 * isSpinning() is consulted by GrabMechanic's throw tick to extend flight time;
 * the backing set is real so behaviour stays consistent once the mechanic lands.
 */
public final class SpinHandler {

    private SpinHandler() {}

    private static final Set<UUID> spinning = new HashSet<>();

    public static void register() { }

    public static void tick(MinecraftServer server) { }

    public static boolean isSpinning(UUID playerId) {
        return spinning.contains(playerId);
    }

    public static void setSpinning(UUID playerId, boolean value) {
        if (value) spinning.add(playerId);
        else spinning.remove(playerId);
    }
}
