package com.cooptest;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.*;
import java.util.function.Supplier;

/**
 * Spin (helicopter) — while thrown and airborne, spin to sweep up a grounded player as
 * a passenger, then launch/mega-ground-pound. Ported from Fabric to Forge 1.20.1.
 * Trigger is in GrabInputHandler (sneak while thrown-airborne). Anim SPIN=64.
 */
public final class SpinHandler {

    private SpinHandler() {}

    private static final float YAW_PER_TICK = 22.0f;
    private static final double GRAVITY_CAP = -0.15;
    private static final long MAX_SPIN_MS = 30000L;
    private static final int SOUND_INTERVAL = 12;
    private static final double HELICOPTER_H_RANGE = 2.5;
    private static final double HELICOPTER_V_RANGE = 2.0;
    private static final int ANIM_SPIN = 64;
    private static final int ANIM_NONE = 0;

    public record SpinStartMsg() {
        public static void encode(SpinStartMsg m, FriendlyByteBuf buf) { }
        public static SpinStartMsg decode(FriendlyByteBuf buf) { return new SpinStartMsg(); }
        public static void handle(SpinStartMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { ServerPlayer p = c.getSender(); if (p != null) onSpinStart(p); });
            c.setPacketHandled(true);
        }
    }

    public record SpinStopMsg() {
        public static void encode(SpinStopMsg m, FriendlyByteBuf buf) { }
        public static SpinStopMsg decode(FriendlyByteBuf buf) { return new SpinStopMsg(); }
        public static void handle(SpinStopMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { ServerPlayer p = c.getSender(); if (p != null) stopSpin(p.getServer(), p.getUUID()); });
            c.setPacketHandled(true);
        }
    }

    public record SpinSyncMsg(UUID playerId, boolean spinning) {
        public static void encode(SpinSyncMsg m, FriendlyByteBuf buf) { buf.writeUUID(m.playerId); buf.writeBoolean(m.spinning); }
        public static SpinSyncMsg decode(FriendlyByteBuf buf) { return new SpinSyncMsg(buf.readUUID(), buf.readBoolean()); }
        public static void handle(SpinSyncMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer())
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.SpinClientHandler.onSync(m.playerId(), m.spinning()));
            });
            c.setPacketHandled(true);
        }
    }

    public record HelicopterLaunchMsg(UUID spinnerId, UUID riderId) {
        public static void encode(HelicopterLaunchMsg m, FriendlyByteBuf buf) { buf.writeUUID(m.spinnerId); buf.writeUUID(m.riderId); }
        public static HelicopterLaunchMsg decode(FriendlyByteBuf buf) { return new HelicopterLaunchMsg(buf.readUUID(), buf.readUUID()); }
        public static void handle(HelicopterLaunchMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer())
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.SpinClientHandler.onLaunch(m.spinnerId(), m.riderId()));
            });
            c.setPacketHandled(true);
        }
    }

    private static final Set<UUID> activeSpin = new HashSet<>();
    private static final Map<UUID, Long> spinStartTime = new HashMap<>();
    private static final Map<UUID, Float> spinYaw = new HashMap<>();
    private static final Map<UUID, Integer> spinTick = new HashMap<>();
    private static final Map<UUID, UUID> helicopterRider = new HashMap<>();
    private static final Map<UUID, UUID> helicopterSpinner = new HashMap<>();
    static final Map<UUID, UUID> pendingGroundPoundRider = new HashMap<>();

    public static void register() { }

    public static void registerMessages() {
        CoopNetwork.register(SpinStartMsg.class, SpinStartMsg::encode, SpinStartMsg::decode, SpinStartMsg::handle);
        CoopNetwork.register(SpinStopMsg.class, SpinStopMsg::encode, SpinStopMsg::decode, SpinStopMsg::handle);
        CoopNetwork.register(SpinSyncMsg.class, SpinSyncMsg::encode, SpinSyncMsg::decode, SpinSyncMsg::handle);
        CoopNetwork.register(HelicopterLaunchMsg.class, HelicopterLaunchMsg::encode, HelicopterLaunchMsg::decode, HelicopterLaunchMsg::handle);
    }

    private static void onSpinStart(ServerPlayer player) {
        if (!CoopMovesConfig.get().enableSpin) return;
        UUID id = player.getUUID();
        if (PoseNetworking.poseStates.getOrDefault(id, PoseState.NONE) != PoseState.GRABBED) return;
        if (GrabMechanic.heldBy.containsKey(id)) return;
        if (activeSpin.contains(id)) return;
        activeSpin.add(id);
        spinStartTime.put(id, System.currentTimeMillis());
        spinYaw.put(id, player.yBodyRot);
        spinTick.put(id, 0);
        DapHoldHandler.forceUnfreeze(player.getServer(), id);
        PoseNetworking.broadcastAnimState(player, ANIM_NONE);
        PoseNetworking.broadcastAnimState(player, ANIM_SPIN);
        broadcastSpinSync(player.getServer(), id, true);
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.8f, 1.6f);
    }

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        Iterator<UUID> it = activeSpin.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) { it.remove(); cleanupSpinMaps(id); continue; }
            boolean stop = GrabMechanic.heldBy.containsKey(id) || player.onGround() || player.isInWater()
                    || (now - spinStartTime.getOrDefault(id, now) >= MAX_SPIN_MS);
            if (stop) {
                it.remove(); cleanupSpinMaps(id);
                detachHelicopterRider(server, id);
                broadcastSpinSync(server, id, false);
                PoseNetworking.broadcastAnimState(player, ANIM_NONE);
                continue;
            }
            boolean hasRider = helicopterRider.containsKey(id);
            float currentYaw = spinYaw.getOrDefault(id, player.yBodyRot);
            if (!hasRider) {
                float newYaw = currentYaw + YAW_PER_TICK;
                if (newYaw > 180f) newYaw -= 360f;
                spinYaw.put(id, newYaw);
                currentYaw = newYaw;
                player.setYRot(currentYaw); player.setYBodyRot(currentYaw); player.setYHeadRot(currentYaw);
            }
            Vec3 vel = player.getDeltaMovement();
            if (vel.y < GRAVITY_CAP) { player.setDeltaMovement(vel.x, GRAVITY_CAP, vel.z); player.hurtMarked = true; }
            Vec3 pos = player.position().add(0, 0.9, 0);
            double angle = Math.toRadians(currentYaw);
            player.serverLevel().sendParticles(ParticleTypes.CLOUD,
                    pos.x + Math.cos(angle) * 0.5, pos.y, pos.z + Math.sin(angle) * 0.5, 2, 0.05, 0.05, 0.05, 0.01);
            int tck = spinTick.merge(id, 1, Integer::sum);
            if (tck % SOUND_INTERVAL == 0)
                player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 0.5f, 1.4f);
            if (!hasRider) checkHelicopterSweep(server, player);
        }
    }

    private static void checkHelicopterSweep(MinecraftServer server, ServerPlayer spinner) {
        UUID spinnerId = spinner.getUUID();
        Vec3 sPos = spinner.position();
        ServerLevel world = spinner.serverLevel();
        AABB sweepBox = new AABB(sPos.x - HELICOPTER_H_RANGE, sPos.y - 0.5, sPos.z - HELICOPTER_H_RANGE,
                sPos.x + HELICOPTER_H_RANGE, sPos.y + HELICOPTER_V_RANGE, sPos.z + HELICOPTER_H_RANGE);
        for (ServerPlayer target : world.getEntitiesOfClass(ServerPlayer.class, sweepBox)) {
            if (target == spinner || target.isSpectator()) continue;
            UUID targetId = target.getUUID();
            if (!target.onGround()) continue;
            if (helicopterSpinner.containsKey(targetId)) continue;
            if (GrabMechanic.holding.containsKey(spinnerId)) continue;
            if (GrabMechanic.heldBy.containsKey(targetId)) continue;
            target.startRiding(spinner, true);
            helicopterRider.put(spinnerId, targetId);
            helicopterSpinner.put(targetId, spinnerId);
            ClientboundSetPassengersPacket pkt = new ClientboundSetPassengersPacket(spinner);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) p.connection.send(pkt);
            spinner.setDeltaMovement(0, 4.0, 0);
            spinner.hurtMarked = true;
            GroundPoundHandler.markMegaPound(spinnerId);
            HelicopterLaunchMsg launch = new HelicopterLaunchMsg(spinnerId, targetId);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) CoopNetwork.sendToPlayer(p, launch);
            world.sendParticles(ParticleTypes.SWEEP_ATTACK, sPos.x, sPos.y + 1.2, sPos.z, 8, 0.4, 0.2, 0.4, 0.1);
            world.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, sPos.x, sPos.y + 0.5, sPos.z, 15, 0.5, 0.5, 0.5, 0.3);
            world.playSound(null, sPos.x, sPos.y, sPos.z, ModSounds.HELI.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
            world.playSound(null, sPos.x, sPos.y, sPos.z, ModSounds.EXPLOSION_IMPACT.get(), SoundSource.PLAYERS, 0.8f, 0.5f);
            spinner.displayClientMessage(Component.literal("§c§l🚀 HELICOPTER! Press SHIFT for MEGA GROUND POUND!"), true);
            target.displayClientMessage(Component.literal("§c§l🚀 You're riding the helicopter!"), true);
            break;
        }
    }

    private static void detachHelicopterRider(MinecraftServer server, UUID spinnerId) {
        UUID riderId = helicopterRider.remove(spinnerId);
        if (riderId == null) return;
        helicopterSpinner.remove(riderId);
        ServerPlayer rider = server.getPlayerList().getPlayer(riderId);
        ServerPlayer spinner = server.getPlayerList().getPlayer(spinnerId);
        if (rider != null) { rider.stopRiding(); rider.teleportTo(rider.serverLevel(), rider.getX(), rider.getY(), rider.getZ(), rider.getYRot(), rider.getXRot()); }
        sendPassengers(server, spinner);
        sendPassengers(server, rider);
    }

    public static void stopSpinKeepRider(MinecraftServer server, UUID id) {
        if (!activeSpin.remove(id)) return;
        UUID riderId = helicopterRider.remove(id);
        if (riderId != null) { helicopterSpinner.remove(riderId); pendingGroundPoundRider.put(id, riderId); }
        cleanupSpinMaps(id);
        broadcastSpinSync(server, id, false);
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null) PoseNetworking.broadcastAnimState(player, ANIM_NONE);
    }

    public static void stopSpin(MinecraftServer server, UUID id) {
        if (!activeSpin.remove(id)) return;
        cleanupSpinMaps(id);
        detachHelicopterRider(server, id);
        broadcastSpinSync(server, id, false);
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null) PoseNetworking.broadcastAnimState(player, ANIM_NONE);
    }

    public static void detachRiderByIds(MinecraftServer server, UUID spinnerId, UUID riderId) {
        helicopterSpinner.remove(riderId);
        ServerPlayer rider = server.getPlayerList().getPlayer(riderId);
        ServerPlayer spinner = server.getPlayerList().getPlayer(spinnerId);
        if (rider != null) { rider.stopRiding(); rider.teleportTo(rider.serverLevel(), rider.getX(), rider.getY(), rider.getZ(), rider.getYRot(), rider.getXRot()); }
        sendPassengers(server, spinner);
        sendPassengers(server, rider);
    }

    private static void sendPassengers(MinecraftServer server, ServerPlayer entity) {
        if (entity == null) return;
        ClientboundSetPassengersPacket pkt = new ClientboundSetPassengersPacket(entity);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) p.connection.send(pkt);
    }

    public static boolean isSpinning(UUID id) { return activeSpin.contains(id); }
    public static boolean hasHelicopterRider(UUID spinnerId) { return helicopterRider.containsKey(spinnerId); }

    private static void cleanupSpinMaps(UUID id) { spinStartTime.remove(id); spinYaw.remove(id); spinTick.remove(id); }

    private static void broadcastSpinSync(MinecraftServer server, UUID id, boolean spinning) {
        SpinSyncMsg pkt = new SpinSyncMsg(id, spinning);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) CoopNetwork.sendToPlayer(p, pkt);
    }

    public static void cleanup(UUID id) {
        activeSpin.remove(id);
        cleanupSpinMaps(id);
        UUID riderId = helicopterRider.remove(id);
        if (riderId != null) helicopterSpinner.remove(riderId);
        helicopterSpinner.remove(id);
        pendingGroundPoundRider.remove(id);
    }
}
