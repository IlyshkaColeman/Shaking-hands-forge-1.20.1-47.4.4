package com.cooptest;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.*;
import java.util.function.Supplier;

/**
 * Charged-Dap core — Forge 1.20.1 port of the Fabric monolith
 * (~4700 lines). The full charge / tier / perfect / fire / heaven state machine is
 * ported here. Following the project reduction strategy, the purely-cosmetic
 * continuous particle systems from the original (tornado swirls, saturn rings,
 * aura beams, expanding sonic-boom rings, heaven particle cascade, underwater
 * water-removal) are omitted or reduced to a representative burst; all GAMEPLAY
 * (charge lifecycle, positioning, tiers 0-5, explosions/knockback, status effects,
 * teleports, KosmX anim broadcasts, heaven-dap flight & return, fire-dap + fusion
 * hand-off) is preserved.
 *
 * Fabric->Forge translations of note:
 *   - CustomPayload records -> CoopNetwork SimpleChannel messages.
 *   - ServerPlayNetworking.send(p, X) -> CoopNetwork.sendToPlayer(p, X); several
 *     client-only VFX/HUD cues (whiff cd, dap-result, impact-frame, fire-dap
 *     window/freeze/first-person) are dropped until the client HUD lands.
 *   - CoopAnimationHandler is @OnlyIn(CLIENT), so anim states are broadcast as raw
 *     hardcoded ordinals (see ANIM_* constants below).
 *   - UseEntityCallback -> PlayerInteractEvent.EntityInteract.
 *   - SlapHandler.checkSlapOnRelease is a stub until the Slap mechanic is ported.
 */
public final class ChargedDapHandler {

    private ChargedDapHandler() {}

    // ---- Hardcoded AnimState ordinals (mirror CoopAnimationHandler.AnimState) ----
    private static final int ANIM_NONE = 0;
    private static final int ANIM_DAP_HIT = 9;
    private static final int ANIM_FIRE_DAP_HIT = 12;
    private static final int ANIM_HIGHFIVE_HIT = 20;
    private static final int ANIM_PERFECT_DAP_HIT = 26;
    private static final int ANIM_FIRE_DAP_COMBO_P1 = 36;
    private static final int ANIM_FIRE_DAP_COMBO_P2 = 37;
    private static final int ANIM_PERFECT_DAP_EXTEND1_P1 = 43;
    private static final int ANIM_PERFECT_DAP_EXTEND1_P2 = 44;
    private static final int ANIM_DAP_HIT_BAD = 83;
    private static final int ANIM_HEAVEN_DAP = 90;

    // -------------------------------------------------------------- tuning
    public static final float DAP_RANGE = 1.6f;

    public static long chargeTimeMs()    { return CoopMovesConfig.get().dapChargeWindowMs; }
    public static long releaseWindowMs() { return CoopMovesConfig.get().dapReleaseWindowMs; }
    public static long perfectWindowMs() { return CoopMovesConfig.get().dapPerfectWindowMs; }
    public static long cooldownMs()      { return CoopMovesConfig.get().dapCooldownMs; }
    public static long whiffCooldownMs() { return CoopMovesConfig.get().dapWhiffCooldownMs; }
    public static long fireDelayMs()     { return CoopMovesConfig.get().dapFireDelayMs; }
    public static long fireBuildTimeMs() { return CoopMovesConfig.get().dapFireBuildTimeMs; }

    public static final double MIN_MOVEMENT_SPEED = 1.5;
    public static final long FIRE_GRACE_PERIOD_MS = 500;
    public static final double SPEED_BONUS_THRESHOLD = 8.0;
    public static final double SPEED_TIER_4_THRESHOLD = 25.0;
    public static final double PERFECT_LEGENDARY_MIN_INDIVIDUAL_SPEED = 10.0;
    public static final int SPEED_HISTORY_TICKS = 40;
    public static final long HEAVEN_READY_TIME_MS = 5000;

    private static final long FIRE_DAP_HIT_LENGTH = 2292;
    private static final long FIRE_IMPACT_TIME = 210;
    private static final long FIRE_J_WINDOW_START = 830;
    private static final long FIRE_J_WINDOW_END = 2200;
    private static final long FUSION_G_WINDOW_START_MS = DapFusionHandler.FUSION_G_WINDOW_START;
    private static final long FIRE_COMBO_FREEZE_MS = 4000;
    private static final long FIRE_COMBO_ARM_IMPACT = 1330;

    // -------------------------------------------------------------- state
    public static final Map<UUID, Long> chargeStartTime = new HashMap<>();
    public static final Map<UUID, Long> releaseTime = new HashMap<>();
    public static final Map<UUID, UUID> waitingForPartner = new HashMap<>();
    /** playerId -> dap cooldown expiry (ms). Written by sibling handlers too. */
    public static final Map<UUID, Long> cooldowns = new HashMap<>();

    public static final Map<UUID, Long> fireStartTime = new HashMap<>();
    public static final Map<UUID, Long> fireGraceTime = new HashMap<>();
    /** playerId -> fire-dap charge level (0..1); consulted by FireSlapHandler. */
    public static final Map<UUID, Float> fireLevel = new HashMap<>();
    public static final Map<UUID, Long> fireMaxedStartTime = new HashMap<>();
    public static final Set<UUID> heavenReady = new HashSet<>();

    public static final Map<UUID, LinkedList<Double>> speedHistory = new HashMap<>();
    public static final Map<UUID, Integer> impactFreezeTicks = new HashMap<>();
    public static final Map<UUID, Long> blockingAnimEndTime = new HashMap<>();

    private static final Map<UUID, Long> perfectDapStartTime = new HashMap<>();
    private static final Map<UUID, UUID> perfectDapPartner = new HashMap<>();
    private static final Map<UUID, Long> perfectDapFreezeEnd = new HashMap<>();
    private static final Map<UUID, Long> comboCooldown = new HashMap<>();
    private static final Map<UUID, ArmorStand> perfectDapArmorStands = new HashMap<>();

    private static final Map<UUID, Long> fireDapStartTime = new HashMap<>();
    private static final Map<UUID, UUID> fireDapPartner = new HashMap<>();
    private static final Map<UUID, Boolean> inFireDapHit = new HashMap<>();
    private static final Map<UUID, Boolean> fireCircleSpawned = new HashMap<>();
    private static final Map<UUID, Boolean> fireComboActive = new HashMap<>();
    private static final Map<UUID, Long> fireDapComboRequestTime = new HashMap<>();
    private static final Map<UUID, Long> fireDapComboFreezeEnd = new HashMap<>();
    private static final Map<UUID, ArmorStand> fireDapArmorStands = new HashMap<>();
    private static final Map<UUID, FireDapScheduledEvent> pendingFireArmImpacts = new HashMap<>();

    private static final Set<UUID> highFivePartners = new HashSet<>();

    private static final Map<UUID, HeavenDapData> heavenPlayers = new HashMap<>();

    private static final List<ScheduledParticles> scheduledParticles = new ArrayList<>();
    private static final List<ScheduledPerfectDapEffect> scheduledPerfectDapEffects = new ArrayList<>();

    private record ScheduledParticles(ServerLevel world, double x, double y, double z, long spawnTime) {}
    private record ScheduledPerfectDapEffect(ServerLevel world, Vec3 pos, ServerPlayer p1, ServerPlayer p2, long effectTime) {}

    private static class HeavenDapData {
        final Vec3 originalMidpoint; final ServerLevel world; final long startTime; final UUID partnerId;
        HeavenDapData(Vec3 m, ServerLevel w, long s, UUID p) { originalMidpoint = m; world = w; startTime = s; partnerId = p; }
    }
    private static class FireDapScheduledEvent {
        final ServerPlayer p1; final ServerPlayer p2; final long executeTime;
        FireDapScheduledEvent(ServerPlayer p1, ServerPlayer p2, long t) { this.p1 = p1; this.p2 = p2; this.executeTime = t; }
    }

