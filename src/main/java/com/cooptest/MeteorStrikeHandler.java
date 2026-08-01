package com.cooptest;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Meteor Strike — unlocked by a perfect-legendary dap; press the dap key to call a
 * meteor that carves a crater and detonates. Ported from Fabric to Forge 1.20.1.
 *
 * grantAbility(...) is invoked by ChargedDapHandler (core, ported later).
 * Translations: ServerWorld->ServerLevel, Vec3d->Vec3, Box->AABB, breakBlock->
 * destroyBlock, createExplosion->explode(Level.ExplosionInteraction), getRotationVec
 * ->getViewVector, BlockPos.ofFloored->containing, Vec3d.ofCenter->Vec3.atCenterOf,
 * CustomPayload->CoopNetwork messages.
 */
public final class MeteorStrikeHandler {

    private MeteorStrikeHandler() {}

    public static final long ABILITY_DURATION_MS = 60_000;
    public static final long COUNTDOWN_MS = 3_000;
    public static final int CRATER_RADIUS = 10;
    public static final int DAMAGE_RADIUS = 20;

    private static final Map<UUID, Long> abilityExpiry = new HashMap<>();
    private static final Map<UUID, PendingMeteor> pendingMeteors = new HashMap<>();

    private static class PendingMeteor {
        final UUID playerId;
        final BlockPos target;
        final long impactTime;
        final ServerLevel world;
        boolean invulnGranted = false;
        PendingMeteor(UUID playerId, BlockPos target, ServerLevel world) {
            this.playerId = playerId;
            this.target = target;
            this.world = world;
            this.impactTime = System.currentTimeMillis() + COUNTDOWN_MS;
        }
    }

    public static void register() { }

