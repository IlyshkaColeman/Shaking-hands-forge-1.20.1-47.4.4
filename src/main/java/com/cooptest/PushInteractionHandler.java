package com.cooptest;

import java.util.HashMap;
import java.util.UUID;

/**
 * STAGE 4 STUB — push mechanic (server side).
 * The push-immunity map is real (GrabMechanic consults it), the rest of the
 * mechanic is ported in the Push/Catch group.
 */
public final class PushInteractionHandler {

    private PushInteractionHandler() {}

    /** playerId -> immunity expiry timestamp (ms). */
    public static final HashMap<UUID, Long> pushImmunity = new HashMap<>();

    public static void registerPayloads() { }

    public static void register() { }

    public static boolean hasPushImmunity(UUID playerId) {
        Long until = pushImmunity.get(playerId);
        return until != null && System.currentTimeMillis() < until;
    }

    public static void cleanupExpiredImmunity() {
        long now = System.currentTimeMillis();
        pushImmunity.entrySet().removeIf(e -> e.getValue() < now);
    }
}
