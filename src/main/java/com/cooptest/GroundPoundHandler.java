package com.cooptest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.*;
import java.util.function.Supplier;

/**
 * Ground Pound — while airborne, dive straight down for an AOE slam. Ported from
 * Fabric to Forge 1.20.1. The spin-rider combo integration (ground-pounding while
 * spinning a grabbed player) is reduced out (the reduced SpinHandler has no rider
 * hooks); the standalone dive + impact is fully preserved. Anims DIVE=65, LAND=66.
 */
public final class GroundPoundHandler {

    private GroundPoundHandler() {}

    private static final double DIVE_SPEED = -3.5;
    private static final double AOE_RADIUS = 8.0;
    private static final double KB_STRENGTH = 2.2;
    private static final long LAND_STUN_MS = 500L;
    private static final int MIN_HEIGHT_BLOCKS = 3;
    /** Minimum blocks the player must plunge for the slam to explode/break blocks.
     *  Below this it is just a soft landing (no explosion, no block damage). */
    private static final double MIN_EXPLOSION_HEIGHT = 10.0;
    /** Plunging this far (blocks) turns the slam into a MEGA pound automatically. */
    private static final double MEGA_EXPLOSION_HEIGHT = 15.0;
    private static final int ANIM_DIVE = 65;
    private static final int ANIM_LAND = 66;
    private static final long MAX_DIVE_MS = 15_000L;