    public static void grantAbility(ServerPlayer p1, ServerPlayer p2) {
        long expiry = System.currentTimeMillis() + ABILITY_DURATION_MS;
        abilityExpiry.put(p1.getUUID(), expiry);
        abilityExpiry.put(p2.getUUID(), expiry);
        CoopNetwork.sendToPlayer(p1, new MeteorGrantMsg(expiry));
        CoopNetwork.sendToPlayer(p2, new MeteorGrantMsg(expiry));
        MinecraftServer server = p1.getServer();
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.displayClientMessage(Component.literal(
                        "§c☄ " + p1.getName().getString() + " §7and §c" + p2.getName().getString()
                                + " §7have unlocked §c§lMETEOR STRIKE§7! Press §lG§7 to fire!"), false);
            }
        }
    }

    public static boolean hasAbility(UUID id) { return abilityExpiry.containsKey(id); }

    public static void cleanup(UUID id) {
        abilityExpiry.remove(id);
        pendingMeteors.remove(id);
    }

    private static void onFire(ServerPlayer player) {
        UUID id = player.getUUID();
        if (!abilityExpiry.containsKey(id)) return;
        if (pendingMeteors.containsKey(id)) return;
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        BlockPos target = null;
        for (double d = 1.0; d <= 80.0; d += 0.5) {
            Vec3 point = eye.add(look.scale(d));
            BlockPos bp = BlockPos.containing(point);
            if (!player.serverLevel().getBlockState(bp).isAir()) { target = bp; break; }
        }
        if (target == null) target = BlockPos.containing(eye.add(look.scale(80.0)));
        pendingMeteors.put(id, new PendingMeteor(id, target, player.serverLevel()));
        Vec3 targetCenter = Vec3.atCenterOf(target);
        for (double d = 0; d < eye.distanceTo(targetCenter); d += 1.0) {
            Vec3 point = eye.add(look.scale(d));
            player.serverLevel().sendParticles(ParticleTypes.CRIT, point.x, point.y, point.z, 1, 0, 0, 0, 0);
        }
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.WITHER_SHOOT, SoundSource.PLAYERS, 2.0f, 0.5f);
        player.displayClientMessage(Component.literal("§c☄ METEOR INCOMING §7— impact in 3 seconds!"), true);
    }

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        abilityExpiry.entrySet().removeIf(e -> {
            if (now >= e.getValue()) {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p != null) CoopNetwork.sendToPlayer(p, new MeteorExpiredMsg());
                pendingMeteors.remove(e.getKey());
                return true;
            }
            return false;
        });
        for (Iterator<PendingMeteor> it = new ArrayList<>(pendingMeteors.values()).iterator(); it.hasNext();) {
            PendingMeteor m = it.next();
            long msLeft = m.impactTime - now;
            if (msLeft <= 500 && !m.invulnGranted) {
                m.invulnGranted = true;
                ServerPlayer p = server.getPlayerList().getPlayer(m.playerId);
                if (p != null) p.setInvulnerable(true);
            }
            if (server.getTickCount() % 2 == 0) {
                spawnCountdownPillar(m.world, m.target, (float) msLeft / COUNTDOWN_MS);
            }
            if (now >= m.impactTime) {
                pendingMeteors.remove(m.playerId);
                abilityExpiry.remove(m.playerId);
                ServerPlayer p = server.getPlayerList().getPlayer(m.playerId);
                impact(m.world, m.target, p);
                if (p != null) {
                    CoopNetwork.sendToPlayer(p, new MeteorExpiredMsg());
                    final ServerPlayer fp = p;
                    server.execute(() -> fp.setInvulnerable(false));
                }
            } else {
                ServerPlayer p = server.getPlayerList().getPlayer(m.playerId);
                if (p != null) {
                    long abilityLeft = Math.max(0, abilityExpiry.getOrDefault(m.playerId, 0L) - now);
                    CoopNetwork.sendToPlayer(p, new MeteorStatusMsg(abilityLeft, msLeft));
                }
            }
        }
        for (Map.Entry<UUID, Long> e : abilityExpiry.entrySet()) {
            if (!pendingMeteors.containsKey(e.getKey())) {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p != null && server.getTickCount() % 5 == 0) {
                    long abilityLeft = Math.max(0, e.getValue() - now);
                    CoopNetwork.sendToPlayer(p, new MeteorStatusMsg(abilityLeft, -1));
                }
            }
        }
    }

    private static void spawnCountdownPillar(ServerLevel world, BlockPos target, float progress) {
        double x = target.getX() + 0.5, z = target.getZ() + 0.5;
        int height = (int) (50 * progress) + 5;
        for (int y = 0; y < height; y += 3) {
            world.sendParticles(ParticleTypes.FLAME, x, target.getY() + y, z, 1, 0.3, 0, 0.3, 0.05);
        }
        for (int i = 0; i < 12; i++) {
            double angle = Math.toRadians(i * 30.0 + (System.currentTimeMillis() / 100.0 % 360));
            double radius = 3.0 * progress + 0.5;
            world.sendParticles(ParticleTypes.CRIT,
                    x + Math.cos(angle) * radius, target.getY() + 0.5, z + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
        }
        if (progress < 0.5f) {
            world.playSound(null, x, target.getY(), z,
                    SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 1.5f, 0.5f + (1.0f - progress));
        }
    }

    private static void impact(ServerLevel world, BlockPos target, ServerPlayer shooter) {
        Vec3 center = Vec3.atCenterOf(target);
        int r = CRATER_RADIUS;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    if (x * x + y * y + z * z > r * r) continue;
                    BlockPos bp = target.offset(x, y, z);
                    var state = world.getBlockState(bp);
                    if (state.isAir()) continue;
                    if (state.getBlock() == Blocks.BEDROCK) continue;
                    world.destroyBlock(bp, false);
                }
            }
        }
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45.0);
            double ex = center.x + Math.cos(angle) * 12;
            double ez = center.z + Math.sin(angle) * 12;
            world.explode(shooter, ex, center.y, ez, 12.0f, true, Level.ExplosionInteraction.TNT);
        }
        world.explode(shooter, center.x, center.y, center.z, 20.0f, true, Level.ExplosionInteraction.TNT);
        AABB hitBox = new AABB(center, center).inflate(DAMAGE_RADIUS);
        for (var e : world.getEntities(shooter, hitBox)) {
            if (!(e instanceof LivingEntity living)) continue;
            double dist = e.position().distanceTo(center);
            if (dist > DAMAGE_RADIUS) continue;
            float dmg = (float) (25.0 * (1.0 - dist / DAMAGE_RADIUS));
            living.hurt(world.damageSources().explosion(null, shooter), dmg);
            Vec3 dir = e.position().subtract(center).normalize();
            if (dir.lengthSqr() < 0.001) dir = new Vec3(0, 1, 0);
            living.setDeltaMovement(living.getDeltaMovement().add(dir.x * 3.0, 2.0, dir.z * 3.0));
            living.hurtMarked = true;
        }
        world.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y, center.z, 20, 5, 5, 5, 0);
        world.sendParticles(ParticleTypes.FLAME, center.x, center.y, center.z, 200, 8, 4, 8, 0.5);
        world.playSound(null, center.x, center.y, center.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 5.0f, 0.3f);
        world.playSound(null, center.x, center.y, center.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 5.0f, 0.5f);
    }

    // ------------------------------------------------------------------ networking

    public record MeteorFireMsg() {
        public static void encode(MeteorFireMsg m, FriendlyByteBuf buf) { }
        public static MeteorFireMsg decode(FriendlyByteBuf buf) { return new MeteorFireMsg(); }
        public static void handle(MeteorFireMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player != null) onFire(player);
            });
            c.setPacketHandled(true);
        }
    }

    public record MeteorGrantMsg(long expiryMs) {
        public static void encode(MeteorGrantMsg m, FriendlyByteBuf buf) { buf.writeLong(m.expiryMs); }
        public static MeteorGrantMsg decode(FriendlyByteBuf buf) { return new MeteorGrantMsg(buf.readLong()); }
        public static void handle(MeteorGrantMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.MeteorStrikeClientHandler.onGrant(m.expiryMs()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    public record MeteorStatusMsg(long remainingAbilityMs, long countdownMs) {
        public static void encode(MeteorStatusMsg m, FriendlyByteBuf buf) {
            buf.writeLong(m.remainingAbilityMs); buf.writeLong(m.countdownMs);
        }
        public static MeteorStatusMsg decode(FriendlyByteBuf buf) {
            return new MeteorStatusMsg(buf.readLong(), buf.readLong());
        }
        public static void handle(MeteorStatusMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.MeteorStrikeClientHandler.onStatus(m.remainingAbilityMs(), m.countdownMs()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    public record MeteorExpiredMsg() {
        public static void encode(MeteorExpiredMsg m, FriendlyByteBuf buf) { }
        public static MeteorExpiredMsg decode(FriendlyByteBuf buf) { return new MeteorExpiredMsg(); }
        public static void handle(MeteorExpiredMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.MeteorStrikeClientHandler.onExpired());
                }
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(MeteorFireMsg.class, MeteorFireMsg::encode, MeteorFireMsg::decode, MeteorFireMsg::handle);
        CoopNetwork.register(MeteorGrantMsg.class, MeteorGrantMsg::encode, MeteorGrantMsg::decode, MeteorGrantMsg::handle);
        CoopNetwork.register(MeteorStatusMsg.class, MeteorStatusMsg::encode, MeteorStatusMsg::decode, MeteorStatusMsg::handle);
        CoopNetwork.register(MeteorExpiredMsg.class, MeteorExpiredMsg::encode, MeteorExpiredMsg::decode, MeteorExpiredMsg::handle);
    }

    public static void sendMeteorFire() {
        CoopNetwork.sendToServer(new MeteorFireMsg());
    }
}
