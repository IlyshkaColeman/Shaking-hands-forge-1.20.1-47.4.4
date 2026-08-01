package com.cooptest;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Kick / drop-kick mechanic. Ported from Fabric to Forge 1.20.1.
 *
 * Tap the kick key (T, routed from GrabInputHandler when free-handed) for a quick
 * kick; hold while sprinting to charge a drop-kick. Charge progress, cooldown and
 * hit results are synced to clients for HUD/feedback (HUD itself lands in Stage 6).
 *
 * Notable translations: EntityAttributeModifier is keyed by UUID (not Identifier)
 * in Mojmap; takeKnockback->knockback; getOtherEntities->getEntities;
 * damage->hurt; ServerWorld->ServerLevel; CustomPayload->CoopNetwork messages.
 */
public final class KickHandler {

    private KickHandler() {}

    public static final float   KICK_RANGE            = 2.0f;
    public static final float   DROP_KICK_RANGE       = 3.0f;
    public static final float   KICK_DAMAGE           = 2.0f;
    public static final float   DROP_KICK_DAMAGE      = 10.0f;
    public static final double  KICK_KB_STRENGTH      = 1.5;
    public static final double  DROP_KICK_KB_STRENGTH = 3.5;
    public static final long    CHARGE_TIME_MS        = 3000L;
    public static final long    KICK_COOLDOWN_MS      = 2000L;
    private static final double KICK_SLOW_AMOUNT      = -0.4;
    private static final double DROP_KICK_SLOW_AMOUNT = -0.9;
    private static final long   KICK_ANIM_MS          = 1000L;
    private static final long   DROP_KICK_ANIM_MS     = 1750L;
    private static final long   DROP_KICK_SLOW_DELAY  = 830L;

    /** Fixed UUID for the movement-speed modifier (Mojmap keys modifiers by UUID). */
    private static final UUID KICK_SLOW_ID = UUID.fromString("7b8f3c2a-1d4e-4a6b-9c2f-000000000001");
    private static final int ANIM_KICK      = 61;
    private static final int ANIM_DROP_KICK = 62;

    private static final Map<UUID, Long>    chargeStart        = new HashMap<>();
    private static final Map<UUID, Long>    cooldownEnd        = new HashMap<>();
    private static final Map<UUID, Integer> lastSyncTick       = new HashMap<>();
    private static final Map<UUID, Long>    slowApplyAt        = new HashMap<>();
    private static final Map<UUID, Long>    slowRemoveAt       = new HashMap<>();
    private static final Map<UUID, Double>  slowAmount         = new HashMap<>();
    private static final Map<UUID, Long>    kickPushWindowEnd  = new HashMap<>();
    private static final Map<UUID, Vec3>    kickPushFwd        = new HashMap<>();

    public static void register() { }

    // ------------------------------------------------------------------ C2S handlers

    private static void onKickStart(ServerPlayer player, boolean dropKickMode) {
        UUID id = player.getUUID();
        if (!CoopMovesConfig.get().enableKick) return;
        if (isOnCooldown(id)) return;
        if (dropKickMode) {
            if (!CoopMovesConfig.get().enableDropKick) {
                executeKick(player, false);
                return;
            }
            chargeStart.put(id, System.currentTimeMillis());
            broadcastChargeSync(player, true, 0f);
        } else {
            executeKick(player, false);
        }
    }

