package com.cooptest.client;

import com.cooptest.DapHoldHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

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

    private static int localRole = -1;
    private static UUID localPartnerId = null;
    private static boolean windowOpen = false;
    private static boolean looping = false;
    private static boolean groupJoiner = false;
    private static boolean wasJDown = false;
    private static boolean wasGDown = false;

    public static void register() {
        MinecraftForge.EVENT_BUS.register(DapHoldClientHandler.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            wasJDown = false;
            wasGDown = false;
            return;
        }

        boolean jDown = ChargedDapClientHandler.isFireComboKeyDown();
        boolean gDown = ChargedDapClientHandler.isDapKeyDown();
        boolean participating = localRole >= 0 || groupJoiner;

        // Releasing G near an already looping dap pair requests a group join.
        if (!participating && !gDown && wasGDown) {
            DapHoldHandler.sendGroupJoin();
        }

        if (participating && (groupJoiner || windowOpen || looping)) {
            // The server treats this as a heartbeat and expires it after 300 ms.
            if (jDown) {
                DapHoldHandler.sendJHold();
            } else if (wasJDown) {
                DapHoldHandler.sendJRelease();
            }
        }

        wasJDown = jDown;
        wasGDown = gDown;
    }

    public static void onStart(UUID playerId, UUID partnerId, int role) {
        inDapHold.add(playerId);
        inDapHold.add(partnerId);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.getUUID().equals(playerId)) {
            localRole = role;
            localPartnerId = partnerId;
            windowOpen = false;
            looping = false;
            groupJoiner = false;
        }
    }

    public static void onWindow(boolean open) {
        windowOpen = open;
    }

    public static void onLoop(boolean isLooping) {
        looping = isLooping;
        if (isLooping) windowOpen = false;
    }

    public static void onEnd(boolean wasLooping) {
        inDapHold.clear();
        localRole = -1;
        localPartnerId = null;
        windowOpen = false;
        looping = false;
        groupJoiner = false;
        wasJDown = false;
    }

    public static void onFreeze(UUID playerId, boolean isFrozen) {
        if (isFrozen) frozen.put(playerId, true);
        else frozen.remove(playerId);
    }

    public static void onGroupJoined(UUID joinerId, UUID hfId, int memberCount) {
        inDapHold.add(joinerId);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.getUUID().equals(joinerId)) {
            groupJoiner = true;
            localPartnerId = hfId;
        }
    }

    public static void onGroupResult(boolean perfect, int memberCount) {
        onEnd(true);
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
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.getUUID().equals(playerId)) {
            localRole = -1;
            localPartnerId = null;
            windowOpen = false;
            looping = false;
            groupJoiner = false;
            wasJDown = false;
        }
    }
}