    // ================================================================ messages
    /** S2C: lock/unlock a client for a scripted sequence (sit, perfect dap, ...). */
    public record PerfectDapFreezePayload(boolean frozen) {
        public static void encode(PerfectDapFreezePayload m, FriendlyByteBuf buf) { buf.writeBoolean(m.frozen); }
        public static PerfectDapFreezePayload decode(FriendlyByteBuf buf) { return new PerfectDapFreezePayload(buf.readBoolean()); }
        public static void handle(PerfectDapFreezePayload m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer())
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.ChargedDapClientHandler.onPerfectDapFreeze(m.frozen()));
            });
            c.setPacketHandled(true);
        }
    }

    /** S2C: facing-dap impact frame cue (client visual). */
    public record FacingDapImpactPayload() {
        public static void encode(FacingDapImpactPayload m, FriendlyByteBuf buf) { }
        public static FacingDapImpactPayload decode(FriendlyByteBuf buf) { return new FacingDapImpactPayload(); }
        public static void handle(FacingDapImpactPayload m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer())
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.ChargedDapClientHandler.onFacingDapImpact());
            });
            c.setPacketHandled(true);
        }
    }

    /** C2S: begin charging (empty-hand hold). */
    public record ChargeStartMsg() {
        public static void encode(ChargeStartMsg m, FriendlyByteBuf buf) { }
        public static ChargeStartMsg decode(FriendlyByteBuf buf) { return new ChargeStartMsg(); }
        public static void handle(ChargeStartMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer p = c.getSender();
                if (p != null && CoopMovesConfig.get().enableDap) onChargeStart(p);
            });
            c.setPacketHandled(true);
        }
    }

    /** C2S: release charge. */
    public record ChargeReleaseMsg() {
        public static void encode(ChargeReleaseMsg m, FriendlyByteBuf buf) { }
        public static ChargeReleaseMsg decode(FriendlyByteBuf buf) { return new ChargeReleaseMsg(); }
        public static void handle(ChargeReleaseMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer p = c.getSender();
                if (p != null && CoopMovesConfig.get().enableDap) onChargeRelease(p);
            });
            c.setPacketHandled(true);
        }
    }

    /** C2S: press J during a fire-dap window (fire combo). */
    public record FireDapJPressMsg() {
        public static void encode(FireDapJPressMsg m, FriendlyByteBuf buf) { }
        public static FireDapJPressMsg decode(FriendlyByteBuf buf) { return new FireDapJPressMsg(); }
        public static void handle(FireDapJPressMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { ServerPlayer p = c.getSender(); if (p != null) onFireDapJPress(p); });
            c.setPacketHandled(true);
        }
    }

    /** S2C: charge/fire progress for the local charge bar. */
    public record ChargeSyncMsg(UUID playerId, float chargePercent, float firePercent, boolean charging) {
        public static void encode(ChargeSyncMsg m, FriendlyByteBuf buf) {
            buf.writeUUID(m.playerId); buf.writeFloat(m.chargePercent); buf.writeFloat(m.firePercent); buf.writeBoolean(m.charging);
        }
        public static ChargeSyncMsg decode(FriendlyByteBuf buf) {
            return new ChargeSyncMsg(buf.readUUID(), buf.readFloat(), buf.readFloat(), buf.readBoolean());
        }
        public static void handle(ChargeSyncMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer())
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.ChargedDapClientHandler.onChargeSync(m.playerId(), m.chargePercent(), m.firePercent(), m.charging()));
            });
            c.setPacketHandled(true);
        }
    }

    /** S2C: heaven-ready flag (fire fully maxed for long enough). */
    public record HeavenReadyMsg(UUID playerId, boolean ready) {
        public static void encode(HeavenReadyMsg m, FriendlyByteBuf buf) { buf.writeUUID(m.playerId); buf.writeBoolean(m.ready); }
        public static HeavenReadyMsg decode(FriendlyByteBuf buf) { return new HeavenReadyMsg(buf.readUUID(), buf.readBoolean()); }
        public static void handle(HeavenReadyMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer())
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.ChargedDapClientHandler.onHeavenReady(m.playerId(), m.ready()));
            });
            c.setPacketHandled(true);
        }
    }

    /** S2C: whiff cooldown started (client shows the red cooldown bar). */
    public record WhiffCooldownMsg(long durationMs) {
        public static void encode(WhiffCooldownMsg m, FriendlyByteBuf buf) { buf.writeLong(m.durationMs); }
        public static WhiffCooldownMsg decode(FriendlyByteBuf buf) { return new WhiffCooldownMsg(buf.readLong()); }
        public static void handle(WhiffCooldownMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer())
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.ChargedDapClientHandler.onWhiffCooldown(m.durationMs()));
            });
            c.setPacketHandled(true);
        }
    }

    /** S2C: fire-dap combo window opened (client shows "PRESS J!"). */
    public record FireDapWindowMsg() {
        public static void encode(FireDapWindowMsg m, FriendlyByteBuf buf) { }
        public static FireDapWindowMsg decode(FriendlyByteBuf buf) { return new FireDapWindowMsg(); }
        public static void handle(FireDapWindowMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer())
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.ChargedDapClientHandler.onFireDapWindow());
            });
            c.setPacketHandled(true);
        }
    }

    /** S2C: a dap resolved (client flash + tier particles). */
    public record DapResultMsg(double x, double y, double z, UUID p1, UUID p2, int tier, boolean perfectHit) {
        public static void encode(DapResultMsg m, FriendlyByteBuf buf) {
            buf.writeDouble(m.x); buf.writeDouble(m.y); buf.writeDouble(m.z);
            buf.writeUUID(m.p1); buf.writeUUID(m.p2); buf.writeInt(m.tier); buf.writeBoolean(m.perfectHit);
        }
        public static DapResultMsg decode(FriendlyByteBuf buf) {
            return new DapResultMsg(buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readUUID(), buf.readUUID(), buf.readInt(), buf.readBoolean());
        }
        public static void handle(DapResultMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer())
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.ChargedDapClientHandler.onDapResult(m.x(), m.y(), m.z(), m.p1(), m.p2(), m.tier(), m.perfectHit()));
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(PerfectDapFreezePayload.class,
                PerfectDapFreezePayload::encode, PerfectDapFreezePayload::decode, PerfectDapFreezePayload::handle);
        CoopNetwork.register(FacingDapImpactPayload.class,
                FacingDapImpactPayload::encode, FacingDapImpactPayload::decode, FacingDapImpactPayload::handle);
        CoopNetwork.register(ChargeStartMsg.class,
                ChargeStartMsg::encode, ChargeStartMsg::decode, ChargeStartMsg::handle);
        CoopNetwork.register(ChargeReleaseMsg.class,
                ChargeReleaseMsg::encode, ChargeReleaseMsg::decode, ChargeReleaseMsg::handle);
        CoopNetwork.register(FireDapJPressMsg.class,
                FireDapJPressMsg::encode, FireDapJPressMsg::decode, FireDapJPressMsg::handle);
        CoopNetwork.register(ChargeSyncMsg.class,
                ChargeSyncMsg::encode, ChargeSyncMsg::decode, ChargeSyncMsg::handle);
        CoopNetwork.register(HeavenReadyMsg.class,
                HeavenReadyMsg::encode, HeavenReadyMsg::decode, HeavenReadyMsg::handle);
        CoopNetwork.register(WhiffCooldownMsg.class,
                WhiffCooldownMsg::encode, WhiffCooldownMsg::decode, WhiffCooldownMsg::handle);
        CoopNetwork.register(FireDapWindowMsg.class,
                FireDapWindowMsg::encode, FireDapWindowMsg::decode, FireDapWindowMsg::handle);
        CoopNetwork.register(DapResultMsg.class,
                DapResultMsg::encode, DapResultMsg::decode, DapResultMsg::handle);
    }

    /** Registers the EntityInteract dap-detect (Fabric UseEntityCallback). */
    public static void register() {
        MinecraftForge.EVENT_BUS.register(ChargedDapHandler.class);
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide) return;
        if (!CoopMovesConfig.get().enableDap) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!(event.getTarget() instanceof ServerPlayer target)) return;
        if (sp.isShiftKeyDown()) return;
        if (getChargePercent(sp.getUUID()) < 0.95f) return;

        if (getChargePercent(target.getUUID()) < 0.95f) {
            NormalFacingDapHandler.recordRightClick(sp, target);
            return;
        }
        if (NormalFacingDapHandler.isConfirmed(sp.getUUID(), target.getUUID())
                || NormalFacingDapHandler.isConfirmedOneSide(target.getUUID(), sp.getUUID())) {
            NormalFacingDapHandler.clearConfirm(sp.getUUID(), target.getUUID());
            chargeStartTime.remove(sp.getUUID());
            chargeStartTime.remove(target.getUUID());
            broadcastChargeCancel(sp);
            broadcastChargeCancel(target);
            NormalFacingDapHandler.start(sp, target);
        } else {
            NormalFacingDapHandler.recordRightClick(sp, target);
        }
    }

    // ================================================================ server tick
    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();

        // delayed crit bursts
        Iterator<ScheduledParticles> pit = scheduledParticles.iterator();
        while (pit.hasNext()) {
            ScheduledParticles sp = pit.next();
            if (now >= sp.spawnTime()) {
                sp.world().sendParticles(ParticleTypes.CRIT, sp.x(), sp.y(), sp.z(), 15, 0.3, 0.3, 0.3, 0.15);
                sp.world().sendParticles(ParticleTypes.ENCHANT, sp.x(), sp.y(), sp.z(), 10, 0.2, 0.2, 0.2, 0.1);
                pit.remove();
            }
        }

        // scheduled perfect-dap impact
        Iterator<ScheduledPerfectDapEffect> eit = scheduledPerfectDapEffects.iterator();
        while (eit.hasNext()) {
            ScheduledPerfectDapEffect e = eit.next();
            if (now >= e.effectTime()) {
                ArmorStand stand = perfectDapArmorStands.get(e.p1().getUUID());
                Vec3 pos = (stand != null && !stand.isRemoved()) ? stand.position() : e.pos();
                ServerLevel w = e.world();
                w.sendParticles(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 5, 0.2, 0.2, 0.2, 0);
                w.sendParticles(ParticleTypes.CRIT, pos.x, pos.y, pos.z, 40, 0.4, 0.4, 0.4, 0.12);
                w.sendParticles(ParticleTypes.FIREWORK, pos.x, pos.y, pos.z, 50, 0.5, 0.5, 0.5, 0.15);
                w.playSound(null, pos.x, pos.y, pos.z, ModSounds.DAP_HIT.get(), SoundSource.PLAYERS, 1.5f, 1.0f);
                w.playSound(null, pos.x, pos.y, pos.z, ModSounds.IMPACT.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
                if (!CoopMovesConfig.get().noGriefMode) {
                    createExplosion(w, pos, e.p1(), e.p2(), 3.5, 6.0f);
                    createShockwave(w, pos, e.p1(), e.p2(), 10.0, 2.0);
                }
                handleUnderwaterPerfectDap(w, pos, e.p1(), e.p2());
                eit.remove();
            }
        }

        // perfect dap lifecycle
        Iterator<Map.Entry<UUID, Long>> pdit = perfectDapStartTime.entrySet().iterator();
        while (pdit.hasNext()) {
            Map.Entry<UUID, Long> entry = pdit.next();
            UUID id = entry.getKey();
            long elapsed = now - entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) { pdit.remove(); cleanupPerfectDap(id); continue; }
            UUID partnerId = perfectDapPartner.get(id);
            ServerPlayer partner = partnerId != null ? server.getPlayerList().getPlayer(partnerId) : null;
            if (partner != null) {
                ArmorStand stand = perfectDapArmorStands.get(id);
                if (stand != null && !stand.isRemoved()) {
                    Vec3 mid = player.position().add(0, 1.4, 0).add(partner.position().add(0, 1.4, 0)).scale(0.5);
                    stand.setPos(mid.x, mid.y, mid.z);
                }
                smoothDapDescent(player, stand);
            }
            if (elapsed >= 812 && perfectDapFreezeEnd.containsKey(id)) {
                perfectDapFreezeEnd.remove(id);
                CoopNetwork.sendToPlayer(player, new PerfectDapFreezePayload(false));
                if (partner != null) { perfectDapFreezeEnd.remove(partnerId); CoopNetwork.sendToPlayer(partner, new PerfectDapFreezePayload(false)); }
            }
            if (elapsed >= 1625) { pdit.remove(); cleanupPerfectDap(id); }
        }

        // heaven dap: return home at 11.5s
        Iterator<Map.Entry<UUID, HeavenDapData>> hit = heavenPlayers.entrySet().iterator();
        while (hit.hasNext()) {
            Map.Entry<UUID, HeavenDapData> entry = hit.next();
            UUID id = entry.getKey();
            HeavenDapData data = entry.getValue();
            long elapsed = now - data.startTime;
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) { hit.remove(); continue; }
            if (elapsed >= 3000 && elapsed <= 9000) {
                Vec3 p = player.position();
                data.world.sendParticles(ParticleTypes.WHITE_ASH, p.x, p.y + 1, p.z, 5, 1.0, 1.0, 1.0, 0.02);
                data.world.sendParticles(ParticleTypes.CLOUD, p.x, p.y, p.z, 3, 0.5, 0.5, 0.5, 0.01);
            }
            if (elapsed >= 11500) {
                Vec3 ret = data.originalMidpoint;
                ServerPlayer partner = data.partnerId != null ? server.getPlayerList().getPlayer(data.partnerId) : null;
                double dx = (partner != null ? partner.getX() : ret.x) - player.getX();
                double dz = (partner != null ? partner.getZ() : ret.z) - player.getZ();
                float yawAway = (float) (Math.atan2(dz, dx) * 180 / Math.PI) - 90 + 180;
                player.teleportTo(data.world, ret.x, ret.y, ret.z, yawAway, 0);
                player.stopFallFlying();
                player.setDeltaMovement(Vec3.ZERO);
                player.hurtMarked = true;
                CoopNetwork.sendToPlayer(player, new PerfectDapFreezePayload(false));
                PoseNetworking.broadcastAnimState(player, ANIM_NONE);
                player.removeEffect(MobEffects.CONFUSION);
                data.world.sendParticles(ParticleTypes.FLASH, ret.x, ret.y + 1, ret.z, 5, 0.5, 0.5, 0.5, 0);
                data.world.sendParticles(ParticleTypes.EXPLOSION_EMITTER, ret.x, ret.y, ret.z, 5, 2, 2, 2, 0.3);
                CoopNetwork.sendToPlayer(player, new HeavenDapPayloads.RestoreVolumePayload());
                if (partner != null && heavenPlayers.containsKey(data.partnerId)) {
                    for (ServerPlayer sv : server.getPlayerList().getPlayers()) {
                        sv.displayClientMessage(Component.literal(
                                "§d§l✨ " + player.getName().getString() + " §7and §d§l" + partner.getName().getString()
                                        + " §7have achieved §d§lPERFECT FRIENDSHIP! ✨"), false);
                    }
                    data.world.playSound(null, ret.x, ret.y, ret.z, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 3.0f, 1.0f);
                    data.world.playSound(null, ret.x, ret.y, ret.z, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 2.0f, 1.5f);
                }
                hit.remove();
            }
        }

        // fire dap lifecycle
        Iterator<Map.Entry<UUID, Long>> fit = fireDapStartTime.entrySet().iterator();
        while (fit.hasNext()) {
            Map.Entry<UUID, Long> entry = fit.next();
            UUID id = entry.getKey();
            long elapsed = now - entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) { fit.remove(); inFireDapHit.remove(id); fireDapPartner.remove(id); continue; }

            if (elapsed >= FIRE_IMPACT_TIME && !fireCircleSpawned.getOrDefault(id, true) && inFireDapHit.getOrDefault(id, false)) {
                UUID partnerId = fireDapPartner.get(id);
                if (partnerId != null && fireDapStartTime.containsKey(partnerId)) {
                    ServerPlayer partner = server.getPlayerList().getPlayer(partnerId);
                    if (partner != null && !fireCircleSpawned.getOrDefault(partnerId, true)) {
                        spawnFireCircle(player, partner);
                        long freezeEnd = now + (FIRE_DAP_HIT_LENGTH - elapsed);
                        fireDapComboFreezeEnd.put(id, freezeEnd);
                        fireDapComboFreezeEnd.put(partnerId, freezeEnd);
                        fireCircleSpawned.put(id, true);
                        fireCircleSpawned.put(partnerId, true);
                    }
                }
            }
            if (elapsed >= FIRE_IMPACT_TIME && inFireDapHit.getOrDefault(id, false)) {
                UUID partnerId = fireDapPartner.get(id);
                ServerPlayer partner = partnerId != null ? server.getPlayerList().getPlayer(partnerId) : null;
                if (partner != null) {
                    ArmorStand stand = fireDapArmorStands.get(id);
                    if (stand != null && !stand.isRemoved()) {
                        Vec3 mid = player.position().add(0, 1.4, 0).add(partner.position().add(0, 1.4, 0)).scale(0.5);
                        stand.setPos(mid.x, mid.y, mid.z);
                        smoothDapDescent(player, stand);
                        smoothDapDescent(partner, stand);
                    }
                }
            }
            if (elapsed >= FIRE_DAP_HIT_LENGTH && inFireDapHit.getOrDefault(id, false)) {
                fit.remove();
                inFireDapHit.remove(id);
                fireDapComboRequestTime.remove(id);
                DapSessionManager.removeSessionForPlayer(id);
                if (fireDapComboFreezeEnd.containsKey(id)) {
                    fireDapComboFreezeEnd.remove(id);
                    PoseNetworking.broadcastAnimState(player, ANIM_NONE);
                }
            }
        }

        // scheduled fire arm-impacts
        Iterator<Map.Entry<UUID, FireDapScheduledEvent>> ait = pendingFireArmImpacts.entrySet().iterator();
        while (ait.hasNext()) {
            FireDapScheduledEvent ev = ait.next().getValue();
            if (now >= ev.executeTime) { executeFireArmImpact(ev.p1, ev.p2); ait.remove(); }
        }

        // fire-dap combo freeze expiry
        Iterator<Map.Entry<UUID, Long>> ffit = fireDapComboFreezeEnd.entrySet().iterator();
        while (ffit.hasNext()) {
            Map.Entry<UUID, Long> entry = ffit.next();
            if (now >= entry.getValue()) {
                UUID id = entry.getKey();
                ffit.remove();
                fireDapPartner.remove(id);
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) PoseNetworking.broadcastAnimState(player, ANIM_NONE);
            }
        }

        // per-player: impact freeze, speed history, charge/fire building
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();

            if (impactFreezeTicks.containsKey(id)) {
                int remaining = impactFreezeTicks.get(id);
                if (remaining > 0) {
                    Vec3 v = player.getDeltaMovement();
                    player.setDeltaMovement(0, Math.min(0, v.y), 0);
                    player.hurtMarked = true;
                    impactFreezeTicks.put(id, remaining - 1);
                } else impactFreezeTicks.remove(id);
            }

            Vec3 velocity = getEffectiveVelocity(player);
            double speed = velocity.length() * 20.0;
            LinkedList<Double> history = speedHistory.computeIfAbsent(id, k -> new LinkedList<>());
            history.addLast(speed);
            while (history.size() > SPEED_HISTORY_TICKS) history.removeFirst();

            if (chargeStartTime.containsKey(id)) {
                float charge = getChargePercent(id);
                double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z) * 20.0;
                if (charge >= 0.99f) {
                    boolean isMoving = horizontalSpeed >= MIN_MOVEMENT_SPEED;
                    if (isMoving) {
                        fireGraceTime.remove(id);
                        if (!CoopMovesConfig.get().enableFireDap) {
                            fireStartTime.remove(id); fireLevel.put(id, 0f);
                        } else {
                            fireStartTime.putIfAbsent(id, now);
                            long timeAtFull = now - fireStartTime.get(id);
                            if (timeAtFull >= fireDelayMs()) {
                                long build = timeAtFull - fireDelayMs();
                                float fire = Math.min(1.0f, (float) build / fireBuildTimeMs());
                                fireLevel.put(id, fire);
                                spawnFireHandParticles(player, fire);
                                if (fire >= 0.99f) {
                                    fireMaxedStartTime.putIfAbsent(id, now);
                                    if (now - fireMaxedStartTime.get(id) >= HEAVEN_READY_TIME_MS && !heavenReady.contains(id)) {
                                        heavenReady.add(id);
                                        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                                                SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0f, 0.8f);
                                        player.displayClientMessage(Component.literal("§d§l✨ HEAVEN READY! ✨"), true);
                                        broadcastHeavenReadyStatus(server, id, true);
                                    }
                                } else fireMaxedStartTime.remove(id);
                            } else fireLevel.put(id, 0f);
                        }
                    } else {
                        fireGraceTime.putIfAbsent(id, now);
                        if (now - fireGraceTime.get(id) > FIRE_GRACE_PERIOD_MS) {
                            fireStartTime.remove(id); fireLevel.put(id, 0f); fireMaxedStartTime.remove(id);
                            if (heavenReady.remove(id)) broadcastHeavenReadyStatus(server, id, false);
                        }
                    }
                } else {
                    fireStartTime.remove(id); fireGraceTime.remove(id); fireLevel.put(id, 0f); fireMaxedStartTime.remove(id);
                    if (heavenReady.remove(id)) broadcastHeavenReadyStatus(server, id, false);
                }
            } else {
                fireStartTime.remove(id); fireGraceTime.remove(id); fireLevel.remove(id); fireMaxedStartTime.remove(id);
                if (heavenReady.remove(id)) broadcastHeavenReadyStatus(server, id, false);
            }
        }

        // fizzle timeout for unmatched releases
        Iterator<Map.Entry<UUID, UUID>> wit = waitingForPartner.entrySet().iterator();
        while (wit.hasNext()) {
            Map.Entry<UUID, UUID> entry = wit.next();
            UUID waiterId = entry.getKey();
            Long releaseT = releaseTime.get(waiterId);
            if (releaseT != null && now - releaseT > releaseWindowMs()) {
                ServerPlayer waiter = server.getPlayerList().getPlayer(waiterId);
                ServerPlayer partner = server.getPlayerList().getPlayer(entry.getValue());
                if (waiter != null && partner != null) executeFizzle(waiter, partner);
                wit.remove();
                releaseTime.remove(waiterId);
                chargeStartTime.remove(waiterId);
                chargeStartTime.remove(entry.getValue());
                if (waiter != null) broadcastChargeCancel(waiter);
                if (partner != null) broadcastChargeCancel(partner);
            }
        }

        // charge bar sync
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            if (!chargeStartTime.containsKey(id)) continue;
            ChargeSyncMsg sync = new ChargeSyncMsg(id, getChargePercent(id), fireLevel.getOrDefault(id, 0f), true);
            for (ServerPlayer other : player.serverLevel().players()) CoopNetwork.sendToPlayer(other, sync);
        }
    }

    private static void cleanupPerfectDap(UUID id) {
        perfectDapPartner.remove(id);
        perfectDapFreezeEnd.remove(id);
        ArmorStand stand = perfectDapArmorStands.remove(id);
        if (stand != null && !stand.isRemoved()) stand.discard();
    }

    // ================================================================ charge
    private static void onChargeStart(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (HighFiveHandler.hasHandRaised(uuid) || HighFiveHandler.isInBlockingAnimation(uuid)) { broadcastChargeCancel(player); return; }
        if (isInComboCooldown(uuid)) { player.displayClientMessage(Component.literal("§cWait 1 second after combo!"), true); broadcastChargeCancel(player); return; }
        if (FallCatchHandler.isInCatchReadyMode(uuid)) return;
        if (isOnCooldown(uuid)) return;
        if (!player.getMainHandItem().isEmpty()) return;

        chargeStartTime.put(uuid, System.currentTimeMillis());
        fireLevel.put(uuid, 0f);
        ChargeSyncMsg sync = new ChargeSyncMsg(uuid, 0f, 0f, true);
        for (ServerPlayer other : player.serverLevel().players()) CoopNetwork.sendToPlayer(other, sync);
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.5f, 0.8f);
    }

    private static void onChargeRelease(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!chargeStartTime.containsKey(uuid)) return;
        long now = System.currentTimeMillis();
        float myCharge = getChargePercent(uuid);
        float myFire = fireLevel.getOrDefault(uuid, 0f);

        if (myCharge >= 0.95f) {
            boolean partnerCharging = chargeStartTime.keySet().stream().filter(u -> !u.equals(uuid)).anyMatch(u -> {
                ServerPlayer nearby = player.serverLevel().players().stream()
                        .filter(p -> p.getUUID().equals(u) && player.distanceTo(p) < 2.5f).findFirst().orElse(null);
                return nearby != null;
            });
            if (!partnerCharging && SlapHandler.checkSlapOnRelease(player)) {
                clearChargeState(uuid);
                if (heavenReady.remove(uuid)) broadcastHeavenReadyStatus(player.getServer(), uuid, false);
                broadcastChargeCancel(player);
                return;
            }
        }

        List<ServerPlayer> allPartners = findAllDapPartners(player);
        if (allPartners.size() >= 2) {
            ServerPlayer tp2 = allPartners.get(0), tp3 = allPartners.get(1);
            Long t2 = chargeStartTime.get(tp2.getUUID()), t3 = chargeStartTime.get(tp3.getUUID());
            if (t2 != null && t3 != null) {
                long maxDiff = Math.max(Math.abs(now - t2), Math.max(Math.abs(now - t3), Math.abs(t2 - t3)));
                if (maxDiff <= releaseWindowMs() * 2) {
                    executeTripleDap(player, tp2, tp3);
                    for (ServerPlayer tp : new ServerPlayer[]{player, tp2, tp3}) {
                        UUID tid = tp.getUUID();
                        clearChargeState(tid);
                        if (heavenReady.remove(tid)) broadcastHeavenReadyStatus(tp.getServer(), tid, false);
                        broadcastChargeCancel(tp);
                    }
                    return;
                }
            }
        }

        ServerPlayer partner = findAnyDapPartner(player);
        if (partner == null) {
            executeWhiff(player);
            clearChargeState(uuid);
            if (heavenReady.remove(uuid)) broadcastHeavenReadyStatus(player.getServer(), uuid, false);
            broadcastChargeCancel(player);
            cooldowns.put(uuid, now + whiffCooldownMs());
            broadcastWhiffCooldown(player, now + whiffCooldownMs());
            player.displayClientMessage(Component.literal("§c✗ Whiff! 0.8s cooldown"), true);
            return;
        }

        UUID partnerId = partner.getUUID();

        if (HighFiveHandler.hasHandRaised(partnerId) && !chargeStartTime.containsKey(partnerId)) {
            if (DapHoldHandler.tryDetect(player, partner)) {
                clearChargeState(uuid);
                if (heavenReady.remove(uuid)) broadcastHeavenReadyStatus(player.getServer(), uuid, false);
                broadcastChargeCancel(player);
                return;
            }
            highFivePartners.add(partnerId);
            executeDap(player, partner, myCharge, myCharge, myFire, myFire, now, now);
            highFivePartners.remove(partnerId);
            HighFiveHandler.handRaisedTime.remove(partnerId);
            HighFiveHandler.startAnimTime.remove(partnerId);
            HighFiveHandler.highFiveCooldown.put(partnerId, now);
            HighFiveHandler.syncHandRaised(partner, false);
            clearChargeState(uuid);
            if (heavenReady.remove(uuid)) broadcastHeavenReadyStatus(player.getServer(), uuid, false);
            broadcastChargeCancel(player);
            return;
        }

        if (waitingForPartner.containsKey(partnerId) && waitingForPartner.get(partnerId).equals(uuid)) {
            long partnerReleaseTime = releaseTime.get(partnerId);
            float partnerCharge = getChargePercent(partnerId);
            float partnerFire = fireLevel.getOrDefault(partnerId, 0f);
            executeDap(player, partner, myCharge, partnerCharge, myFire, partnerFire, partnerReleaseTime, now);
            waitingForPartner.remove(partnerId);
            releaseTime.remove(partnerId);
            clearChargeState(uuid);
            clearChargeState(partnerId);
            if (heavenReady.remove(uuid)) broadcastHeavenReadyStatus(player.getServer(), uuid, false);
            if (heavenReady.remove(partnerId)) broadcastHeavenReadyStatus(partner.getServer(), partnerId, false);
            broadcastChargeCancel(player);
            broadcastChargeCancel(partner);
        } else {
            releaseTime.put(uuid, now);
            waitingForPartner.put(uuid, partnerId);
            player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.7f, 1.2f);
        }
    }

    private static void clearChargeState(UUID uuid) {
        chargeStartTime.remove(uuid);
        fireLevel.remove(uuid);
        fireStartTime.remove(uuid);
    }

    private static ServerPlayer findAnyDapPartner(ServerPlayer player) {
        AABB box = player.getBoundingBox().inflate(DAP_RANGE);
        Vec3 look = player.getViewVector(1.0f);
        for (ServerPlayer other : player.serverLevel().players()) {
            if (other == player) continue;
            if (isOnCooldown(other.getUUID())) continue;
            boolean ready = chargeStartTime.containsKey(other.getUUID()) || HighFiveHandler.hasHandRaised(other.getUUID());
            if (!ready) continue;
            if (!box.intersects(other.getBoundingBox())) continue;
            Vec3 toOther = other.position().subtract(player.position()).normalize();
            if (look.dot(toOther) <= 0.0) continue;
            return other;
        }
        return null;
    }

    private static List<ServerPlayer> findAllDapPartners(ServerPlayer player) {
        AABB box = player.getBoundingBox().inflate(DAP_RANGE);
        List<ServerPlayer> result = new ArrayList<>();
        for (ServerPlayer other : player.serverLevel().players()) {
            if (other == player) continue;
            if (isOnCooldown(other.getUUID())) continue;
            if (!chargeStartTime.containsKey(other.getUUID())) continue;
            if (box.intersects(other.getBoundingBox())) result.add(other);
            if (result.size() >= 2) break;
        }
        return result;
    }

    // ================================================================ dap dispatch
    private static void executeTripleDap(ServerPlayer p1, ServerPlayer p2, ServerPlayer p3) {
        ServerLevel world = p1.serverLevel();
        long now = System.currentTimeMillis();
        Vec3 center = p1.position().add(p2.position()).add(p3.position()).scale(1.0 / 3.0);
        double radius = 0.7;
        ServerPlayer[] trio = {p1, p2, p3};
        for (int i = 0; i < 3; i++) {
            double angle = Math.PI * 2 * i / 3;
            double px = center.x + radius * Math.cos(angle);
            double pz = center.z + radius * Math.sin(angle);
            float yaw = (float) (-Math.toDegrees(Math.atan2(center.x - px, center.z - pz)));
            trio[i].teleportTo(world, px, p1.getY(), pz, yaw, 0);
            trio[i].setYRot(yaw); trio[i].setYBodyRot(yaw); trio[i].setYHeadRot(yaw); trio[i].yBodyRotO = yaw;
            trio[i].swing(InteractionHand.MAIN_HAND);
        }
        for (ServerPlayer p : trio) { cooldowns.put(p.getUUID(), now + cooldownMs()); PoseNetworking.broadcastAnimState(p, ANIM_DAP_HIT); }
        Vec3 cTop = center.add(0, 1.4, 0);
        world.sendParticles(ParticleTypes.END_ROD, cTop.x, cTop.y, cTop.z, 40, 0.4, 0.4, 0.4, 0.15);
        world.sendParticles(ParticleTypes.FLASH, cTop.x, cTop.y, cTop.z, 3, 0.1, 0.1, 0.1, 0);
        world.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, cTop.x, cTop.y, cTop.z, 15, 0.5, 0.5, 0.5, 0.2);
        world.playSound(null, cTop.x, cTop.y, cTop.z, ModSounds.EPIC_DAP.get(), SoundSource.PLAYERS, 1.3f, 1.1f);
        for (ServerPlayer p : trio) p.displayClientMessage(Component.literal("§6§l⚡ TRIPLE DAP!"), true);
    }

    private static void executeDap(ServerPlayer p1, ServerPlayer p2,
                                   float charge1, float charge2, float fire1, float fire2,
                                   long releaseTime1, long releaseTime2) {
        if (DapHoldHandler.isInDapHold(p1.getUUID()) || DapHoldHandler.isInDapHold(p2.getUUID())) return;

        long now = System.currentTimeMillis();
        cooldowns.put(p1.getUUID(), now + cooldownMs());
        cooldowns.put(p2.getUUID(), now + cooldownMs());

        float avgCharge = (charge1 + charge2) / 2.0f;
        float avgFire = (fire1 + fire2) / 2.0f;
        double speed1 = getMaxRecentSpeed(p1.getUUID());
        double speed2 = getMaxRecentSpeed(p2.getUUID());
        double combinedSpeed = speed1 + speed2;
        long timeDiff = Math.abs(releaseTime1 - releaseTime2);
        boolean perfectHit = timeDiff <= perfectWindowMs();
        boolean bothCharging = chargeStartTime.containsKey(p1.getUUID()) && chargeStartTime.containsKey(p2.getUUID());
        int tier = calculateTier(avgCharge, combinedSpeed, avgFire, fire1, fire2);

        if (tier >= 3 && bothCharging && !perfectHit && timeDiff > releaseWindowMs()) return;

        boolean isPerfectDap = tier >= 3 && bothCharging && perfectHit;
        boolean isHighTier = tier >= 4;
        if (!isPerfectDap && !isHighTier && !arePlayersFacingEachOther(p1, p2)) {
            p1.displayClientMessage(Component.literal("§c§lKeep eye contact!"), true);
            p2.displayClientMessage(Component.literal("§c§lKeep eye contact!"), true);
            cooldowns.put(p1.getUUID(), now + 300);
            cooldowns.put(p2.getUUID(), now + 300);
            broadcastChargeCancel(p1); broadcastChargeCancel(p2);
            PoseNetworking.broadcastAnimState(p1, ANIM_NONE); PoseNetworking.broadcastAnimState(p2, ANIM_NONE);
            return;
        }

        Vec3 dapPos = p1.position().add(p2.position()).scale(0.5).add(0, 0.5, 0);
        ServerLevel world = p1.serverLevel();

        if (NormalFacingDapHandler.isConfirmed(p1.getUUID(), p2.getUUID())) {
            NormalFacingDapHandler.clearConfirm(p1.getUUID(), p2.getUUID());
            NormalFacingDapHandler.start(p1, p2);
            return;
        }

        switch (tier) {
            case 0 -> executeTier0(world, dapPos, p1, p2);
            case 1 -> executeTier1(world, dapPos, p1, p2);
            case 2 -> executeTier2(world, dapPos, p1, p2);
            case 3 -> executeTier3Great(world, dapPos, p1, p2, perfectHit, bothCharging);
            case 4 -> executeTier4Legendary(world, dapPos, p1, p2, perfectHit, bothCharging);
            case 5 -> executeTier5FireDap(world, dapPos, p1, p2, perfectHit);
        }

        p1.swing(InteractionHand.MAIN_HAND, true);
        p2.swing(InteractionHand.MAIN_HAND, true);
        MahitoTrollHandler.checkForMahitoTroll(p1, p2);

        speedHistory.remove(p1.getUUID());
        speedHistory.remove(p2.getUUID());
        chargeStartTime.remove(p1.getUUID());
        chargeStartTime.remove(p2.getUUID());
        broadcastChargeCancel(p1);
        broadcastChargeCancel(p2);

        boolean facingActive = FacingDapHandler.isActive(p1.getUUID()) || FacingDapHandler.isActive(p2.getUUID());
        if (!facingActive) {
            if (highFivePartners.contains(p1.getUUID())) {
                PoseNetworking.broadcastAnimState(p1, ANIM_HIGHFIVE_HIT);
                PoseNetworking.broadcastAnimState(p2, ANIM_DAP_HIT);
                comboCooldown.put(p1.getUUID(), now + 1000); comboCooldown.put(p2.getUUID(), now + 1000);
            } else if (highFivePartners.contains(p2.getUUID())) {
                PoseNetworking.broadcastAnimState(p2, ANIM_HIGHFIVE_HIT);
                PoseNetworking.broadcastAnimState(p1, ANIM_DAP_HIT);
                comboCooldown.put(p1.getUUID(), now + 1000); comboCooldown.put(p2.getUUID(), now + 1000);
            } else {
                int animOrd = tier <= 2 ? ANIM_DAP_HIT_BAD : ANIM_DAP_HIT;
                PoseNetworking.broadcastAnimState(p1, animOrd);
                PoseNetworking.broadcastAnimState(p2, animOrd);
            }
        }

        scheduledParticles.add(new ScheduledParticles(world, dapPos.x, dapPos.y, dapPos.z, System.currentTimeMillis() + 800));

        DapResultMsg result = new DapResultMsg(dapPos.x, dapPos.y, dapPos.z, p1.getUUID(), p2.getUUID(), tier, perfectHit);
        for (ServerPlayer other : world.getServer().getPlayerList().getPlayers()) CoopNetwork.sendToPlayer(other, result);
    }

    private static int calculateTier(float avgCharge, double combinedSpeed, float avgFire, float fire1, float fire2) {
        if (fire1 >= 0.90f && fire2 >= 0.90f) return 5;
        if (avgCharge >= 0.8f && combinedSpeed >= SPEED_TIER_4_THRESHOLD) return 4;
        float chargeScore = avgCharge * 100;
        float speedBonus = 0;
        if (combinedSpeed >= SPEED_BONUS_THRESHOLD)
            speedBonus = (float) ((combinedSpeed - SPEED_BONUS_THRESHOLD) / (SPEED_TIER_4_THRESHOLD - SPEED_BONUS_THRESHOLD) * 30);
        float finalScore = chargeScore + speedBonus;
        if (finalScore >= 100) return 3;
        if (finalScore >= 70) return 2;
        if (finalScore >= 40) return 1;
        return 0;
    }

    private static void executeFizzle(ServerPlayer p1, ServerPlayer p2) {
        ServerLevel world = p1.serverLevel();
        Vec3 pos = p1.position().add(p2.position()).scale(0.5).add(0, 1.4, 0);
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0f, 0.5f);
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.8f, 0.5f);
        world.sendParticles(ParticleTypes.POOF, pos.x, pos.y, pos.z, 12, 0.4, 0.3, 0.4, 0.03);
        world.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y, pos.z, 8, 0.3, 0.3, 0.3, 0.02);
        p1.displayClientMessage(Component.literal("§7*missed!* timing off..."), true);
        p2.displayClientMessage(Component.literal("§7*missed!* timing off..."), true);
        clearChargeState(p1.getUUID());
        clearChargeState(p2.getUUID());
        if (heavenReady.remove(p1.getUUID())) broadcastHeavenReadyStatus(p1.getServer(), p1.getUUID(), false);
        if (heavenReady.remove(p2.getUUID())) broadcastHeavenReadyStatus(p2.getServer(), p2.getUUID(), false);
        broadcastChargeCancel(p1); broadcastChargeCancel(p2);
        PoseNetworking.broadcastAnimState(p1, ANIM_NONE); PoseNetworking.broadcastAnimState(p2, ANIM_NONE);
        long now = System.currentTimeMillis();
        cooldowns.put(p1.getUUID(), now + 500); cooldowns.put(p2.getUUID(), now + 500);
    }

    public static void executeWhiff(ServerPlayer player) {
        ServerLevel world = player.serverLevel();
        Vec3 pos = player.position().add(0, 1.4, 0).add(player.getViewVector(1.0f).scale(0.5));
        if (Math.random() < 0.1) {
            world.playSound(null, pos.x, pos.y, pos.z, ModSounds.DAP_MISS.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
        } else {
            world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0f, 0.6f);
            world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.SAND_BREAK, SoundSource.PLAYERS, 0.5f, 1.5f);
        }
        world.sendParticles(ParticleTypes.POOF, pos.x, pos.y, pos.z, 8, 0.2, 0.2, 0.2, 0.02);
        world.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y, pos.z, 5, 0.15, 0.15, 0.15, 0.01);
        setBlockingAnimation(player.getUUID(), 330);
        PoseNetworking.broadcastAnimState(player, ANIM_NONE);
        player.displayClientMessage(Component.literal("§7*whoosh*"), true);
    }

    // ================================================================ tiers 0-2
    private static void executeTierLow(ServerLevel world, Vec3 pos, ServerPlayer p1, ServerPlayer p2, int tier, String msg, double kb) {
        DapSession session = DapSessionManager.createSession(p1.getUUID(), p2.getUUID(), 1.4, DapSession.DapType.NORMAL_DAP);
        if (session == null) {
            world.playSound(null, pos.x, pos.y, pos.z, ModSounds.DAP_WEAK.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
            spawnPrecisionDapParticles(world, pos, tier);
            p1.displayClientMessage(Component.literal(msg), true);
            p2.displayClientMessage(Component.literal(msg), true);
            return;
        }
        session.onComplete(() -> {
            Vec3 mid = p1.position().add(p2.position()).scale(0.5).add(0, 1.3, 0);
            world.playSound(null, mid.x, mid.y, mid.z, SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.PLAYERS, 0.9f, 1.1f);
            spawnPrecisionDapParticles(world, mid, tier);
            p1.displayClientMessage(Component.literal(msg), true);
            p2.displayClientMessage(Component.literal(msg), true);
            applyKnockback(p1, p2, pos, kb);
        });
    }

    private static void executeTier0(ServerLevel w, Vec3 p, ServerPlayer p1, ServerPlayer p2) { executeTierLow(w, p, p1, p2, 0, "§7Weak dap...", 0.1); }
    private static void executeTier1(ServerLevel w, Vec3 p, ServerPlayer p1, ServerPlayer p2) { executeTierLow(w, p, p1, p2, 1, "§e✋ Decent Dap!", 0.3); }
    private static void executeTier2(ServerLevel w, Vec3 p, ServerPlayer p1, ServerPlayer p2) { executeTierLow(w, p, p1, p2, 2, "§a✋ Good Dap! ✋", 0.6); }

    // ================================================================ tier 3 (great / perfect)
    private static void executeTier3Great(ServerLevel world, Vec3 pos, ServerPlayer p1, ServerPlayer p2,
                                          boolean perfectHit, boolean bothCharging) {
        if (bothCharging && perfectHit) {
            rotateBothPlayersToFaceEachOther(p1, p2);
            if (FacingDapHandler.areFacingEachOther(p1, p2) && !FacingDapHandler.isActive(p1.getUUID())) {
                FacingDapHandler.start(p1, p2);
                return;
            }
            long now = System.currentTimeMillis();
            UUID id1 = p1.getUUID(), id2 = p2.getUUID();
            DapSession session = DapSessionManager.createSession(id1, id2, 1.5, DapSession.DapType.PERFECT_DAP);
            if (session == null) { executeTier3Normal(world, pos, p1, p2); return; }
            perfectDapStartTime.put(id1, now); perfectDapStartTime.put(id2, now);
            perfectDapPartner.put(id1, id2); perfectDapPartner.put(id2, id1);
            session.onComplete(() -> startPerfectDapTier3Animation(world, pos, p1, p2));
        } else {
            executeTier3Normal(world, pos, p1, p2);
        }
    }

    private static void startPerfectDapTier3Animation(ServerLevel world, Vec3 pos, ServerPlayer p1, ServerPlayer p2) {
        long now = System.currentTimeMillis();
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        Vec3 diff = p2.position().subtract(p1.position());
        float yaw1 = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90f;
        float yaw2 = yaw1 + 180f;
        p1.setYRot(yaw1); p1.setYBodyRot(yaw1); p1.setYHeadRot(yaw1);
        p2.setYRot(yaw2); p2.setYBodyRot(yaw2); p2.setYHeadRot(yaw2);
        p1.swing(InteractionHand.MAIN_HAND); p2.swing(InteractionHand.MAIN_HAND);

        Vec3 handMid = p1.position().add(0, 1.4, 0).add(p2.position().add(0, 1.4, 0)).scale(0.5);
        ArmorStand stand = new ArmorStand(world, handMid.x, handMid.y, handMid.z);
        stand.setInvisible(true); stand.setNoGravity(true); stand.setInvulnerable(true); stand.setSilent(true);
        world.addFreshEntity(stand);
        perfectDapArmorStands.put(id1, stand);

        PoseNetworking.broadcastAnimState(p1, ANIM_PERFECT_DAP_HIT);
        PoseNetworking.broadcastAnimState(p2, ANIM_PERFECT_DAP_HIT);
        perfectDapFreezeEnd.put(id1, now + 1290); perfectDapFreezeEnd.put(id2, now + 1290);
        CoopNetwork.sendToPlayer(p1, new PerfectDapFreezePayload(true));
        CoopNetwork.sendToPlayer(p2, new PerfectDapFreezePayload(true));
        setBlockingAnimation(id1, 1625); setBlockingAnimation(id2, 1625);
        scheduledPerfectDapEffects.add(new ScheduledPerfectDapEffect(world, pos, p1, p2, now + 150));
        p1.displayClientMessage(Component.literal("§6§l✋ PERFECT GREAT DAP! ✋"), true);
        p2.displayClientMessage(Component.literal("§6§l✋ PERFECT GREAT DAP! ✋"), true);
        DapSessionManager.removeSession(id1);
    }

    private static void executeTier3Normal(ServerLevel world, Vec3 pos, ServerPlayer p1, ServerPlayer p2) {
        Runnable impact = () -> {
            world.playSound(null, pos.x, pos.y, pos.z, ModSounds.DAP_WEAK.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
            spawnPrecisionDapParticles(world, pos, 3);
            world.sendParticles(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
            createExplosion(world, pos, p1, p2, 3.5, 6.0f);
            applyKnockback(p1, p2, pos, 1.0);
            p1.displayClientMessage(Component.literal("§6§l✋ GREAT DAP! ✋"), true);
            p2.displayClientMessage(Component.literal("§6§l✋ GREAT DAP! ✋"), true);
            if (CoopMovesConfig.get().enableDapCombo) DapComboChain.startCombo(p1, p2, pos);
        };
        DapSession session = DapSessionManager.createSession(p1.getUUID(), p2.getUUID(), 1.4, DapSession.DapType.NORMAL_DAP);
        if (session == null) impact.run(); else session.onComplete(impact);
    }

    // ================================================================ tier 4 (legendary)
    private static void executeTier4Legendary(ServerLevel world, Vec3 pos, ServerPlayer p1, ServerPlayer p2,
                                              boolean perfectHit, boolean bothCharging) {
        boolean bothHeavenReady = heavenReady.contains(p1.getUUID()) && heavenReady.contains(p2.getUUID());
        if (bothHeavenReady && perfectHit) {
            startHeavenDap(p1, p2, pos, world);
            return;
        }
        if (bothCharging) {
            // "The power was too great" — both perish.
            world.playSound(null, pos.x, pos.y, pos.z, ModSounds.EPIC_DAP.get(), SoundSource.PLAYERS, 2.0f, 0.5f);
            world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 2.0f, 0.7f);
            world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_DEATH, SoundSource.PLAYERS, 2.0f, 0.8f);
            world.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 3, 1, 1, 1, 0);
            world.sendParticles(ParticleTypes.SOUL, pos.x, pos.y, pos.z, 50, 0.5, 0.5, 0.5, 0.2);
            if (!CoopMovesConfig.get().noGriefMode)
                world.explode(null, pos.x, pos.y, pos.z, 6.0f, Level.ExplosionInteraction.MOB);
            removeTotem(p1); removeTotem(p2);
            p1.setHealth(0); p2.setHealth(0);
            p1.die(world.damageSources().magic()); p2.die(world.damageSources().magic());
            p1.displayClientMessage(Component.literal("§4§l☠ THE POWER WAS TOO GREAT! ☠"), true);
            p2.displayClientMessage(Component.literal("§4§l☠ THE POWER WAS TOO GREAT! ☠"), true);
            for (ServerPlayer sv : world.getServer().getPlayerList().getPlayers())
                sv.displayClientMessage(Component.literal("§4§l☠ " + p1.getName().getString() + " §7and §4"
                        + p2.getName().getString() + " §7failed to achieve Perfect Friendship... §c§lTHEY PERISHED!"), false);
        } else {
            world.playSound(null, pos.x, pos.y, pos.z, ModSounds.EPIC_DAP.get(), SoundSource.PLAYERS, 2.0f, 0.9f);
            world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 2.0f, 1.0f);
            world.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
            world.sendParticles(ParticleTypes.FIREWORK, pos.x, pos.y, pos.z, 40, 0.5, 0.5, 0.5, 0.25);
            if (!CoopMovesConfig.get().noGriefMode)
                world.explode(null, pos.x, pos.y, pos.z, 5.0f, Level.ExplosionInteraction.MOB);
            applyKnockback(p1, p2, pos, 2.0);
            p1.displayClientMessage(Component.literal("§d§l⚡ LEGENDARY DAP! ⚡"), true);
            p2.displayClientMessage(Component.literal("§d§l⚡ LEGENDARY DAP! ⚡"), true);
        }
    }

    private static void removeTotem(ServerPlayer player) {
        if (player.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) player.getMainHandItem().setCount(0);
        if (player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) player.getOffhandItem().setCount(0);
    }

    // ================================================================ tier 5 (fire dap)
    private static void executeTier5FireDap(ServerLevel world, Vec3 pos, ServerPlayer p1, ServerPlayer p2, boolean perfectHit) {
        boolean bothHeavenReady = heavenReady.contains(p1.getUUID()) && heavenReady.contains(p2.getUUID());
        double speed1 = getMaxRecentSpeed(p1.getUUID()), speed2 = getMaxRecentSpeed(p2.getUUID());
        boolean bothFast = speed1 >= PERFECT_LEGENDARY_MIN_INDIVIDUAL_SPEED && speed2 >= PERFECT_LEGENDARY_MIN_INDIVIDUAL_SPEED;
        if (bothHeavenReady && bothFast) { startHeavenDap(p1, p2, pos, world); return; }

        world.playSound(null, pos.x, pos.y, pos.z, ModSounds.EPIC_DAP.get(), SoundSource.PLAYERS, 2.0f, 1.0f);
        world.playSound(null, pos.x, pos.y, pos.z, ModSounds.FIRE_IMPACT.get(), SoundSource.PLAYERS, 2.0f, 1.0f);
        world.playSound(null, pos.x, pos.y, pos.z, ModSounds.EXPLOSION_IMPACT.get(), SoundSource.PLAYERS, 1.5f, 1.0f);
        world.sendParticles(ParticleTypes.FLAME, pos.x, pos.y + 1.0, pos.z, 30, 0.3, 0.3, 0.3, 0.2);
        world.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, pos.x, pos.y + 1.0, pos.z, 15, 0.2, 0.2, 0.2, 0.15);
        world.sendParticles(ParticleTypes.LAVA, pos.x, pos.y + 1.0, pos.z, 8, 0.3, 0.3, 0.3, 0);
        world.sendParticles(ParticleTypes.FLASH, pos.x, pos.y + 1.0, pos.z, 3, 0, 0, 0, 0);
        createFireShockwave(world, pos, p1, p2);

        p1.displayClientMessage(Component.literal(perfectHit ? "§c§l🔥 PERFECT FIRE DAP! 🔥" : "§c§l🔥 FIRE DAP! 🔥"), true);
        p2.displayClientMessage(Component.literal(perfectHit ? "§c§l🔥 PERFECT FIRE DAP! 🔥" : "§c§l🔥 FIRE DAP! 🔥"), true);

        startFireDap(p1, p2, pos);
        DapFusionHandler.openFusionWindow(p1, p2);

        for (ServerPlayer nearby : nearby(world, pos, 50)) {
            if (nearby != p1 && nearby != p2) {
                String prefix = perfectHit ? "§c§lPERFECT " : "§c§l";
                nearby.displayClientMessage(Component.literal(prefix + "🔥 " + p1.getName().getString() + " §7and §c"
                        + p2.getName().getString() + " §7unleashed a §c§lFIRE DAP§7!"), false);
            }
        }
    }

    public static void startFireDap(ServerPlayer p1, ServerPlayer p2, Vec3 midpoint) {
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        long now = System.currentTimeMillis();
        fireComboActive.put(id1, true); fireComboActive.put(id2, true);
        fireDapStartTime.put(id1, now); fireDapStartTime.put(id2, now);
        fireCircleSpawned.put(id1, false); fireCircleSpawned.put(id2, false);
        fireDapPartner.put(id1, id2); fireDapPartner.put(id2, id1);
        inFireDapHit.put(id1, true); inFireDapHit.put(id2, true);
        DapSession session = DapSessionManager.createSession(id1, id2, 1.2, DapSession.DapType.FIRE_DAP);
        if (session == null) {
            fireComboActive.remove(id1); fireComboActive.remove(id2);
            fireDapStartTime.remove(id1); fireDapStartTime.remove(id2);
            return;
        }
        session.onComplete(() -> startFireDapAnimation(p1, p2, midpoint));
    }

    private static void startFireDapAnimation(ServerPlayer p1, ServerPlayer p2, Vec3 midpoint) {
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        ServerLevel world = p1.serverLevel();
        long now = System.currentTimeMillis();
        fireDapStartTime.put(id1, now); fireDapStartTime.put(id2, now);
        p1.swing(InteractionHand.MAIN_HAND); p2.swing(InteractionHand.MAIN_HAND);

        Vec3 handMid = p1.position().add(0, 1.4, 0).add(p2.position().add(0, 1.4, 0)).scale(0.5);
        ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, world);
        stand.setPos(handMid.x, handMid.y, handMid.z);
        stand.setInvisible(true); stand.setNoGravity(true); stand.setInvulnerable(true); stand.setSilent(true);
        world.addFreshEntity(stand);
        fireDapArmorStands.put(id1, stand);

        PoseNetworking.broadcastAnimState(p1, ANIM_FIRE_DAP_HIT);
        PoseNetworking.broadcastAnimState(p2, ANIM_FIRE_DAP_HIT);
        p1.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200, 255, false, false));
        p1.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 200, 255, false, false));
        p2.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200, 255, false, false));
        p2.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 200, 255, false, false));
        CoopNetwork.sendToPlayer(p1, new FireDapWindowMsg());
        CoopNetwork.sendToPlayer(p2, new FireDapWindowMsg());
    }

    private static void onFireDapJPress(ServerPlayer player) {
        UUID id = player.getUUID();
        if (!inFireDapHit.getOrDefault(id, false)) return;
        long now = System.currentTimeMillis();
        Long start = fireDapStartTime.get(id);
        if (start == null) return;
        long elapsed = now - start;
        if (elapsed < FIRE_J_WINDOW_START || elapsed > FIRE_J_WINDOW_END) {
            player.displayClientMessage(Component.literal("§cToo early/late for combo!"), true);
            return;
        }
        DapFusionHandler.cancelForJCombo(id);
        UUID partnerId = fireDapPartner.get(id);
        if (partnerId == null) return;
        ServerPlayer partner = player.getServer().getPlayerList().getPlayer(partnerId);
        if (partner == null) return;
        fireDapComboRequestTime.put(id, now);
        Long partnerReq = fireDapComboRequestTime.get(partnerId);
        if (partnerReq != null && Math.abs(now - partnerReq) < 1000) executeFireDapCombo(player, partner);
    }

    private static void executeFireDapCombo(ServerPlayer p1, ServerPlayer p2) {
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        long now = System.currentTimeMillis();
        inFireDapHit.remove(id1); inFireDapHit.remove(id2);
        fireDapComboRequestTime.remove(id1); fireDapComboRequestTime.remove(id2);
        DapSessionManager.removeSessionForPlayer(id1);
        p1.swing(InteractionHand.MAIN_HAND); p2.swing(InteractionHand.MAIN_HAND);
        fireDapComboFreezeEnd.put(id1, now + FIRE_COMBO_FREEZE_MS);
        fireDapComboFreezeEnd.put(id2, now + FIRE_COMBO_FREEZE_MS);
        PoseNetworking.broadcastAnimState(p1, ANIM_FIRE_DAP_COMBO_P1);
        PoseNetworking.broadcastAnimState(p2, ANIM_FIRE_DAP_COMBO_P2);
        Vec3 midpoint = p1.position().add(p2.position()).scale(0.5);
        p1.serverLevel().playSound(null, midpoint.x, midpoint.y, midpoint.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 2.0f, 0.8f);
        p1.displayClientMessage(Component.literal("§c§l🔥 DIVINE FLAME COMBO! 🔥"), true);
        p2.displayClientMessage(Component.literal("§c§l🔥 DIVINE FLAME COMBO! 🔥"), true);
        p1.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 100, 255, false, false));
        p1.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 100, 255, false, false));
        p2.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 100, 255, false, false));
        p2.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 100, 255, false, false));
        pendingFireArmImpacts.put(id1, new FireDapScheduledEvent(p1, p2, now + FIRE_COMBO_ARM_IMPACT));
    }

    private static void executeFireArmImpact(ServerPlayer p1, ServerPlayer p2) {
        ServerLevel world = p1.serverLevel();
        ArmorStand stand = fireDapArmorStands.get(p1.getUUID());
        Vec3 midpoint = (stand != null && !stand.isRemoved())
                ? stand.position() : p1.position().add(p2.position()).scale(0.5).add(0, 1.2, 0);
        for (int h = 0; h < 30; h++) {
            world.sendParticles(ParticleTypes.FLAME, midpoint.x, midpoint.y + 2 + h, midpoint.z, 25, 1.5, 0.5, 1.5, 0.15);
            world.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, midpoint.x, midpoint.y + 2 + h, midpoint.z, 15, 1.2, 0.4, 1.2, 0.1);
        }
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        AABB box = new AABB(midpoint, midpoint).inflate(30);
        for (Entity entity : world.getEntities((Entity) null, box)) {
            if (entity.getUUID().equals(id1) || entity.getUUID().equals(id2)) continue;
            double dist = entity.position().distanceTo(midpoint);
            if (dist >= 30 || dist <= 0.1) continue;
            Vec3 dir = entity.position().subtract(midpoint).normalize();
            double strength = (30 - dist) / 30.0 * 3.0;
            entity.setDeltaMovement(dir.x * strength, 0.8 + strength * 0.5, dir.z * strength);
            entity.hurtMarked = true;
            if (entity instanceof LivingEntity living)
                living.hurt(living.damageSources().explosion(null, null), (float) ((30 - dist) / 30.0 * 20.0));
        }
        world.playSound(null, midpoint.x, midpoint.y, midpoint.z, ModSounds.GALACTIC_DAP.get(), SoundSource.PLAYERS, 3.0f, 1.0f);
        world.playSound(null, midpoint.x, midpoint.y, midpoint.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 2.5f, 0.8f);
    }

    private static void spawnFireCircle(ServerPlayer p1, ServerPlayer p2) {
        ServerLevel world = p1.serverLevel();
        Vec3 mid = p1.position().add(p2.position()).scale(0.5);
        world.sendParticles(ParticleTypes.EXPLOSION_EMITTER, mid.x, mid.y + 1, mid.z, 3, 0, 0, 0, 0);
        for (double angle = 0; angle < 360; angle += 6) {
            double rad = Math.toRadians(angle);
            double x = mid.x + 8 * Math.cos(rad), z = mid.z + 8 * Math.sin(rad);
            world.sendParticles(ParticleTypes.FLAME, x, mid.y + 0.1, z, 6, 0.4, 1.0, 0.4, 0.05);
            world.sendParticles(ParticleTypes.LARGE_SMOKE, x, mid.y + 0.1, z, 4, 0.5, 1.4, 0.5, 0.08);
        }
        world.playSound(null, mid.x, mid.y, mid.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 2.5f, 0.5f);
        world.playSound(null, mid.x, mid.y, mid.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 3.5f, 0.6f);
    }

    private static void createFireShockwave(ServerLevel world, Vec3 pos, ServerPlayer p1, ServerPlayer p2) {
        double radius = 50.0;
        p1.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200, 1, false, false, true));
        p2.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200, 1, false, false, true));
        AABB box = new AABB(pos, pos).inflate(radius);
        for (Entity entity : world.getEntities((Entity) null, box)) {
            if (entity == p1 || entity == p2) continue;
            double dist = entity.position().distanceTo(pos);
            if (dist > radius) continue;
            double strength = (1.0 - dist / radius) * 15.0;
            Vec3 dir = entity.position().subtract(pos).normalize();
            entity.setDeltaMovement(dir.x * strength, strength * 1.5, dir.z * strength);
            entity.hurtMarked = true;
        }
    }

    // ================================================================ heaven dap
    public static void startHeavenDap(ServerPlayer p1, ServerPlayer p2, Vec3 midpoint, ServerLevel world) {
        long now = System.currentTimeMillis();
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        CoopNetwork.sendToPlayer(p1, new HeavenDapPayloads.HeavenImpactPayload());
        CoopNetwork.sendToPlayer(p2, new HeavenDapPayloads.HeavenImpactPayload());
        world.playSound(null, midpoint.x, midpoint.y, midpoint.z, ModSounds.EPIC_DAP.get(), SoundSource.PLAYERS, 3.0f, 1.2f);
        p1.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 80, 255, false, false));
        p2.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 80, 255, false, false));
        if (!CoopMovesConfig.get().noGriefMode) {
            for (int i = 0; i < 8; i++) {
                double a = Math.toRadians(i * 45.0);
                world.explode(null, midpoint.x + Math.cos(a) * 25, midpoint.y, midpoint.z + Math.sin(a) * 25,
                        12f, Level.ExplosionInteraction.TNT);
            }
        }
        world.sendParticles(ParticleTypes.FLASH, midpoint.x, midpoint.y, midpoint.z, 5, 0, 0, 0, 0);
        world.sendParticles(ParticleTypes.EXPLOSION_EMITTER, midpoint.x, midpoint.y, midpoint.z, 3, 0.5, 0.5, 0.5, 0);
        world.sendParticles(ParticleTypes.END_ROD, midpoint.x, midpoint.y, midpoint.z, 50, 1.0, 1.0, 1.0, 0.3);

        heavenPlayers.put(id1, new HeavenDapData(midpoint, world, now, id2));
        heavenPlayers.put(id2, new HeavenDapData(midpoint, world, now, id1));

        double heavenY = 500.0;
        Vec3 heavenMid = new Vec3(midpoint.x, heavenY, midpoint.z);
        Vec3 dir = p2.position().subtract(p1.position());
        if (dir.length() < 0.001) dir = new Vec3(1, 0, 0);
        dir = dir.normalize();
        Vec3 pos1 = heavenMid.add(dir.scale(-2.5)), pos2 = heavenMid.add(dir.scale(2.5));
        float yaw1 = (float) (Math.atan2(pos2.z - pos1.z, pos2.x - pos1.x) * 180 / Math.PI) - 90;
        float yaw2 = yaw1 + 180;
        p1.teleportTo(world, pos1.x, pos1.y, pos1.z, yaw1, 0);
        p2.teleportTo(world, pos2.x, pos2.y, pos2.z, yaw2, 0);
        p1.stopFallFlying(); p2.stopFallFlying();
        p1.setDeltaMovement(Vec3.ZERO); p2.setDeltaMovement(Vec3.ZERO);
        p1.hurtMarked = true; p2.hurtMarked = true;
        CoopNetwork.sendToPlayer(p1, new PerfectDapFreezePayload(true));
        CoopNetwork.sendToPlayer(p2, new PerfectDapFreezePayload(true));
        PoseNetworking.broadcastAnimState(p1, ANIM_HEAVEN_DAP);
        PoseNetworking.broadcastAnimState(p2, ANIM_HEAVEN_DAP);
        p1.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 400, 0, false, false));
        p2.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 400, 0, false, false));
        p1.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 40, 0, false, false));
        p2.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 40, 0, false, false));
        CoopNetwork.sendToPlayer(p1, new HeavenDapPayloads.HeavenDapStartPayload());
        CoopNetwork.sendToPlayer(p2, new HeavenDapPayloads.HeavenDapStartPayload());
    }

    // ================================================================ helpers
    private static void spawnFireHandParticles(ServerPlayer player, float fireLvl) {
        if (Math.random() > 0.33) return;
        ServerLevel world = player.serverLevel();
        Vec3 pos = player.position();
        double yawRad = Math.toRadians(player.getYRot());
        double handX = pos.x - Math.cos(yawRad) * 0.4;
        double handY = pos.y + 1.3;
        double handZ = pos.z - Math.sin(yawRad) * 0.4;
        world.sendParticles(ParticleTypes.FLAME, handX, handY, handZ, 1, 0.06, 0.06, 0.06, 0.005);
        if (fireLvl > 0.6f) world.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, handX, handY, handZ, 1, 0.05, 0.05, 0.05, 0.003);
    }

    private static void spawnPrecisionDapParticles(ServerLevel world, Vec3 pos, int tier) {
        Vec3 p = pos.add(0, 1.0, 0);
        int count = 15 + tier * 5;
        world.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, count, 0.3, 0.3, 0.3, 0.15);
        if (tier >= 3) world.sendParticles(ParticleTypes.ENCHANTED_HIT, p.x, p.y, p.z, count / 2, 0.4, 0.4, 0.4, 0.2);
    }

    private static void applyKnockback(ServerPlayer p1, ServerPlayer p2, Vec3 center, double strength) {
        p1.setDeltaMovement(0, 0, 0); p2.setDeltaMovement(0, 0, 0);
        p1.hurtMarked = true; p2.hurtMarked = true;
    }

    public static void applyImpactFreeze(ServerPlayer p1, ServerPlayer p2, int ticks) {
        if (ticks <= 0) return;
        p1.setDeltaMovement(0, 0, 0); p2.setDeltaMovement(0, 0, 0);
        p1.hurtMarked = true; p2.hurtMarked = true;
        impactFreezeTicks.put(p1.getUUID(), ticks);
        impactFreezeTicks.put(p2.getUUID(), ticks);
    }

    private static void createExplosion(ServerLevel world, Vec3 pos, ServerPlayer p1, ServerPlayer p2, double radius, float maxDamage) {
        AABB box = new AABB(pos, pos).inflate(radius);
        for (Entity entity : world.getEntities((Entity) null, box)) {
            if (entity == p1 || entity == p2) continue;
            double dist = entity.position().distanceTo(pos);
            if (dist > radius) continue;
            double strength = (1.0 - dist / radius) * 2.0;
            Vec3 dir = entity.position().subtract(pos).normalize();
            entity.setDeltaMovement(entity.getDeltaMovement().add(dir.x * strength, strength * 0.5, dir.z * strength));
            entity.hurtMarked = true;
            if (entity instanceof ServerPlayer target)
                target.hurt(world.damageSources().explosion(null, null), (float) ((1.0 - dist / radius) * maxDamage));
        }
    }

    private static void createShockwave(ServerLevel world, Vec3 pos, ServerPlayer p1, ServerPlayer p2, double radius, double strength) {
        for (int i = 0; i < 36; i++) {
            double angle = (i / 36.0) * Math.PI * 2;
            for (double r = 1; r <= radius; r += 2) {
                double px = pos.x + Math.cos(angle) * r, pz = pos.z + Math.sin(angle) * r;
                world.sendParticles(ParticleTypes.CLOUD, px, pos.y, pz, 1, 0, 0.1, 0, 0.02);
            }
        }
        AABB box = new AABB(pos, pos).inflate(radius);
        for (Entity entity : world.getEntities((Entity) null, box)) {
            if (entity == p1 || entity == p2) continue;
            double dist = entity.position().distanceTo(pos);
            if (dist > radius || dist < 0.5) continue;
            double s = (1.0 - dist / radius) * strength;
            Vec3 dir = entity.position().subtract(pos).normalize();
            entity.setDeltaMovement(entity.getDeltaMovement().add(dir.x * s * 1.5, s * 0.6, dir.z * s * 1.5));
            entity.hurtMarked = true;
        }
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 0.6f, 1.5f);
    }

    private static void handleUnderwaterPerfectDap(ServerLevel world, Vec3 pos, ServerPlayer p1, ServerPlayer p2) {
        if (!p1.isUnderWater() && !p2.isUnderWater()) return;
        p1.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 120, 0, false, false));
        p2.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 120, 0, false, false));
        world.sendParticles(ParticleTypes.SPLASH, pos.x, pos.y, pos.z, 80, 2.0, 2.0, 2.0, 0.4);
        world.sendParticles(ParticleTypes.BUBBLE_POP, pos.x, pos.y, pos.z, 40, 1.5, 1.5, 1.5, 0.3);
    }

    private static void rotateBothPlayersToFaceEachOther(ServerPlayer p1, ServerPlayer p2) {
        faceTowards(p1, p2);
        faceTowards(p2, p1);
    }

    private static void faceTowards(ServerPlayer from, ServerPlayer to) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        from.setYRot(yaw); from.setYBodyRot(yaw); from.setYHeadRot(yaw);
    }

    private static boolean arePlayersFacingEachOther(ServerPlayer p1, ServerPlayer p2) {
        Vec3 p1ToP2 = new Vec3(p2.getX() - p1.getX(), 0, p2.getZ() - p1.getZ()).normalize();
        Vec3 p2ToP1 = p1ToP2.reverse();
        double yaw1 = Math.toRadians(p1.getYRot());
        Vec3 look1 = new Vec3(-Math.sin(yaw1), 0, Math.cos(yaw1));
        double yaw2 = Math.toRadians(p2.getYRot());
        Vec3 look2 = new Vec3(-Math.sin(yaw2), 0, Math.cos(yaw2));
        return look1.dot(p1ToP2) > -0.3 && look2.dot(p2ToP1) > -0.3;
    }

    private static void smoothDapDescent(ServerPlayer player, ArmorStand stand) {
        if (stand == null || stand.isRemoved()) return;
        if (fireComboActive.getOrDefault(player.getUUID(), false)) return;
        Vec3 playerPos = player.position();
        if (player.onGround()) return;
        double newY = playerPos.y - 0.12;
        if (newY < playerPos.y) player.setPos(playerPos.x, newY, playerPos.z);
    }

    private static double getMaxRecentSpeed(UUID playerId) {
        LinkedList<Double> history = speedHistory.get(playerId);
        if (history == null || history.isEmpty()) return 0.0;
        double max = 0.0;
        for (Double s : history) if (s > max) max = s;
        return max;
    }

    private static Vec3 getEffectiveVelocity(ServerPlayer player) {
        if (player.isPassenger() && player.getVehicle() != null) return player.getVehicle().getDeltaMovement();
        return player.getDeltaMovement();
    }

    private static List<ServerPlayer> nearby(ServerLevel world, Vec3 pos, double radius) {
        List<ServerPlayer> result = new ArrayList<>();
        for (ServerPlayer p : world.players()) if (p.position().distanceTo(pos) <= radius) result.add(p);
        return result;
    }

    private static void broadcastChargeCancel(ServerPlayer player) {
        if (player == null) return;
        NormalFacingDapHandler.clearConfirm(player.getUUID(), null);
        ChargeSyncMsg msg = new ChargeSyncMsg(player.getUUID(), 0f, 0f, false);
        for (ServerPlayer other : player.getServer().getPlayerList().getPlayers()) CoopNetwork.sendToPlayer(other, msg);
    }

    public static void broadcastWhiffCooldown(ServerPlayer player, long cooldownEnd) {
        if (player == null) return;
        CoopNetwork.sendToPlayer(player, new WhiffCooldownMsg(whiffCooldownMs()));
        PoseNetworking.broadcastAnimState(player, ANIM_NONE);
    }

    private static void broadcastHeavenReadyStatus(MinecraftServer server, UUID playerId, boolean ready) {
        HeavenReadyMsg msg = new HeavenReadyMsg(playerId, ready);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) CoopNetwork.sendToPlayer(p, msg);
    }

    // ================================================================ queries
    public static float getChargePercent(UUID playerId) {
        Long start = chargeStartTime.get(playerId);
        if (start == null) return 0f;
        return Math.min(1.0f, (float) (System.currentTimeMillis() - start) / chargeTimeMs());
    }

    public static float getFireLevel(UUID playerId) { return fireLevel.getOrDefault(playerId, 0f); }
    public static boolean isCharging(UUID playerId) { return chargeStartTime.containsKey(playerId); }

    public static boolean isFullyCharged(UUID playerId) {
        Long start = chargeStartTime.get(playerId);
        if (start == null) return false;
        return Math.min((System.currentTimeMillis() - start) / (float) chargeTimeMs(), 1.0f) >= 0.8f;
    }

    private static boolean isOnCooldown(UUID uuid) {
        Long end = cooldowns.get(uuid);
        if (end == null) return false;
        long now = System.currentTimeMillis();
        if (end - now > 10000) { cooldowns.remove(uuid); return false; }
        return now < end;
    }

    public static boolean isInBlockingAnimation(UUID uuid) {
        Long end = blockingAnimEndTime.get(uuid);
        if (end == null) return false;
        if (System.currentTimeMillis() >= end) { blockingAnimEndTime.remove(uuid); return false; }
        return true;
    }

    public static void setBlockingAnimation(UUID uuid, long durationMs) {
        blockingAnimEndTime.put(uuid, System.currentTimeMillis() + durationMs);
    }

    public static boolean isPerfectDapFrozen(UUID playerId) { return perfectDapFreezeEnd.containsKey(playerId); }
    public static boolean isFireDapFrozen(UUID playerId) { return fireDapComboFreezeEnd.containsKey(playerId); }
    public static boolean isInFireDapBlockingState(UUID playerId) {
        return inFireDapHit.getOrDefault(playerId, false) || fireDapComboFreezeEnd.containsKey(playerId);
    }

    public static boolean isInComboCooldown(UUID playerId) {
        Long end = comboCooldown.get(playerId);
        if (end == null) return false;
        if (System.currentTimeMillis() < end) return true;
        comboCooldown.remove(playerId);
        return false;
    }

    public static long cooldownMsPublic() { return cooldownMs(); }

    public static void cleanup(UUID uuid) {
        chargeStartTime.remove(uuid); releaseTime.remove(uuid); waitingForPartner.remove(uuid);
        cooldowns.remove(uuid); speedHistory.remove(uuid);
        fireStartTime.remove(uuid); fireGraceTime.remove(uuid); fireLevel.remove(uuid);
        impactFreezeTicks.remove(uuid); blockingAnimEndTime.remove(uuid);
        perfectDapStartTime.remove(uuid); perfectDapPartner.remove(uuid); perfectDapFreezeEnd.remove(uuid);
        comboCooldown.remove(uuid);
        ArmorStand pStand = perfectDapArmorStands.remove(uuid);
        if (pStand != null && !pStand.isRemoved()) pStand.discard();
        fireDapStartTime.remove(uuid); fireDapPartner.remove(uuid); inFireDapHit.remove(uuid);
        fireCircleSpawned.remove(uuid); fireDapComboRequestTime.remove(uuid); fireDapComboFreezeEnd.remove(uuid);
        pendingFireArmImpacts.remove(uuid); fireComboActive.remove(uuid);
        ArmorStand fStand = fireDapArmorStands.remove(uuid);
        if (fStand != null && !fStand.isRemoved()) fStand.discard();
        heavenPlayers.remove(uuid); heavenReady.remove(uuid); fireMaxedStartTime.remove(uuid);
        DapSessionManager.removeSessionForPlayer(uuid);
        DapComboChain.cancelCombo(uuid);
        PerfectDapComboHandler.cancelCombo(uuid);
    }
}
