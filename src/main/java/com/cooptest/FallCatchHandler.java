package com.cooptest;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Fall-catch: a player holding out a hand (GRAB_READY, R) near a falling player
 * cancels their fall damage — a clutch save. Ported from Fabric to Forge 1.20.1.
 *
 * Fabric ServerLivingEntityEvents.ALLOW_DAMAGE -> Forge LivingAttackEvent
 * (cancelling the event is the equivalent of returning false);
 * ServerTickEvents.END_SERVER_TICK -> CoopServerTick call; CustomPayload ->
 * CoopNetwork message. Uses the shared GRAB_READY pose, so no extra keybind.
 */
public final class FallCatchHandler {

    private FallCatchHandler() {}

    public static final double CATCH_RANGE_HORIZONTAL = 3.0;
    public static final double CATCH_RANGE_VERTICAL = 4.0;
    public static final long CATCH_WINDOW_MS = 500;
    public static final long CATCH_COOLDOWN_MS = 1000;

    private static final Map<UUID, Long> catchReadyTime = new HashMap<>();
    private static final Map<UUID, Long> catchCooldowns = new HashMap<>();
    private static final Map<UUID, Boolean> successfulCatch = new HashMap<>();

    public static void register() {
        MinecraftForge.EVENT_BUS.register(FallCatchHandler.class);
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = catchReadyTime.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            UUID playerId = entry.getKey();
            PoseState pose = PoseNetworking.poseStates.getOrDefault(playerId, PoseState.NONE);
            if (pose != PoseState.GRAB_READY) {
                if (!successfulCatch.getOrDefault(playerId, false)) {
                    catchCooldowns.put(playerId, now + CATCH_COOLDOWN_MS);
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player != null) {
                        MechanicHudText.danger(player, "CATCH MISSED", "1.0s cooldown");
                    }
                }
                it.remove();
                successfulCatch.remove(playerId);
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID playerId = player.getUUID();
            PoseState pose = PoseNetworking.poseStates.getOrDefault(playerId, PoseState.NONE);
            if (pose == PoseState.GRAB_READY && !catchReadyTime.containsKey(playerId)) {
                catchReadyTime.put(playerId, now);
                successfulCatch.put(playerId, false);
            }
        }
    }

    // ------------------------------------------------------------------ damage hook

    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (event.isCanceled()) return;
        if (!CoopMovesConfig.get().enableCatch) return;
        if (!(event.getEntity() instanceof ServerPlayer fallingPlayer)) return;
        if (!event.getSource().is(DamageTypes.FALL)) return;
        ServerPlayer catcher = findCatcher(fallingPlayer);
        if (catcher != null) {
            playCatchEffects(fallingPlayer, catcher);
            successfulCatch.put(catcher.getUUID(), true);
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ queries

    public static boolean isInCatchReadyMode(UUID playerId) {
        return PoseNetworking.poseStates.getOrDefault(playerId, PoseState.NONE) == PoseState.GRAB_READY;
    }

    public static boolean isOnCatchCooldown(UUID playerId) {
        Long cooldownEnd = catchCooldowns.get(playerId);
        if (cooldownEnd == null) return false;
        if (System.currentTimeMillis() >= cooldownEnd) {
            catchCooldowns.remove(playerId);
            return false;
        }
        return true;
    }

    public static boolean canEnterCatchReady(UUID playerId) {
        return !isOnCatchCooldown(playerId);
    }

    private static ServerPlayer findCatcher(ServerPlayer fallingPlayer) {
        ServerLevel world = fallingPlayer.serverLevel();
        AABB searchBox = new AABB(
                fallingPlayer.getX() - CATCH_RANGE_HORIZONTAL, fallingPlayer.getY() - 2, fallingPlayer.getZ() - CATCH_RANGE_HORIZONTAL,
                fallingPlayer.getX() + CATCH_RANGE_HORIZONTAL, fallingPlayer.getY() + 1, fallingPlayer.getZ() + CATCH_RANGE_HORIZONTAL);
        for (Player player : world.players()) {
            if (player == fallingPlayer) continue;
            if (!(player instanceof ServerPlayer catcher)) continue;
            UUID catcherId = catcher.getUUID();
            PoseState pose = PoseNetworking.poseStates.getOrDefault(catcherId, PoseState.NONE);
            if (pose != PoseState.GRAB_READY) continue;
            if (isOnCatchCooldown(catcherId)) continue;
            if (!catchReadyTime.containsKey(catcherId)) continue;
            if (!searchBox.contains(catcher.position())) continue;
            double dx = fallingPlayer.getX() - catcher.getX();
            double dz = fallingPlayer.getZ() - catcher.getZ();
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);
            if (horizontalDist <= CATCH_RANGE_HORIZONTAL) return catcher;
        }
        return null;
    }

    private static void playCatchEffects(ServerPlayer caught, ServerPlayer catcher) {
        ServerLevel world = catcher.serverLevel();
        double x = (caught.getX() + catcher.getX()) / 2;
        double y = catcher.getY() + 1.5;
        double z = (caught.getZ() + catcher.getZ()) / 2;
        world.playSound(null, x, y, z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.2f, 0.7f);
        world.playSound(null, x, y, z, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0f, 1.2f);
        world.playSound(null, x, y, z, SoundEvents.WOOL_FALL, SoundSource.PLAYERS, 1.0f, 0.8f);
        world.sendParticles(ParticleTypes.CLOUD, x, y, z, 12, 0.4, 0.3, 0.4, 0.02);
        world.sendParticles(ParticleTypes.CRIT, x, y, z, 8, 0.3, 0.3, 0.3, 0.1);
        world.sendParticles(ParticleTypes.WAX_ON, x, y, z, 6, 0.2, 0.2, 0.2, 0.02);
        caught.setDeltaMovement(Vec3.ZERO);
        caught.hurtMarked = true;

        MinecraftServer server = catcher.getServer();
        if (server != null) {
            CatchAnimMsg msg = new CatchAnimMsg(catcher.getUUID(), caught.getUUID());
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                CoopNetwork.sendToPlayer(p, msg);
            }
            PoseNetworking.poseStates.put(catcher.getUUID(), PoseState.NONE);
            PoseNetworking.broadcastPoseChange(server, catcher.getUUID(), PoseState.NONE);
        }
        MechanicHudText.success(caught, "SAVED!", catcher.getName().getString() + " caught you");
        MechanicHudText.success(catcher, "PERFECT CATCH", caught.getName().getString() + " secured");
    }

    public static void cleanup(UUID playerId) {
        catchReadyTime.remove(playerId);
        catchCooldowns.remove(playerId);
        successfulCatch.remove(playerId);
    }

    // ------------------------------------------------------------------ networking

    public record CatchAnimMsg(UUID catcherId, UUID caughtId) {
        public static void encode(CatchAnimMsg m, FriendlyByteBuf buf) {
            buf.writeUUID(m.catcherId); buf.writeUUID(m.caughtId);
        }
        public static CatchAnimMsg decode(FriendlyByteBuf buf) {
            return new CatchAnimMsg(buf.readUUID(), buf.readUUID());
        }
        public static void handle(CatchAnimMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.CatchClientHandler.onCatchAnim(m.catcherId(), m.caughtId()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(CatchAnimMsg.class, CatchAnimMsg::encode, CatchAnimMsg::decode, CatchAnimMsg::handle);
    }
}
