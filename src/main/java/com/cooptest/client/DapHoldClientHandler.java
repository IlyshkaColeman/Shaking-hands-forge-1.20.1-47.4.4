package com.cooptest.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dap-hold client state. Ported from Fabric to Forge 1.20.1 (reduced).
 *
 * The third-person animations are already driven by PoseNetworking.broadcastAnimState;
 * this handler tracks per-player dap-hold / freeze state so the animation core and
 * (Stage 5) movement-freeze mixin can gate correctly. The J-hold input + HUD land
 * with the client-render / dap-input stage — {@link com.cooptest.DapHoldHandler}
 * exposes sendJHold/sendJRelease/sendGroupJoin for that.
 */
@OnlyIn(Dist.CLIENT)
public final class DapHoldClientHandler {

    private DapHoldClientHandler() {}

    private static final Set<UUID> inDapHold = new HashSet<>();
    private static final Map<UUID, Boolean> frozen = new HashMap<>();

    public static void register() { }

    public static void onStart(UUID playerId, UUID partnerId, int role) {
        inDapHold.add(playerId);
    }

    public static void onWindow(boolean open) { }

    public static void onLoop(boolean looping) { }

    public static void onEnd(boolean wasLooping) {
        inDapHold.clear();
    }

    public static void onFreeze(UUID playerId, boolean isFrozen) {
        if (isFrozen) frozen.put(playerId, true);
        else frozen.remove(playerId);
    }

    public static void onGroupJoined(UUID joinerId, UUID hfId, int memberCount) {
        inDapHold.add(joinerId);
    }

    public static void onGroupResult(boolean perfect, int memberCount) {
        inDapHold.clear();
    }

    /** Consulted by CoopAnimationHandler to gate DAP_HIT / HIGHFIVE_HIT. */
    public static boolean isAnimationLocked(UUID playerId) {
        return inDapHold.contains(playerId);
    }

    public static boolean isFrozen(UUID playerId) {
        return frozen.getOrDefault(playerId, false);
    }

    public static void cleanup(UUID playerId) {
        inDapHold.remove(playerId);
        frozen.remove(playerId);
    }
}