    private static void onKickRelease(ServerPlayer player) {
        UUID id = player.getUUID();
        Long start = chargeStart.remove(id);
        lastSyncTick.remove(id);
        if (start == null) return;
        boolean fullCharge = (System.currentTimeMillis() - start) >= CHARGE_TIME_MS;
        broadcastChargeSync(player, false, 0f);
        executeKick(player, fullCharge);
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        int tick = server.getTickCount();

        Iterator<Map.Entry<UUID, Long>> it = chargeStart.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            UUID id = entry.getKey();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) { it.remove(); lastSyncTick.remove(id); continue; }
            if (!player.isSprinting()) {
                it.remove();
                lastSyncTick.remove(id);
                broadcastChargeSync(player, false, 0f);
                CoopNetwork.sendToPlayer(player, new KickCooldownMsg(0L));
                continue;
            }
            int lastTick = lastSyncTick.getOrDefault(id, -999);
            if (tick - lastTick >= 2) {
                lastSyncTick.put(id, tick);
                float pct = Math.min(1f, (float) (now - entry.getValue()) / CHARGE_TIME_MS);
                broadcastChargeSync(player, true, pct);
            }
        }

        Iterator<Map.Entry<UUID, Long>> pushWindowIt = kickPushWindowEnd.entrySet().iterator();
        while (pushWindowIt.hasNext()) {
            Map.Entry<UUID, Long> entry = pushWindowIt.next();
            if (now >= entry.getValue()) { pushWindowIt.remove(); kickPushFwd.remove(entry.getKey()); continue; }
            UUID id = entry.getKey();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            Vec3 fwd = kickPushFwd.get(id);
            if (player == null || fwd == null) { pushWindowIt.remove(); continue; }
            float reach = (float) (KICK_RANGE + 0.5);
            AABB box = player.getBoundingBox().inflate(reach + 0.3);
            for (Entity target : player.serverLevel().getEntities(player, box,
                    e -> e instanceof LivingEntity && !e.isRemoved())) {
                double dx = target.getX() - player.getX(), dz = target.getZ() - player.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > reach) continue;
                double dot = (dist < 0.01) ? 1.0 : (fwd.x * dx + fwd.z * dz) / dist;
                if (dot < 0.2) continue;
                target.setDeltaMovement(target.getDeltaMovement().add(fwd.x * 0.12, 0.06, fwd.z * 0.12));
                target.hurtMarked = true;
            }
        }

        Iterator<Map.Entry<UUID, Long>> applyIt = slowApplyAt.entrySet().iterator();
        while (applyIt.hasNext()) {
            Map.Entry<UUID, Long> entry = applyIt.next();
            if (now >= entry.getValue()) {
                applyIt.remove();
                UUID id = entry.getKey();
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) applySlowdown(player, slowAmount.getOrDefault(id, DROP_KICK_SLOW_AMOUNT));
            }
        }

        Iterator<Map.Entry<UUID, Long>> removeIt = slowRemoveAt.entrySet().iterator();
        while (removeIt.hasNext()) {
            Map.Entry<UUID, Long> entry = removeIt.next();
            if (now >= entry.getValue()) {
                removeIt.remove();
                slowAmount.remove(entry.getKey());
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) removeSlowdown(player);
            }
        }
    }

    private static void executeKick(ServerPlayer player, boolean isDropKick) {
        UUID id = player.getUUID();
        ServerLevel world = player.serverLevel();
        float reach = isDropKick ? DROP_KICK_RANGE : KICK_RANGE;
        float damage = isDropKick ? DROP_KICK_DAMAGE : KICK_DAMAGE;
        double kbStrength = isDropKick ? DROP_KICK_KB_STRENGTH : KICK_KB_STRENGTH;
        double upwardPop = isDropKick ? 0.55 : 0.35;
        float yaw = player.getYRot();
        double fwdX = -Math.sin(Math.toRadians(yaw));
        double fwdZ = Math.cos(Math.toRadians(yaw));
        AABB searchBox = player.getBoundingBox().inflate(reach + 0.5);
        List<Entity> candidates = world.getEntities(player, searchBox,
                e -> e instanceof LivingEntity && !e.isRemoved() && !e.isSpectator());
        boolean hitAny = false;
        List<ServerPlayer> playerHits = new ArrayList<>();
        for (Entity target : candidates) {
            double dx = target.getX() - player.getX();
            double dz = target.getZ() - player.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > reach) continue;
            double dot = (dist < 0.01) ? 1.0 : (fwdX * dx + fwdZ * dz) / dist;
            if (dot < 0.25) continue;
            target.hurt(world.damageSources().playerAttack(player), damage);
            if (target instanceof LivingEntity living) {
                if (isDropKick) {
                    living.setDeltaMovement(fwdX * 4.0, 0.8, fwdZ * 4.0);
                } else {
                    living.knockback(kbStrength, -dx, -dz);
                    Vec3 vel2 = living.getDeltaMovement();
                    living.setDeltaMovement(vel2.x, upwardPop, vel2.z);
                }
                living.hurtMarked = true;
            }
            if (target instanceof ServerPlayer hitPlayer) {
                playerHits.add(hitPlayer);
            }
            Vec3 hitPos = player.position().add(target.position()).scale(0.5)
                    .add(0, player.getBbHeight() * 0.55, 0);
            if (isDropKick) {
                world.sendParticles(ParticleTypes.EXPLOSION, hitPos.x, hitPos.y, hitPos.z, 1, 0.2, 0.2, 0.2, 0.0);
                world.sendParticles(ParticleTypes.CRIT, hitPos.x, hitPos.y, hitPos.z, 5, 0.3, 0.3, 0.3, 0.15);
                world.sendParticles(ParticleTypes.ENCHANTED_HIT, hitPos.x, hitPos.y, hitPos.z, 4, 0.2, 0.2, 0.2, 0.1);
            } else {
                world.sendParticles(ParticleTypes.SWEEP_ATTACK, hitPos.x, hitPos.y, hitPos.z, 5, 0.2, 0.2, 0.2, 0.1);
                world.sendParticles(ParticleTypes.CRIT, hitPos.x, hitPos.y, hitPos.z, 8, 0.2, 0.2, 0.2, 0.1);
            }
            hitAny = true;
        }

        if (isDropKick && playerHits.size() == 1) {
            ServerPlayer other = playerHits.get(0);
            Vec3 mid = player.position().add(other.position()).scale(0.5).add(0, 1.0, 0);
            world.sendParticles(ParticleTypes.EXPLOSION, mid.x, mid.y, mid.z, 2, 0.3, 0.3, 0.3, 0.0);
            world.sendParticles(ParticleTypes.EXPLOSION_EMITTER, mid.x, mid.y, mid.z, 1, 0.2, 0.2, 0.2, 0.0);
            world.sendParticles(ParticleTypes.CRIT, mid.x, mid.y, mid.z, 8, 0.4, 0.4, 0.4, 0.2);
            world.sendParticles(ParticleTypes.ENCHANTED_HIT, mid.x, mid.y, mid.z, 6, 0.3, 0.3, 0.3, 0.15);
            world.sendParticles(ParticleTypes.LARGE_SMOKE, mid.x, mid.y, mid.z, 4, 0.3, 0.3, 0.3, 0.02);
            for (int i = 0; i < 8; i++) {
                double angle = (Math.PI * 2 / 8) * i;
                double rx = mid.x + Math.cos(angle) * 1.5;
                double rz = mid.z + Math.sin(angle) * 1.5;
                world.sendParticles(ParticleTypes.SWEEP_ATTACK, rx, mid.y - 0.8, rz, 1, 0, 0, 0, 0);
            }
            world.playSound(null, mid.x, mid.y, mid.z,
                    SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.2f, 0.8f);
            world.playSound(null, mid.x, mid.y, mid.z,
                    ModSounds.EXPLOSION_IMPACT.get(), SoundSource.PLAYERS, 1.0f, 0.7f);
            hitAny = false;
        }

        if (hitAny && isDropKick) {
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 1.0f, 1.0f);
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    ModSounds.EXPLOSION_IMPACT.get(), SoundSource.PLAYERS, 0.9f, 0.9f);
        } else if (!hitAny) {
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.PLAYERS, 0.4f, 1.2f);
        }

        PoseNetworking.broadcastAnimState(player, isDropKick ? ANIM_DROP_KICK : ANIM_KICK);
        long now = System.currentTimeMillis();
        kickPushWindowEnd.put(id, now + 540L);
        kickPushFwd.put(id, new Vec3(fwdX, 0, fwdZ));
        if (!isDropKick) {
            player.setDeltaMovement(player.getDeltaMovement().add(fwdX * 0.3, 0, fwdZ * 0.3));
            player.hurtMarked = true;
            applySlowdown(player, KICK_SLOW_AMOUNT);
            slowRemoveAt.put(id, now + KICK_ANIM_MS);
        } else {
            player.setDeltaMovement(player.getDeltaMovement().add(fwdX * 0.5, 0, fwdZ * 0.5));
            player.hurtMarked = true;
            slowApplyAt.put(id, now + DROP_KICK_SLOW_DELAY);
            slowRemoveAt.put(id, now + DROP_KICK_ANIM_MS);
            slowAmount.put(id, DROP_KICK_SLOW_AMOUNT);
        }
        CoopNetwork.sendToPlayer(player, new KickResultMsg(isDropKick, hitAny));
        cooldownEnd.put(id, now + KICK_COOLDOWN_MS);
        CoopNetwork.sendToPlayer(player, new KickCooldownMsg(KICK_COOLDOWN_MS));
    }

    private static void applySlowdown(ServerPlayer player, double amount) {
        AttributeInstance attr = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr == null) return;
        attr.removeModifier(KICK_SLOW_ID);
        attr.addPermanentModifier(new AttributeModifier(
                KICK_SLOW_ID, "coop_kick_slow", amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    private static void removeSlowdown(ServerPlayer player) {
        AttributeInstance attr = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr != null) attr.removeModifier(KICK_SLOW_ID);
    }

    private static boolean isOnCooldown(UUID id) {
        Long end = cooldownEnd.get(id);
        return end != null && System.currentTimeMillis() < end;
    }

    private static void broadcastChargeSync(ServerPlayer player, boolean isCharging, float pct) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        KickChargeSyncMsg pkt = new KickChargeSyncMsg(player.getUUID(), isCharging, pct);
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            CoopNetwork.sendToPlayer(other, pkt);
        }
    }

    public static void cleanup(UUID id) {
        chargeStart.remove(id);
        cooldownEnd.remove(id);
        lastSyncTick.remove(id);
        slowApplyAt.remove(id);
        slowRemoveAt.remove(id);
        slowAmount.remove(id);
        kickPushWindowEnd.remove(id);
        kickPushFwd.remove(id);
    }

    // ------------------------------------------------------------------ networking

    public record KickStartMsg(boolean dropKickMode) {
        public static void encode(KickStartMsg m, FriendlyByteBuf buf) { buf.writeBoolean(m.dropKickMode); }
        public static KickStartMsg decode(FriendlyByteBuf buf) { return new KickStartMsg(buf.readBoolean()); }
        public static void handle(KickStartMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player != null) onKickStart(player, m.dropKickMode());
            });
            c.setPacketHandled(true);
        }
    }

    public record KickReleaseMsg() {
        public static void encode(KickReleaseMsg m, FriendlyByteBuf buf) { }
        public static KickReleaseMsg decode(FriendlyByteBuf buf) { return new KickReleaseMsg(); }
        public static void handle(KickReleaseMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player != null) onKickRelease(player);
            });
            c.setPacketHandled(true);
        }
    }

    public record KickChargeSyncMsg(UUID playerId, boolean isCharging, float chargePercent) {
        public static void encode(KickChargeSyncMsg m, FriendlyByteBuf buf) {
            buf.writeUUID(m.playerId); buf.writeBoolean(m.isCharging); buf.writeFloat(m.chargePercent);
        }
        public static KickChargeSyncMsg decode(FriendlyByteBuf buf) {
            return new KickChargeSyncMsg(buf.readUUID(), buf.readBoolean(), buf.readFloat());
        }
        public static void handle(KickChargeSyncMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.KickClientHandler.onChargeSync(m.playerId(), m.isCharging(), m.chargePercent()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    public record KickCooldownMsg(long cooldownMs) {
        public static void encode(KickCooldownMsg m, FriendlyByteBuf buf) { buf.writeLong(m.cooldownMs); }
        public static KickCooldownMsg decode(FriendlyByteBuf buf) { return new KickCooldownMsg(buf.readLong()); }
        public static void handle(KickCooldownMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.KickClientHandler.onCooldown(m.cooldownMs()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    public record KickResultMsg(boolean isDropKick, boolean hit) {
        public static void encode(KickResultMsg m, FriendlyByteBuf buf) {
            buf.writeBoolean(m.isDropKick); buf.writeBoolean(m.hit);
        }
        public static KickResultMsg decode(FriendlyByteBuf buf) {
            return new KickResultMsg(buf.readBoolean(), buf.readBoolean());
        }
        public static void handle(KickResultMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.KickClientHandler.onResult(m.isDropKick(), m.hit()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(KickStartMsg.class, KickStartMsg::encode, KickStartMsg::decode, KickStartMsg::handle);
        CoopNetwork.register(KickReleaseMsg.class, KickReleaseMsg::encode, KickReleaseMsg::decode, KickReleaseMsg::handle);
        CoopNetwork.register(KickChargeSyncMsg.class, KickChargeSyncMsg::encode, KickChargeSyncMsg::decode, KickChargeSyncMsg::handle);
        CoopNetwork.register(KickCooldownMsg.class, KickCooldownMsg::encode, KickCooldownMsg::decode, KickCooldownMsg::handle);
        CoopNetwork.register(KickResultMsg.class, KickResultMsg::encode, KickResultMsg::decode, KickResultMsg::handle);
    }

    public static void sendKickStart(boolean dropKickMode) { CoopNetwork.sendToServer(new KickStartMsg(dropKickMode)); }
    public static void sendKickRelease() { CoopNetwork.sendToServer(new KickReleaseMsg()); }
}
