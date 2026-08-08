package com.cooptest;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

/**
 * Clears all per-player coop state on death, respawn and disconnect.
 *
 * The Fabric mod only cleaned up on DISCONNECT, which left stale state after death —
 * e.g. a stuck pose so the Grab key (R) stopped responding once you had died. This
 * Forge port additionally hooks {@link LivingDeathEvent} and
 * {@link PlayerEvent.PlayerRespawnEvent} and broadcasts a pose/anim reset, fixing that.
 */
public final class PlayerCleanupHandler {

    private PlayerCleanupHandler() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.register(PlayerCleanupHandler.class);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            cleanupAll(player.getServer(), player.getUUID(), player, false);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            cleanupAll(player.getServer(), player.getUUID(), player, false);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            cleanupAll(player.getServer(), player.getUUID(), player, true);
        }
    }

    private static void cleanupAll(MinecraftServer server, UUID uuid, ServerPlayer player, boolean leaving) {
        GrabMechanic.fullCleanup(uuid);
        HighFiveHandler.cleanup(uuid);
        HighFiveHugHandler.cleanup(uuid);
        HighFiveQTEHugHandler.cleanup(uuid);
        HuddleHandler.cleanup(uuid);
        PushInteractionHandler.pushImmunity.remove(uuid);
        ChargedDapHandler.cleanup(uuid);
        SyncDapHandler.cleanup(uuid, server);
        QTEManager.cancelQTE(uuid);
        DapComboChain.cancelCombo(uuid);
        PerfectDapComboHandler.cancelCombo(uuid);
        DapFusionHandler.cleanup(uuid);
        MahitoTrollHandler.cleanup(uuid);
        FallDapHandler.cleanup(uuid);
        FallCatchHandler.cleanup(uuid);
        MeteorStrikeHandler.cleanup(uuid);
        ArmPoseTracker.cleanup(uuid);
        MarioJumpHandler.cleanup(uuid);
        DivineFlamCombo.cleanup(uuid);
        KickHandler.cleanup(uuid);
        ClapHandler.cleanup(uuid);
        NormalFacingDapHandler.cleanup(uuid);
        DapHoldHandler.cleanup(uuid, server);
        SitHandler.cleanup(uuid);
        GroundPoundHandler.cleanup(uuid);
        SpinHandler.cleanup(uuid);
        LaunchedPlayerTracker.cleanup(uuid);

        // Reset pose/anim so the client's input logic (e.g. Grab R) works after respawn.
        if (server != null) {
            PoseNetworking.broadcastPoseChange(server, uuid, PoseState.NONE);
            if (player != null) PoseNetworking.broadcastAnimState(player, 0);
        }
        if (leaving) PoseNetworking.poseStates.remove(uuid);
        else PoseNetworking.poseStates.put(uuid, PoseState.NONE);
    }
}
