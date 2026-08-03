package com.cooptest;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server-side high-five mechanic. Ported from Fabric to Forge 1.20.1.
 *
 * STAGE 4 NOTE — reduced "basic high-five" build. Raise a hand (H) with empty
 * hands; if a partner within range also has a hand up, the pair connects and a
 * speed-tiered impact fires (tier 0..3). If nobody connects within 2.5 s the hand
 * drops with a "left hanging" cue.
 *
 * Deferred (marked "STAGE 4:" below) and restored when their groups are ported:
 *   - Combo follow-up (H+H window)              -> needs QTE / combo client
 *   - Sike (right-click bait)                   -> sike group
 *   - Hug / QTE-hug triggers                    -> HighFiveHugHandler / QTE
 *   - Freeze + beacon + particle-beam FX        -> combo/sike visual layer
 *   - Cross-mechanic interlocks (ChargedDap /
 *     FallDap / FallCatch guards)               -> Dap family (Stage 4 step 3)
 *
 * Fabric API translations follow the project template (ServerWorld->ServerLevel,
 * Vec3d->Vec3, Box->AABB, SoundCategory->SoundSource, getVelocity/velocityModified
 * ->getDeltaMovement/hurtMarked, sendMessage->displayClientMessage, CustomPayload
 * ->CoopNetwork SimpleChannel messages).
 */
public final class HighFiveHandler {

    private HighFiveHandler() {}

    public static final float HIGH_FIVE_RANGE = 1.6f;
    public static final long HAND_RAISED_DURATION = 2500;
    public static final long COOLDOWN_MS = 1000;
    public static final long HIGH_FIVE_ANIM_DURATION = 1500;
    public static final long START_ANIM_DELAY_MS = 0;
    public static final long HIT_EFFECT_DELAY_MS = 100;
    public static final long END_ANIM_DURATION_MS = 1500;

    public static final double SPEED_TIER_1 = 5.0;
    public static final double SPEED_TIER_2 = 7.5;
    public static final double SPEED_TIER_3 = 12.0;
    public static final int SPEED_HISTORY_TICKS = 30;

    public static final Map<UUID, Long> handRaisedTime = new HashMap<>();
    public static final Map<UUID, Long> highFiveCooldown = new HashMap<>();
    public static final Map<UUID, Long> highFiveAnimStart = new HashMap<>();
    public static final Map<UUID, Long> startAnimTime = new HashMap<>();
    public static final Map<UUID, Long> endAnimTime = new HashMap<>();
    public static final Map<UUID, LinkedList<Double>> speedHistory = new HashMap<>();

    private static final Map<UUID, PendingHighFive> pendingEffects = new HashMap<>();

    // combo (H+H) state
    private static final Map<UUID, Long> comboWindowStart = new HashMap<>();
    private static final Map<UUID, UUID> comboPartner = new HashMap<>();
    private static final Map<UUID, Long> comboRequested = new HashMap<>();
    private static final Map<UUID, Long> comboFreezeEnd = new HashMap<>();
    private static final Map<UUID, ComboImpact> pendingComboImpacts = new HashMap<>();
    private static final Map<UUID, Vec3> frozenPositions = new HashMap<>();
    private static final long COMBO_WINDOW_MS = 1000;
    private static final long COMBO_FREEZE_MS = 2250;
    private static final long COMBO_SECOND_HIT_MS = 1290;
    private static final int ANIM_HIT_COMBO = 21;

    // sike (fake high-five) state
    private static final java.util.Set<UUID> sikeMode = new java.util.HashSet<>();
    private static final Map<UUID, Long> sikeStunEnd = new HashMap<>();
    private static final Map<UUID, Long> sikeSlowEnd = new HashMap<>();
    private static final long SIKE_ANIM_MS = 1458L;
    private static final long SIKE_SLOW_MS = 2000L;
    private static final int ANIM_SIKE_POSE = 63;

    private static class ComboImpact {
        final ServerPlayer p1, p2; final long impactTime;
        ComboImpact(ServerPlayer p1, ServerPlayer p2, long t) { this.p1 = p1; this.p2 = p2; this.impactTime = t; }
    }

    public static final int ANIM_START = 1;
    public static final int ANIM_END   = 2;
    public static final int ANIM_HIT   = 3;
    public static final int ANIM_SIKE  = 4;

    private static class PendingHighFive {
        ServerPlayer p1, p2;
        Vec3 pos;
        int tier;
        long effectTime;
        PendingHighFive(ServerPlayer p1, ServerPlayer p2, Vec3 pos, int tier, long effectTime) {
            this.p1 = p1;
            this.p2 = p2;
            this.pos = pos;
            this.tier = tier;
            this.effectTime = effectTime;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** No Forge game-event listeners needed; kept for CoopMoves.commonSetup symmetry. */
    public static void register() { }

    // ------------------------------------------------------------------ server tick

    /** Called from CoopServerTick under enableHighFive (Fabric: END_SERVER_TICK). */
    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            double speed = player.getDeltaMovement().length() * 20.0;
            LinkedList<Double> history = speedHistory.computeIfAbsent(id, k -> new LinkedList<>());
            history.addLast(speed);
            while (history.size() > SPEED_HISTORY_TICKS) history.removeFirst();
        }