    public record GroundPoundStartMsg() {
        public static void encode(GroundPoundStartMsg m, FriendlyByteBuf buf) { }
        public static GroundPoundStartMsg decode(FriendlyByteBuf buf) { return new GroundPoundStartMsg(); }
        public static void handle(GroundPoundStartMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { ServerPlayer p = c.getSender(); if (p != null
                    && CoopMovesConfig.get().enableGroundPound) onStart(p); });
            c.setPacketHandled(true);
        }
    }

    public record GroundPoundSyncMsg(UUID playerId, boolean diving) {
        public static void encode(GroundPoundSyncMsg m, FriendlyByteBuf buf) { buf.writeUUID(m.playerId); buf.writeBoolean(m.diving); }
        public static GroundPoundSyncMsg decode(FriendlyByteBuf buf) { return new GroundPoundSyncMsg(buf.readUUID(), buf.readBoolean()); }
        public static void handle(GroundPoundSyncMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer())
                    net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () ->
                            com.cooptest.client.GroundPoundClientHandler.onSync(m.playerId(), m.diving()));
            });
            c.setPacketHandled(true);
        }
    }

    private static final Set<UUID> diving = new HashSet<>();
    private static final Map<UUID, Double> diveStartY = new HashMap<>();
    private static final Map<UUID, Long> landStunEnd = new HashMap<>();
    private static final Map<UUID, Long> diveStartTime = new HashMap<>();
    static final Set<UUID> megaPound = new HashSet<>();

    public static void register() { }

    public static void registerMessages() {
        CoopNetwork.register(GroundPoundStartMsg.class, GroundPoundStartMsg::encode, GroundPoundStartMsg::decode, GroundPoundStartMsg::handle);
        CoopNetwork.register(GroundPoundSyncMsg.class, GroundPoundSyncMsg::encode, GroundPoundSyncMsg::decode, GroundPoundSyncMsg::handle);
    }

    private static void onStart(ServerPlayer player) {
        if (!CoopMovesConfig.get().enableGroundPound) return;
        UUID id = player.getUUID();
        if (diving.contains(id)) return;
        if (GrabMechanic.heldBy.containsKey(id)) return;
        if (player.onGround() && !SpinHandler.isSpinning(id)) return;
        if (SpinHandler.isSpinning(id)) SpinHandler.stopSpinKeepRider(player.getServer(), id);
        diving.add(id);
        diveStartY.put(id, player.getY());
        diveStartTime.put(id, System.currentTimeMillis());
        player.setDeltaMovement(0, DIVE_SPEED, 0);
        player.hurtMarked = true;
        PoseNetworking.broadcastAnimState(player, ANIM_DIVE);
        broadcastDiveSync(player.getServer(), id, true);
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 1.0f, 0.6f);
    }

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        landStunEnd.entrySet().removeIf(e -> now >= e.getValue());

        Iterator<UUID> it = diving.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) { it.remove(); cleanupDiveMaps(id); continue; }
            player.fallDistance = 0f;
            Long dStart = diveStartTime.get(id);
            if (dStart != null && now - dStart > MAX_DIVE_MS) {
                it.remove(); cleanupDiveMaps(id);
                broadcastDiveSync(server, id, false);
                PoseNetworking.broadcastAnimState(player, 0);
                continue;
            }
            boolean landed = player.onGround() || player.isInWater() || isCloseToGroundFalling(player);
            if (landed) {
                it.remove();
                double heightFallen = Math.max(0, diveStartY.getOrDefault(id, player.getY()) - player.getY());
                cleanupDiveMaps(id);
                UUID riderId = SpinHandler.pendingGroundPoundRider.remove(id);
                if (riderId != null) SpinHandler.detachRiderByIds(player.getServer(), id, riderId);
                executeImpact(player, heightFallen);
                broadcastDiveSync(server, id, false);
                continue;
            }
            Vec3 vel = player.getDeltaMovement();
            player.setDeltaMovement(vel.x * 0.1, DIVE_SPEED, vel.z * 0.1);
            player.hurtMarked = true;
        }
    }

    private static void executeImpact(ServerPlayer player, double heightFallen) {
        ServerLevel world = player.serverLevel();
        Vec3 pos = player.position();
        UUID id = player.getUUID();
        // Mega pound triggers from the charged flag OR from a big plunge (>= 15 blocks).
        boolean isMega = megaPound.remove(id) || heightFallen >= MEGA_EXPLOSION_HEIGHT;

        // Require a real plunge (>= 10 blocks) before the slam explodes and breaks blocks.
        // A short drop lands softly instead. Mega pound (charged) always slams.
        if (!isMega && heightFallen < MIN_EXPLOSION_HEIGHT) {
            lightLanding(player, world, pos, id);
            return;
        }

        double rawPower = Math.min(1.0, heightFallen / 10.0);
        double scaledPower = 1.0 - Math.exp(-rawPower * 2.5);
        if (heightFallen < MIN_HEIGHT_BLOCKS) scaledPower *= 0.3;
        double megaMult = isMega ? 2.5 : 1.0;
        double aoeRadius = isMega ? 14.0 : AOE_RADIUS;
        double kbMult = (0.5 + scaledPower * 1.5) * megaMult;
        double upwardPop = (0.3 + scaledPower * 0.5) * (isMega ? 1.8 : 1.0);

        AABB aoeBox = player.getBoundingBox().inflate(aoeRadius);
        for (LivingEntity living : world.getEntitiesOfClass(LivingEntity.class, aoeBox, e -> e != player && !e.isRemoved())) {
            double dx = living.getX() - pos.x, dz = living.getZ() - pos.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > aoeRadius) continue;
            double falloff = 1.0 - (dist / aoeRadius);
            double nx = dist > 0.01 ? dx / dist : 0, nz = dist > 0.01 ? dz / dist : 0;
            living.setDeltaMovement(nx * KB_STRENGTH * falloff * kbMult, upwardPop * falloff, nz * KB_STRENGTH * falloff * kbMult);
            living.hurtMarked = true;
            double dmg = scaledPower * (isMega ? 8.0 : 4.0) * falloff;
            if (dmg > 0.5) living.hurt(world.damageSources().playerAttack(player), (float) dmg);
        }

        float explosionPower = isMega ? 6.0f : 3.5f;
        world.explode(player, pos.x, pos.y, pos.z, explosionPower, Level.ExplosionInteraction.TNT);

        int rings = (int) (scaledPower * 3) + 1;
        for (int ring = 1; ring <= rings; ring++) {
            double radius = ring * 2.0;
            int count = ring * 12;
            for (int i = 0; i < count; i++) {
                double angle = (Math.PI * 2 / count) * i;
                double rx = pos.x + Math.cos(angle) * radius, rz = pos.z + Math.sin(angle) * radius;
                world.sendParticles(ParticleTypes.EXPLOSION, rx, pos.y + 0.1, rz, 1, 0, 0, 0, 0);
                world.sendParticles(ParticleTypes.SWEEP_ATTACK, rx, pos.y + 0.1, rz, 1, 0, 0, 0, 0);
            }
        }
        world.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y + 0.3, pos.z, 2, 0.3, 0, 0.3, 0);
        world.sendParticles(ParticleTypes.CRIT, pos.x, pos.y + 0.5, pos.z, 20, 0.8, 0.5, 0.8, 0.2);
        world.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + 0.2, pos.z, 8, 0.4, 0.1, 0.4, 0.02);

        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS,
                0.8f + (float) scaledPower * 0.4f, 0.7f - (float) scaledPower * 0.2f);
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.9f, 0.6f);
        if (scaledPower > 0.5) world.playSound(null, pos.x, pos.y, pos.z, ModSounds.EXPLOSION_IMPACT.get(), SoundSource.PLAYERS, 1.0f, 0.8f);

        PoseNetworking.broadcastAnimState(player, ANIM_LAND);
        landStunEnd.put(id, System.currentTimeMillis() + LAND_STUN_MS);
        player.setDeltaMovement(0, 0, 0);
        player.hurtMarked = true;
        player.displayClientMessage(Component.literal(scaledPower >= 0.6 ? "§c§l GROUND POUND!" : "§e Ground Pound"), true);
    }

    /** Soft touch-down for dives shorter than {@link #MIN_EXPLOSION_HEIGHT} blocks:
     *  plays the land animation and a light puff, but no explosion / block breaking / AOE. */
    private static void lightLanding(ServerPlayer player, ServerLevel world, Vec3 pos, UUID id) {
        world.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y + 0.1, pos.z, 8, 0.4, 0.05, 0.4, 0.02);
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.5f, 1.1f);
        PoseNetworking.broadcastAnimState(player, ANIM_LAND);
        landStunEnd.put(id, System.currentTimeMillis() + LAND_STUN_MS);
        player.setDeltaMovement(0, 0, 0);
        player.hurtMarked = true;
    }

    private static boolean isCloseToGroundFalling(ServerPlayer player) {
        if (player.getDeltaMovement().y >= 0) return false;
        Vec3 pos = player.position();
        for (int i = 1; i <= 3; i++) {
            if (!player.serverLevel().getBlockState(BlockPos.containing(pos.x, pos.y - i * 0.5, pos.z)).isAir()) return true;
        }
        return false;
    }

    private static void broadcastDiveSync(MinecraftServer server, UUID id, boolean divingState) {
        GroundPoundSyncMsg pkt = new GroundPoundSyncMsg(id, divingState);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) CoopNetwork.sendToPlayer(p, pkt);
    }

    private static void cleanupDiveMaps(UUID id) { diveStartY.remove(id); diveStartTime.remove(id); }

    public static boolean isDiving(UUID id) { return diving.contains(id); }
    public static void markMegaPound(UUID id) { megaPound.add(id); }

    public static void cleanup(UUID id) {
        diving.remove(id);
        cleanupDiveMaps(id);
        landStunEnd.remove(id);
        megaPound.remove(id);
    }
}
