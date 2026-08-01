package com.cooptest;

import java.util.UUID;

/**
 * STAGE 4 STUB — server-side high-five mechanic.
 * isInBlockingState() is consulted by PoseNetworking before allowing GRAB_READY;
 * returning false preserves the non-blocking default until Stage 4 ports the
 * full HighFive group.
 */
public final class HighFiveHandler {

    private HighFiveHandler() {}

    public static void registerPayloads() { }

    public static void register() { }

    public static boolean isInBlockingState(UUID playerId) {
        return false;
    }
}