        Iterator<Map.Entry<UUID, PendingHighFive>> pendingIt = pendingEffects.entrySet().iterator();
        while (pendingIt.hasNext()) {
            PendingHighFive pending = pendingIt.next().getValue();
            if (now >= pending.effectTime) {
                executeHighFiveEffects(pending.p1, pending.p2, pending.pos, pending.tier);
                pendingIt.remove();
            }
        }

        // combo window timeout (missed combo)
        Iterator<Map.Entry<UUID, Long>> cwIt = comboWindowStart.entrySet().iterator();
        while (cwIt.hasNext()) {
            Map.Entry<UUID, Long> entry = cwIt.next();
            UUID playerId = entry.getKey();
            if (now - entry.getValue() > COMBO_WINDOW_MS) {
                cwIt.remove();
                comboPartner.remove(playerId);
                comboRequested.remove(playerId);
            }
        }
        // pending combo second impacts
        Iterator<Map.Entry<UUID, ComboImpact>> ciIt = pendingComboImpacts.entrySet().iterator();
        while (ciIt.hasNext()) {
            ComboImpact impact = ciIt.next().getValue();
            if (now >= impact.impactTime) { executeSecondImpact(impact.p1, impact.p2); ciIt.remove(); }
        }
        // combo freeze expiry
        Iterator<Map.Entry<UUID, Long>> cfIt = comboFreezeEnd.entrySet().iterator();
        while (cfIt.hasNext()) {
            Map.Entry<UUID, Long> entry = cfIt.next();
            if (now >= entry.getValue()) {
                UUID playerId = entry.getKey();
                cfIt.remove();
                frozenPositions.remove(playerId);
                handRaisedTime.remove(playerId);
                startAnimTime.remove(playerId);
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) { syncHandRaised(player, false); PoseNetworking.broadcastAnimState(player, 0); }
            }
        }
        // pin frozen (combo/sike) players in place
        for (Map.Entry<UUID, Vec3> entry : frozenPositions.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                Vec3 fp = entry.getValue();
                if (player.position().distanceToSqr(fp) > 0.01) {
                    player.teleportTo(player.serverLevel(), fp.x, fp.y, fp.z, player.getYRot(), player.getXRot());
                    player.setDeltaMovement(Vec3.ZERO);
                    player.hurtMarked = true;
                }
            }
        }
        // sike stun -> slow
        Iterator<Map.Entry<UUID, Long>> ssIt = sikeStunEnd.entrySet().iterator();
        while (ssIt.hasNext()) {
            Map.Entry<UUID, Long> entry = ssIt.next();
            if (now >= entry.getValue()) {
                UUID victimId = entry.getKey();
                ssIt.remove();
                frozenPositions.remove(victimId);
                ServerPlayer victim = server.getPlayerList().getPlayer(victimId);
                if (victim != null) {
                    PoseNetworking.broadcastAnimState(victim, 0);
                    syncHandRaised(victim, false);
                    victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, (int) (SIKE_SLOW_MS / 50), 4, false, true));
                    sikeSlowEnd.put(victimId, now + SIKE_SLOW_MS);
                }
            }
        }
        sikeSlowEnd.entrySet().removeIf(e -> now >= e.getValue());

        Iterator<Map.Entry<UUID, Long>> animIt = highFiveAnimStart.entrySet().iterator();
        while (animIt.hasNext()) {
            Map.Entry<UUID, Long> entry = animIt.next();
            if (now - entry.getValue() > HIGH_FIVE_ANIM_DURATION) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) PoseNetworking.broadcastAnimState(player, 0);
                animIt.remove();
            }
        }

        Iterator<Map.Entry<UUID, Long>> endIt = endAnimTime.entrySet().iterator();
        while (endIt.hasNext()) {
            Map.Entry<UUID, Long> entry = endIt.next();
            if (now >= entry.getValue()) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) PoseNetworking.broadcastAnimState(player, 0);
                endIt.remove();
            }
        }

        Iterator<Map.Entry<UUID, Long>> it = handRaisedTime.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            UUID playerId = entry.getKey();
            long startAnimStartTime = startAnimTime.getOrDefault(playerId, entry.getValue());
            if (now - startAnimStartTime > START_ANIM_DELAY_MS + HAND_RAISED_DURATION) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                it.remove();
                startAnimTime.remove(playerId);
                if (player != null) {
                    executeEndAnimation(player);
                    syncHandRaised(player, false);
                }
            }
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID playerId = player.getUUID();
            if (!handRaisedTime.containsKey(playerId)) continue;
            if (isOnCooldown(playerId)) continue;
            if (isInBlockingState(playerId)) continue;
            Long startTime = startAnimTime.get(playerId);
            if (startTime != null && now - startTime < START_ANIM_DELAY_MS) continue;
            ServerPlayer partner = findHighFivePartner(player);
            if (partner != null) {
                Long partnerStartTime = startAnimTime.get(partner.getUUID());
                if (partnerStartTime != null && now - partnerStartTime < START_ANIM_DELAY_MS) continue;
                executeHighFive(player, partner);
            }
        }
    }

    // ------------------------------------------------------------------ state queries

    /** Consulted by PoseNetworking before allowing GRAB_READY. */
    public static boolean isInBlockingState(UUID playerId) {
        return isInBlockingAnimation(playerId)
                || sikeStunEnd.containsKey(playerId)
                || sikeSlowEnd.containsKey(playerId);
    }

    public static boolean isInBlockingAnimation(UUID uuid) {
        return highFiveAnimStart.containsKey(uuid) || endAnimTime.containsKey(uuid)
                || comboFreezeEnd.containsKey(uuid);
    }

    public static boolean isInHighFiveMode(UUID playerId) {
        return handRaisedTime.containsKey(playerId) || startAnimTime.containsKey(playerId);
    }

    public static boolean isInAnyHighFiveState(UUID playerId) {
        return isInHighFiveMode(playerId)
                || isInBlockingState(playerId)
                || highFiveAnimStart.containsKey(playerId);
    }

    public static boolean hasHandRaised(UUID uuid) {
        return handRaisedTime.containsKey(uuid);
    }

    private static boolean isOnCooldown(UUID uuid) {
        Long cooldownStart = highFiveCooldown.get(uuid);
        if (cooldownStart == null) return false;
        return System.currentTimeMillis() - cooldownStart < COOLDOWN_MS;
    }

    public static float getHighFiveAnimProgress(UUID uuid) {
        Long startTime = highFiveAnimStart.get(uuid);
        if (startTime == null) return -1f;
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > HIGH_FIVE_ANIM_DURATION) {
            highFiveAnimStart.remove(uuid);
            return -1f;
        }
        return (float) elapsed / HIGH_FIVE_ANIM_DURATION;
    }

    public static void cleanup(UUID playerId) {
        handRaisedTime.remove(playerId);
        highFiveCooldown.remove(playerId);
        highFiveAnimStart.remove(playerId);
        startAnimTime.remove(playerId);
        endAnimTime.remove(playerId);
        speedHistory.remove(playerId);
        pendingEffects.remove(playerId);
        comboWindowStart.remove(playerId);
        comboPartner.remove(playerId);
        comboRequested.remove(playerId);
        comboFreezeEnd.remove(playerId);
        pendingComboImpacts.remove(playerId);
        frozenPositions.remove(playerId);
        sikeMode.remove(playerId);
        sikeStunEnd.remove(playerId);
        sikeSlowEnd.remove(playerId);
    }

    // ------------------------------------------------------------------ requests

    private static void onHighFiveRequest(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!handRaisedTime.containsKey(uuid) && ChargedDapHandler.isCharging(uuid)) {
            syncHandRaised(player, false);
            return;
        }
        if (ChargedDapHandler.isInComboCooldown(uuid)) {
            player.displayClientMessage(Component.literal("§cWait 1 second after combo!"), true);
            syncHandRaised(player, false);
            return;
        }
        if (FallCatchHandler.isInCatchReadyMode(uuid)) return;
        if (isInBlockingState(uuid)) return;
        if (isOnCooldown(uuid)) return;
        if (!player.getMainHandItem().isEmpty()) return;

        if (handRaisedTime.containsKey(uuid)) {
            handRaisedTime.remove(uuid);
            startAnimTime.remove(uuid);
            syncHandRaised(player, false);
            executeEndAnimation(player);
        } else {
            long now = System.currentTimeMillis();
            handRaisedTime.put(uuid, now);
            startAnimTime.put(uuid, now);
            syncHandRaised(player, true);
            broadcastHighFiveAnim(player, ANIM_START);
            player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.3f, 1.5f);
        }
    }

    private static ServerPlayer findHighFivePartner(ServerPlayer player) {
        AABB searchBox = player.getBoundingBox().inflate(HIGH_FIVE_RANGE);
        long now = System.currentTimeMillis();
        for (ServerPlayer other : player.serverLevel().players()) {
            if (other == player) continue;
            if (!handRaisedTime.containsKey(other.getUUID())) continue;
            if (isOnCooldown(other.getUUID())) continue;
            if (isInBlockingState(other.getUUID())) continue;
            Long otherStartTime = startAnimTime.get(other.getUUID());
            if (otherStartTime != null && now - otherStartTime < START_ANIM_DELAY_MS) continue;
            if (searchBox.intersects(other.getBoundingBox())) return other;
        }
        return null;
    }

    private static void executeHighFive(ServerPlayer player1, ServerPlayer player2) {
        boolean p1Sike = sikeMode.remove(player1.getUUID());
        boolean p2Sike = sikeMode.remove(player2.getUUID());
        if (p1Sike && p2Sike) { executeMutualSike(player1, player2); return; }
        if (p1Sike) { executeSike(player1, player2); return; }
        if (p2Sike) { executeSike(player2, player1); return; }
        long now = System.currentTimeMillis();
        highFiveCooldown.put(player1.getUUID(), now);
        highFiveCooldown.put(player2.getUUID(), now);
        handRaisedTime.remove(player1.getUUID());
        handRaisedTime.remove(player2.getUUID());
        startAnimTime.remove(player1.getUUID());
        startAnimTime.remove(player2.getUUID());
        syncHandRaised(player1, false);
        syncHandRaised(player2, false);

        highFiveAnimStart.put(player1.getUUID(), now);
        highFiveAnimStart.put(player2.getUUID(), now);
        broadcastHighFiveAnim(player1, ANIM_HIT);
        broadcastHighFiveAnim(player2, ANIM_HIT);
        PoseNetworking.broadcastAnimState(player1, 20);
        PoseNetworking.broadcastAnimState(player2, 20);

        // After a high-five: open the G QTE-hug (~250ms); if it isn't entered, fall
        // back to the hold-to-hug (F) window at 2s. Mirrors the Fabric flow.
        final ServerPlayer fp1 = player1, fp2 = player2;
        new Thread(() -> {
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            fp1.getServer().execute(() -> HighFiveQTEHugHandler.startHugQTE(fp1, fp2));
        }).start();
        if (CoopMovesConfig.get().enableHighFiveHug) {
            new Thread(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                fp1.getServer().execute(() -> {
                    if (!HighFiveQTEHugHandler.isInHugSession(fp1.getUUID())
                            && !HighFiveQTEHugHandler.isInHugSession(fp2.getUUID()))
                        HighFiveHugHandler.startHugHold(fp1, fp2);
                });
            }).start();
        }

        double speed1 = getMaxRecentSpeed(player1.getUUID());
        double speed2 = getMaxRecentSpeed(player2.getUUID());
        double maxSpeed = Math.max(speed1, speed2);
        int tier = 0;
        if (maxSpeed >= SPEED_TIER_3) tier = 3;
        else if (maxSpeed >= SPEED_TIER_2) tier = 2;
        else if (maxSpeed >= SPEED_TIER_1) tier = 1;

        Vec3 highFivePos = player1.position().add(player2.position()).scale(0.5).add(0, 1.4, 0);
        pendingEffects.put(player1.getUUID(), new PendingHighFive(
                player1, player2, highFivePos, tier, now + HIT_EFFECT_DELAY_MS));
        speedHistory.remove(player1.getUUID());
        speedHistory.remove(player2.getUUID());

        broadcastHighFiveSuccess(player1.getServer(),
                highFivePos.x, highFivePos.y, highFivePos.z,
                player1.getUUID(), player2.getUUID(), tier);

        // Open the H+H combo window.
        comboWindowStart.put(player1.getUUID(), now);
        comboWindowStart.put(player2.getUUID(), now);
        comboPartner.put(player1.getUUID(), player2.getUUID());
        comboPartner.put(player2.getUUID(), player1.getUUID());
        CoopNetwork.sendToPlayer(player1, new ComboWindowMsg(player1.getUUID()));
        CoopNetwork.sendToPlayer(player2, new ComboWindowMsg(player2.getUUID()));
    }

    // ------------------------------------------------------------------ combo (H+H)

    private static void onComboRequest(ServerPlayer player) {
        UUID playerId = player.getUUID();
        long now = System.currentTimeMillis();
        Long windowStart = comboWindowStart.get(playerId);
        if (windowStart == null || now - windowStart > COMBO_WINDOW_MS) {
            comboWindowStart.remove(playerId);
            comboPartner.remove(playerId);
            comboRequested.remove(playerId);
            return;
        }
        comboRequested.put(playerId, now);
        UUID partnerId = comboPartner.get(playerId);
        if (partnerId == null) return;
        ServerPlayer partner = player.getServer().getPlayerList().getPlayer(partnerId);
        if (partner == null || !comboWindowStart.containsKey(partnerId)) return;
        if (comboRequested.containsKey(partnerId)) executeCombo(player, partner);
    }

    private static void executeCombo(ServerPlayer p1, ServerPlayer p2) {
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        long now = System.currentTimeMillis();
        comboWindowStart.remove(id1); comboWindowStart.remove(id2);
        comboPartner.remove(id1); comboPartner.remove(id2);
        comboRequested.remove(id1); comboRequested.remove(id2);
        handRaisedTime.remove(id1); handRaisedTime.remove(id2);
        startAnimTime.remove(id1); startAnimTime.remove(id2);
        syncHandRaised(p1, false); syncHandRaised(p2, false);
        comboFreezeEnd.put(id1, now + COMBO_FREEZE_MS);
        comboFreezeEnd.put(id2, now + COMBO_FREEZE_MS);
        frozenPositions.put(id1, p1.position());
        frozenPositions.put(id2, p2.position());
        p1.setDeltaMovement(Vec3.ZERO); p2.setDeltaMovement(Vec3.ZERO);
        p1.hurtMarked = true; p2.hurtMarked = true;
        PoseNetworking.broadcastAnimState(p1, ANIM_HIT_COMBO);
        PoseNetworking.broadcastAnimState(p2, ANIM_HIT_COMBO);
        pendingComboImpacts.put(id1, new ComboImpact(p1, p2, now + COMBO_SECOND_HIT_MS));
        p1.displayClientMessage(Component.literal("§6§l✨ COMBO! ✨"), true);
        p2.displayClientMessage(Component.literal("§6§l✨ COMBO! ✨"), true);
    }

    private static void executeSecondImpact(ServerPlayer p1, ServerPlayer p2) {
        Vec3 pos = p1.position().add(p2.position()).scale(0.5).add(0, 0.5, 0);
        ServerLevel world = p1.serverLevel();
        world.playSound(null, pos.x, pos.y, pos.z, ModSounds.DAP_WEAK.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.5f, 1.0f);
        world.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, pos.x, pos.y, pos.z, 30, 0.3, 0.3, 0.3, 0.1);
        world.sendParticles(ParticleTypes.CRIT, pos.x, pos.y, pos.z, 20, 0.3, 0.3, 0.3, 0.15);
        p1.displayClientMessage(Component.literal("§e⚡ PERFECT! ⚡"), true);
        p2.displayClientMessage(Component.literal("§e⚡ PERFECT! ⚡"), true);
    }

    // ------------------------------------------------------------------ sike

    private static void executeSike(ServerPlayer siker, ServerPlayer victim) {
        long now = System.currentTimeMillis();
        UUID sikerId = siker.getUUID(), victimId = victim.getUUID();
        handRaisedTime.remove(sikerId); handRaisedTime.remove(victimId);
        startAnimTime.remove(sikerId); startAnimTime.remove(victimId);
        syncHandRaised(siker, false); syncHandRaised(victim, false);
        PoseNetworking.broadcastAnimState(siker, 0);
        broadcastHighFiveAnim(victim, ANIM_SIKE);
        PoseNetworking.broadcastAnimState(victim, ANIM_SIKE_POSE);
        sikeStunEnd.put(victimId, now + SIKE_ANIM_MS);
        frozenPositions.put(victimId, victim.position());
        victim.setDeltaMovement(Vec3.ZERO); victim.hurtMarked = true;
        ServerLevel world = victim.serverLevel();
        Vec3 vp = victim.position().add(0, 1.8, 0);
        world.playSound(null, vp.x, vp.y, vp.z, SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 1.0f, 0.8f);
        world.sendParticles(ParticleTypes.ANGRY_VILLAGER, vp.x, vp.y + 0.6, vp.z, 4, 0.3, 0.2, 0.3, 0.05);
        world.sendParticles(ParticleTypes.POOF, vp.x, vp.y + 0.3, vp.z, 8, 0.2, 0.1, 0.2, 0.03);
        siker.displayClientMessage(Component.literal("§6§l😂 SIKE!"), true);
        victim.displayClientMessage(Component.literal("§c§lSIKE!"), true);
        highFiveCooldown.put(sikerId, now);
    }

    private static void executeMutualSike(ServerPlayer p1, ServerPlayer p2) {
        long now = System.currentTimeMillis();
        for (ServerPlayer p : new ServerPlayer[]{p1, p2}) {
            UUID id = p.getUUID();
            handRaisedTime.remove(id); startAnimTime.remove(id);
            syncHandRaised(p, false);
            PoseNetworking.broadcastAnimState(p, 0);
            highFiveCooldown.put(id, now);
        }
        Vec3 toP2 = p2.position().subtract(p1.position()).normalize();
        if (toP2.lengthSqr() < 0.01) toP2 = new Vec3(1, 0, 0);
        ServerLevel world = p1.serverLevel();
        p1.hurt(world.damageSources().magic(), 6.0f);
        p2.hurt(world.damageSources().magic(), 6.0f);
        p1.setDeltaMovement(toP2.reverse().scale(0.65).add(0, 0.5, 0)); p1.hurtMarked = true;
        p2.setDeltaMovement(toP2.scale(0.65).add(0, 0.5, 0)); p2.hurtMarked = true;
        Vec3 mid = p1.position().add(p2.position()).scale(0.5).add(0, 1.0, 0);
        world.sendParticles(ParticleTypes.CRIT, mid.x, mid.y, mid.z, 24, 0.4, 0.4, 0.4, 0.2);
        world.sendParticles(ParticleTypes.ANGRY_VILLAGER, mid.x, mid.y + 0.5, mid.z, 8, 0.3, 0.2, 0.3, 0.05);
        world.playSound(null, mid.x, mid.y, mid.z, SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 1.0f, 0.8f);
        p1.displayClientMessage(Component.literal("§c§l💥 MUTUAL SIKE! You both played dirty!"), true);
        p2.displayClientMessage(Component.literal("§c§l💥 MUTUAL SIKE! You both played dirty!"), true);
    }

    private static double getMaxRecentSpeed(UUID playerId) {
        LinkedList<Double> history = speedHistory.get(playerId);
        if (history == null || history.isEmpty()) return 0.0;
        double maxSpeed = 0.0;
        for (Double speed : history) if (speed > maxSpeed) maxSpeed = speed;
        return maxSpeed;
    }

    private static void executeEndAnimation(ServerPlayer player) {
        long now = System.currentTimeMillis();
        endAnimTime.put(player.getUUID(), now + END_ANIM_DURATION_MS);
        broadcastHighFiveAnim(player, ANIM_END);
        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position().add(0, 1.6, 0);
        level.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.8f, 0.5f);
        level.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.SAND_BREAK, SoundSource.PLAYERS, 0.4f, 1.2f);
        level.sendParticles(ParticleTypes.POOF, pos.x, pos.y, pos.z, 6, 0.15, 0.15, 0.15, 0.01);
        player.displayClientMessage(Component.literal("§7*left hanging*"), true);
    }

    // ------------------------------------------------------------------ tier effects

    private static void executeHighFiveEffects(ServerPlayer p1, ServerPlayer p2, Vec3 pos, int tier) {
        ServerLevel world = p1.serverLevel();
        switch (tier) {
            case 0 -> executeTier0(world, pos, p1, p2);
            case 1 -> executeTier1(world, pos, p1, p2);
            case 2 -> executeTier2(world, pos, p1, p2);
            case 3 -> executeTier3(world, pos, p1, p2);
        }
    }

    private static void executeTier0(ServerLevel world, Vec3 pos, ServerPlayer p1, ServerPlayer p2) {
        world.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.2f, 1.1f);
        world.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 1.8f);
        spawnStarBurst(world, pos, 10, 0.3);
        world.sendParticles(ParticleTypes.FIREWORK, pos.x, pos.y, pos.z, 8, 0.1, 0.1, 0.1, 0.08);
        world.sendParticles(ParticleTypes.WAX_ON, pos.x, pos.y, pos.z, 6, 0.2, 0.2, 0.2, 0.02);
        applyKnockback(p1, p2, pos, 0.1);
    }

    private static void executeTier1(ServerLevel world, Vec3 pos, ServerPlayer p1, ServerPlayer p2) {
        world.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.5f, 1.0f);
        world.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0f, 2.0f);
        world.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 0.8f, 1.2f);
        spawnStarBurst(world, pos, 16, 0.5);
        world.sendParticles(ParticleTypes.FIREWORK, pos.x, pos.y, pos.z, 15, 0.15, 0.15, 0.15, 0.12);
        world.sendParticles(ParticleTypes.WAX_ON, pos.x, pos.y, pos.z, 10, 0.25, 0.25, 0.25, 0.03);
        world.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 5, 0.2, 0.2, 0.2, 0.05);
        applyKnockback(p1, p2, pos, 0.4);
    }

    private static void executeTier2(ServerLevel world, Vec3 pos, ServerPlayer p1, ServerPlayer p2) {
        world.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 2.0f, 0.9f);
        world.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1.2f, 1.0f);
        world.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.2f, 2.0f);
        spawnStarBurst(world, pos, 24, 0.7);
        world.sendParticles(ParticleTypes.FIREWORK, pos.x, pos.y, pos.z, 25, 0.2, 0.2, 0.2, 0.18);
        world.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 15, 0.3, 0.3, 0.3, 0.1);
        world.sendParticles(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
        world.sendParticles(ParticleTypes.WAX_ON, pos.x, pos.y, pos.z, 15, 0.3, 0.3, 0.3, 0.05);
        applyKnockback(p1, p2, pos, 0.8);
    }

    private static void executeTier3(ServerLevel world, Vec3 pos, ServerPlayer p1, ServerPlayer p2) {
        world.playSound(null, pos.x, pos.y, pos.z,
                ModSounds.EPIC_DAP.get(), SoundSource.PLAYERS, 2.0f, 1.0f);
        world.playSound(null, pos.x, pos.y, pos.z,
                ModSounds.EXPLOSION_IMPACT.get(), SoundSource.PLAYERS, 1.5f, 1.0f);
        world.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.2f, 1.3f);
        world.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.PLAYERS, 1.5f, 1.0f);
        world.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 2.0f, 0.5f);
        spawnStarBurst(world, pos, 32, 1.0);
        world.sendParticles(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 3, 0.5, 0.5, 0.5, 0);
        world.sendParticles(ParticleTypes.FIREWORK, pos.x, pos.y, pos.z, 40, 0.3, 0.3, 0.3, 0.25);
        world.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 25, 0.5, 0.5, 0.5, 0.15);
        world.sendParticles(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 2, 0, 0, 0, 0);
        world.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, pos.x, pos.y, pos.z, 20, 0.4, 0.4, 0.4, 0.1);
        world.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, pos.x, pos.y, pos.z, 30, 0.5, 0.5, 0.5, 0.3);
        createBattleShockwave(world, pos, p1, p2, 10.0);
        // STAGE 4: ChargedDapHandler.applyImpactFreeze(p1, p2, 3) restores with Dap family.
        applyKnockback(p1, p2, pos, 0.3);
        p1.displayClientMessage(Component.literal("§6§l⚡ SHOCKWAVE! ⚡"), true);
        p2.displayClientMessage(Component.literal("§6§l⚡ SHOCKWAVE! ⚡"), true);
    }

    private static void createBattleShockwave(ServerLevel world, Vec3 pos, ServerPlayer p1, ServerPlayer p2, double radius) {
        for (int ring = 1; ring <= 5; ring++) {
            double r = ring * 2.0;
            int points = (int) (r * 8);
            for (int i = 0; i < points; i++) {
                double angle = Math.toRadians((360.0 / points) * i);
                double x = pos.x + Math.cos(angle) * r;
                double z = pos.z + Math.sin(angle) * r;
                if (ring <= 2) {
                    world.sendParticles(ParticleTypes.CLOUD, x, pos.y, z, 1, 0.1, 0.2, 0.1, 0.02);
                } else if (ring <= 4) {
                    world.sendParticles(ParticleTypes.SWEEP_ATTACK, x, pos.y + 0.5, z, 1, 0, 0, 0, 0);
                } else {
                    world.sendParticles(ParticleTypes.CRIT, x, pos.y + 0.5, z, 2, 0.1, 0.1, 0.1, 0.05);
                }
            }
        }
        AABB pushBox = new AABB(
                pos.x - radius, pos.y - radius, pos.z - radius,
                pos.x + radius, pos.y + radius, pos.z + radius);
        for (Entity entity : world.getEntities((Entity) null, pushBox)) {
            if (entity == p1 || entity == p2) continue;
            double dist = entity.position().distanceTo(pos);
            if (dist > radius || dist < 0.5) continue;
            double strength = (1.0 - (dist / radius)) * 4.0 + 1.0;
            Vec3 dir = entity.position().subtract(pos).normalize();
            if (dir.lengthSqr() < 0.01) {
                dir = new Vec3(Math.random() - 0.5, 0, Math.random() - 0.5).normalize();
            }
            entity.setDeltaMovement(entity.getDeltaMovement().add(dir.x * strength, strength * 0.6, dir.z * strength));
            entity.hurtMarked = true;
            world.sendParticles(ParticleTypes.CRIT,
                    entity.getX(), entity.getY() + 1, entity.getZ(), 5, 0.2, 0.2, 0.2, 0.1);
        }
    }

    private static void spawnStarBurst(ServerLevel world, Vec3 pos, int rays, double spread) {
        for (int i = 0; i < rays; i++) {
            double angle = (2 * Math.PI * i) / rays;
            double dx = Math.cos(angle) * spread;
            double dz = Math.sin(angle) * spread;
            world.sendParticles(ParticleTypes.CRIT, pos.x, pos.y, pos.z, 2, dx, 0.2, dz, 0.15);
        }
    }

    private static void applyKnockback(ServerPlayer p1, ServerPlayer p2, Vec3 center, double strength) {
        Vec3 dir1 = p1.position().subtract(center).normalize();
        Vec3 dir2 = p2.position().subtract(center).normalize();
        if (dir1.lengthSqr() < 0.01) dir1 = new Vec3(1, 0, 0);
        if (dir2.lengthSqr() < 0.01) dir2 = new Vec3(-1, 0, 0);
        double push = 0.15 * strength;
        p1.setDeltaMovement(dir1.x * push, 0.05, dir1.z * push);
        p2.setDeltaMovement(dir2.x * push, 0.05, dir2.z * push);
        p1.hurtMarked = true;
        p2.hurtMarked = true;
    }

    // ------------------------------------------------------------------ broadcasts

    public static void syncHandRaised(ServerPlayer player, boolean raised) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        HandRaisedSyncMsg msg = new HandRaisedSyncMsg(player.getUUID(), raised);
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            CoopNetwork.sendToPlayer(other, msg);
        }
    }

    private static void broadcastHighFiveAnim(ServerPlayer player, int animState) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        HighFiveAnimMsg msg = new HighFiveAnimMsg(player.getUUID(), animState);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            CoopNetwork.sendToPlayer(p, msg);
        }
    }

    private static void broadcastHighFiveSuccess(MinecraftServer server, double x, double y, double z,
                                                 UUID p1, UUID p2, int tier) {
        if (server == null) return;
        HighFiveSuccessMsg msg = new HighFiveSuccessMsg(x, y, z, p1, p2, tier);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            CoopNetwork.sendToPlayer(p, msg);
        }
    }

    // ------------------------------------------------------------------ networking

    /** C2S: request to raise/lower the hand or connect. */
    public record HighFiveRequestMsg() {
        public static void encode(HighFiveRequestMsg m, FriendlyByteBuf buf) { }
        public static HighFiveRequestMsg decode(FriendlyByteBuf buf) { return new HighFiveRequestMsg(); }
        public static void handle(HighFiveRequestMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player == null) return;
                if (!CoopMovesConfig.get().enableHighFive) return;
                onHighFiveRequest(player);
            });
            c.setPacketHandled(true);
        }
    }

    /** S2C: sync a player's hand-raised flag to all clients. */
    public record HandRaisedSyncMsg(UUID playerId, boolean raised) {
        public static void encode(HandRaisedSyncMsg m, FriendlyByteBuf buf) {
            buf.writeUUID(m.playerId);
            buf.writeBoolean(m.raised);
        }
        public static HandRaisedSyncMsg decode(FriendlyByteBuf buf) {
            return new HandRaisedSyncMsg(buf.readUUID(), buf.readBoolean());
        }
        public static void handle(HandRaisedSyncMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.HighFiveClientHandler.onHandRaisedSync(m.playerId(), m.raised()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    /** S2C: connect succeeded — client plays the tier flash / feedback. */
    public record HighFiveSuccessMsg(double x, double y, double z, UUID player1, UUID player2, int tier) {
        public static void encode(HighFiveSuccessMsg m, FriendlyByteBuf buf) {
            buf.writeDouble(m.x); buf.writeDouble(m.y); buf.writeDouble(m.z);
            buf.writeUUID(m.player1); buf.writeUUID(m.player2); buf.writeInt(m.tier);
        }
        public static HighFiveSuccessMsg decode(FriendlyByteBuf buf) {
            return new HighFiveSuccessMsg(buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readUUID(), buf.readUUID(), buf.readInt());
        }
        public static void handle(HighFiveSuccessMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.HighFiveClientHandler.onHighFiveSuccess(
                                    m.x(), m.y(), m.z(), m.player1(), m.player2(), m.tier()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    /** S2C: drive the client high-five animation states (start/end/hit/sike). */
    public record HighFiveAnimMsg(UUID playerId, int animState) {
        public static void encode(HighFiveAnimMsg m, FriendlyByteBuf buf) {
            buf.writeUUID(m.playerId);
            buf.writeInt(m.animState);
        }
        public static HighFiveAnimMsg decode(FriendlyByteBuf buf) {
            return new HighFiveAnimMsg(buf.readUUID(), buf.readInt());
        }
        public static void handle(HighFiveAnimMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.HighFiveClientHandler.onHighFiveAnim(m.playerId(), m.animState()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    /** C2S: H pressed during the combo window. */
    public record ComboRequestMsg() {
        public static void encode(ComboRequestMsg m, FriendlyByteBuf buf) { }
        public static ComboRequestMsg decode(FriendlyByteBuf buf) { return new ComboRequestMsg(); }
        public static void handle(ComboRequestMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer p = c.getSender();
                if (p != null && CoopMovesConfig.get().enableHighFiveCombo) onComboRequest(p);
            });
            c.setPacketHandled(true);
        }
    }

    /** C2S: H pressed while attacking = sike bait. */
    public record SikeRequestMsg() {
        public static void encode(SikeRequestMsg m, FriendlyByteBuf buf) { }
        public static SikeRequestMsg decode(FriendlyByteBuf buf) { return new SikeRequestMsg(); }
        public static void handle(SikeRequestMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer p = c.getSender();
                if (p != null && CoopMovesConfig.get().enableHighFive) { sikeMode.add(p.getUUID()); onHighFiveRequest(p); }
            });
            c.setPacketHandled(true);
        }
    }

    /** S2C: opens the client "press H for combo" window. */
    public record ComboWindowMsg(UUID playerId) {
        public static void encode(ComboWindowMsg m, FriendlyByteBuf buf) { buf.writeUUID(m.playerId); }
        public static ComboWindowMsg decode(FriendlyByteBuf buf) { return new ComboWindowMsg(buf.readUUID()); }
        public static void handle(ComboWindowMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer())
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.HighFiveClientHandler.onComboWindow(m.playerId()));
            });
            c.setPacketHandled(true);
        }
    }

    /** Registered from CoopNetwork.registerAll() (order-sensitive across sides). */
    public static void registerMessages() {
        CoopNetwork.register(HighFiveRequestMsg.class, HighFiveRequestMsg::encode, HighFiveRequestMsg::decode, HighFiveRequestMsg::handle);
        CoopNetwork.register(HandRaisedSyncMsg.class, HandRaisedSyncMsg::encode, HandRaisedSyncMsg::decode, HandRaisedSyncMsg::handle);
        CoopNetwork.register(HighFiveSuccessMsg.class, HighFiveSuccessMsg::encode, HighFiveSuccessMsg::decode, HighFiveSuccessMsg::handle);
        CoopNetwork.register(HighFiveAnimMsg.class, HighFiveAnimMsg::encode, HighFiveAnimMsg::decode, HighFiveAnimMsg::handle);
        CoopNetwork.register(ComboRequestMsg.class, ComboRequestMsg::encode, ComboRequestMsg::decode, ComboRequestMsg::handle);
        CoopNetwork.register(SikeRequestMsg.class, SikeRequestMsg::encode, SikeRequestMsg::decode, SikeRequestMsg::handle);
        CoopNetwork.register(ComboWindowMsg.class, ComboWindowMsg::encode, ComboWindowMsg::decode, ComboWindowMsg::handle);
    }

    /** Client -> server request helper. */
    public static void sendHighFiveRequest() {
        CoopNetwork.sendToServer(new HighFiveRequestMsg());
    }

    public static void sendComboRequest() {
        CoopNetwork.sendToServer(new ComboRequestMsg());
    }

    public static void sendSikeRequest() {
        CoopNetwork.sendToServer(new SikeRequestMsg());
    }
}
