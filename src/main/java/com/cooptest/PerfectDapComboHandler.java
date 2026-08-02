package com.cooptest;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

/**
 * Perfect-dap combo timing-bar chain ("x1..x∞"). Ported from Fabric to Forge 1.20.1.
 *
 * Driven by ChargedDapHandler (core). Uses DapFusionHandler.FusionQTEPayload for the
 * moving-green timing bar. Anim ordinals are raw ints (PERFECT_DAP_HIT_COMBO=69,
 * _END=74). LightningEntity->LightningBolt; other translations per project template.
 */
public final class PerfectDapComboHandler {

    private PerfectDapComboHandler() {}

    private static final long COMBO_ANIM_MS = 1167L;
    private static final long HIT1_MS = 292L;
    private static final long HIT2_MS = 792L;
    private static final long FIRST_QTE_MS = 813L;
    private static final int GREEN_HALF_BASE = 250;
    private static final int GREEN_SHRINK = 40;
    private static final int GREEN_MIN_HALF = 60;

    private static final int ANIM_COMBO = 69;
    private static final int ANIM_COMBO_END = 74;
    private static final int ANIM_NONE = 0;

    private static final Random RANDOM = new Random();

    private static class ComboSession {
        final UUID p1, p2;
        Vec3 pos;
        int count = 0;
        String button = "G";
        boolean inComboAnim = false;
        boolean qteOpen = false;
        boolean p1Hit = false;
        boolean p2Hit = false;
        long phaseStart = 0;
        boolean hit1Fired = false;
        boolean hit2Fired = false;
        long qteOpenedAt = 0;
        float greenCenterFrac = 0.5f;
        double orbitAngle = 0.0;
        long lastOrbitMs = 0;

        ComboSession(UUID p1, UUID p2, Vec3 pos) {
            this.p1 = p1; this.p2 = p2; this.pos = pos;
            this.phaseStart = System.currentTimeMillis();
        }
        void resetHits() { p1Hit = false; p2Hit = false; }
        boolean bothHit() { return p1Hit && p2Hit; }
        long elapsed() { return System.currentTimeMillis() - phaseStart; }
        void pickButton() { button = RANDOM.nextBoolean() ? "G" : "H"; }
        void rollGreenCenter() { greenCenterFrac = 0.15f + RANDOM.nextFloat() * 0.70f; }
        int greenHalfWidth() { return Math.max(GREEN_MIN_HALF, GREEN_HALF_BASE - count * GREEN_SHRINK); }
    }

    private static final Map<UUID, ComboSession> activeCombos = new HashMap<>();

    public static void register() { }

    public static void openFirstWindow(ServerPlayer p1, ServerPlayer p2, Vec3 pos) {
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        if (activeCombos.containsKey(id1) || activeCombos.containsKey(id2)) return;
        ComboSession s = new ComboSession(id1, id2, pos);
        s.pickButton();
        s.qteOpen = true;
        s.qteOpenedAt = System.currentTimeMillis();
        s.phaseStart = System.currentTimeMillis();
        activeCombos.put(id1, s);
        activeCombos.put(id2, s);
        CoopNetwork.sendToPlayer(p1, new DapFusionHandler.FusionQTEPayload(p1.getUUID(), s.button, 0, 0L, FIRST_QTE_MS, true, 0));
        CoopNetwork.sendToPlayer(p2, new DapFusionHandler.FusionQTEPayload(p2.getUUID(), s.button, 0, 0L, FIRST_QTE_MS, true, 0));
        p1.serverLevel().playSound(null, pos.x, pos.y, pos.z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.8f, 1.6f);
        p1.serverLevel().playSound(null, pos.x, pos.y, pos.z, ModSounds.DAP_HIT.get(), SoundSource.PLAYERS, 0.5f, 1.2f);
    }

    public static boolean onButtonPress(ServerPlayer player, String button) {
        if (!"G".equals(button) && !"H".equals(button) && !"FAIL".equals(button)) return false;
        UUID id = player.getUUID();
        ComboSession s = activeCombos.get(id);
        if (s == null) return false;
        if (s.qteOpen) {
            if ("FAIL".equals(button) || !button.equals(s.button)) {
                failCombo(s, player.getServer(), id);
                return true;
            }
            if (id.equals(s.p1)) s.p1Hit = true;
            else if (id.equals(s.p2)) s.p2Hit = true;
            return true;
        }
        if (s.inComboAnim) {
            if ("FAIL".equals(button) || !button.equals(s.button)) {
                if (id.equals(s.p1)) s.p1Hit = false;
                else if (id.equals(s.p2)) s.p2Hit = false;
            } else {
                if (id.equals(s.p1)) s.p1Hit = true;
                else if (id.equals(s.p2)) s.p2Hit = true;
            }
            return true;
        }
        return false;
    }

    public static boolean isInCombo(UUID id) { return activeCombos.containsKey(id); }

    public static void cancelCombo(UUID id) {
        ComboSession s = activeCombos.get(id);
        if (s == null) return;
        activeCombos.remove(s.p1);
        activeCombos.remove(s.p2);
    }

    public static void tick(MinecraftServer server) {
        Set<ComboSession> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ComboSession s : new ArrayList<>(activeCombos.values())) {
            if (!seen.add(s)) continue;
            tickSession(s, server);
        }
    }

    private static void tickSession(ComboSession s, MinecraftServer server) {
        ServerPlayer p1 = server.getPlayerList().getPlayer(s.p1);
        ServerPlayer p2 = server.getPlayerList().getPlayer(s.p2);
        if (p1 == null || p2 == null) { cleanupOnly(s); return; }
        long elapsed = s.elapsed();
        if (s.inComboAnim) {
            if (s.count >= 3) {
                long nowMs = System.currentTimeMillis();
                long interval = Math.max(40L, 80L - s.count * 4L);
                if (nowMs - s.lastOrbitMs >= interval) {
                    s.lastOrbitMs = nowMs;
                    s.orbitAngle += 0.45;
                    int pts = Math.min(s.count, 8);
                    ServerLevel world = p1.serverLevel();
                    for (ServerPlayer tgt : new ServerPlayer[]{p1, p2}) {
                        Vec3 tp = tgt.position().add(0, 1.1, 0);
                        for (int i = 0; i < pts; i++) {
                            double a = s.orbitAngle + (Math.PI * 2 * i / pts);
                            double r = 0.7 + 0.1 * Math.sin(nowMs / 300.0);
                            world.sendParticles(ParticleTypes.END_ROD, tp.x + Math.cos(a) * r, tp.y, tp.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
                            if (s.count >= 6) {
                                double a2 = -s.orbitAngle * 1.8 + (Math.PI * 2 * i / pts);
                                world.sendParticles(ParticleTypes.ENCHANTED_HIT, tp.x + Math.cos(a2) * 0.4, tp.y + 0.3, tp.z + Math.sin(a2) * 0.4, 1, 0, 0, 0, 0);
                            }
                        }
                    }
                }
            }
            if (!s.hit1Fired && elapsed >= HIT1_MS) { s.hit1Fired = true; fireImpact(p1, p2, s, false); }
            if (!s.hit2Fired && elapsed >= HIT2_MS) { s.hit2Fired = true; fireImpact(p1, p2, s, true); }
            if (elapsed >= COMBO_ANIM_MS) {
                if (s.bothHit()) {
                    s.count++;
                    closeFusionBar(p1, p2, s);
                    String msg = comboMessage(s.count);
                    p1.displayClientMessage(Component.literal(msg), true);
                    p2.displayClientMessage(Component.literal(msg), true);
                    Vec3 mid = p1.position().add(p2.position()).scale(0.5);
                    p1.serverLevel().playSound(null, mid.x, mid.y, mid.z, ModSounds.DAP_HIT.get(), SoundSource.PLAYERS,
                            Math.min(1.5f, 0.9f + s.count * 0.04f), Math.min(2.0f, 1.0f + s.count * 0.07f));
                    float upY = Math.min(0.6f, 0.2f + s.count * 0.025f);
                    p1.setDeltaMovement(p1.getDeltaMovement().add(0, upY, 0)); p1.hurtMarked = true;
                    p2.setDeltaMovement(p2.getDeltaMovement().add(0, upY, 0)); p2.hurtMarked = true;
                    if (s.count >= 3) {
                        Vec3 dir = p2.position().subtract(p1.position()).normalize();
                        double pull = Math.min(0.5, 0.1 + (s.count - 3) * 0.05);
                        p1.setDeltaMovement(p1.getDeltaMovement().add(dir.x * pull, 0, dir.z * pull));
                        p2.setDeltaMovement(p2.getDeltaMovement().add(-dir.x * pull, 0, -dir.z * pull));
                        p1.hurtMarked = true; p2.hurtMarked = true;
                    }
                    startComboCycle(s, p1, p2);
                } else {
                    UUID misser = !s.p1Hit ? s.p1 : (!s.p2Hit ? s.p2 : null);
                    Vec3 dir = p2.position().subtract(p1.position()).normalize();
                    p1.setDeltaMovement(-dir.x * 1.2, -0.5, -dir.z * 1.2);
                    p2.setDeltaMovement(dir.x * 1.2, -0.5, dir.z * 1.2);
                    p1.hurtMarked = true; p2.hurtMarked = true;
                    Vec3 mid2 = p1.position().add(p2.position()).scale(0.5).add(0, 1, 0);
                    p1.serverLevel().sendParticles(ParticleTypes.ANGRY_VILLAGER, mid2.x, mid2.y, mid2.z, 6, 0.3, 0.3, 0.3, 0.05);
                    failCombo(s, server, misser);
                }
            }
            return;
        }
        if (s.qteOpen) {
            if (s.bothHit()) {
                s.qteOpen = false;
                closeFusionBar(p1, p2, s);
                startComboCycle(s, p1, p2);
                return;
            }
            if (elapsed > FIRST_QTE_MS + 200L) {
                closeFusionBar(p1, p2, s);
                failCombo(s, server, null);
            }
        }
    }

    private static void startComboCycle(ComboSession s, ServerPlayer p1, ServerPlayer p2) {
        s.inComboAnim = true;
        s.hit1Fired = false;
        s.hit2Fired = false;
        s.resetHits();
        s.pickButton();
        s.rollGreenCenter();
        s.phaseStart = System.currentTimeMillis();
        s.pos = p1.position().add(p2.position()).scale(0.5).add(0, 1.2, 0);
        PoseNetworking.broadcastAnimState(p1, ANIM_COMBO);
        PoseNetworking.broadcastAnimState(p2, ANIM_COMBO);
        if (s.count == 0) sendTimingQTE(p1, p2, s);
        else sendGreenUpdate(p1, p2, s);
    }

    private static void sendGreenUpdate(ServerPlayer p1, ServerPlayer p2, ComboSession s) {
        long halfWidthMs = Math.max(80L, 300L - s.count * 20L);
        long periodMs = Math.max(600L, 1800L - s.count * 80L);
        int centerInt = Math.round(s.greenCenterFrac * 100f);
        CoopNetwork.sendToPlayer(p1, new DapFusionHandler.FusionQTEPayload(s.p1, s.button, centerInt, halfWidthMs, -periodMs, true, 2));
        CoopNetwork.sendToPlayer(p2, new DapFusionHandler.FusionQTEPayload(s.p2, s.button, centerInt, halfWidthMs, -periodMs, true, 2));
        p1.serverLevel().playSound(null, s.pos.x, s.pos.y, s.pos.z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.0f, Math.min(2.0f, 1.5f + s.count * 0.06f));
    }

    private static void failCombo(ComboSession s, MinecraftServer server, UUID misserId) {
        activeCombos.remove(s.p1);
        activeCombos.remove(s.p2);
        if (server == null) return;
        ServerPlayer p1 = server.getPlayerList().getPlayer(s.p1);
        ServerPlayer p2 = server.getPlayerList().getPlayer(s.p2);
        if (p1 != null) CoopNetwork.sendToPlayer(p1, new DapFusionHandler.FusionQTEPayload(s.p1, "G", 0, 0L, 0L, false, 0));
        if (p2 != null) CoopNetwork.sendToPlayer(p2, new DapFusionHandler.FusionQTEPayload(s.p2, "G", 0, 0L, 0L, false, 0));
        if (p1 != null) PoseNetworking.broadcastAnimState(p1, ANIM_COMBO_END);
        if (p2 != null) PoseNetworking.broadcastAnimState(p2, ANIM_COMBO_END);
        String failMsg = "§c✗ You missed! (Combo x" + s.count + ")";
        String otherMsg = "§c✗ Partner missed! (Combo x" + s.count + ")";
        if (misserId == null) {
            if (p1 != null) p1.displayClientMessage(Component.literal("§c✗ Too slow! (Combo x" + s.count + ")"), true);
            if (p2 != null) p2.displayClientMessage(Component.literal("§c✗ Too slow! (Combo x" + s.count + ")"), true);
        } else {
            ServerPlayer misser = server.getPlayerList().getPlayer(misserId);
            UUID otherId = misserId.equals(s.p1) ? s.p2 : s.p1;
            ServerPlayer other = server.getPlayerList().getPlayer(otherId);
            if (misser != null) misser.displayClientMessage(Component.literal(failMsg), true);
            if (other != null) other.displayClientMessage(Component.literal(otherMsg), true);
        }
        final ServerPlayer fp1 = p1, fp2 = p2;
        new Timer(true).schedule(new TimerTask() {
            @Override public void run() {
                server.execute(() -> {
                    if (fp1 != null && fp1.isAlive()) PoseNetworking.broadcastAnimState(fp1, ANIM_NONE);
                    if (fp2 != null && fp2.isAlive()) PoseNetworking.broadcastAnimState(fp2, ANIM_NONE);
                });
            }
        }, 500L);
    }

    private static void cleanupOnly(ComboSession s) {
        activeCombos.remove(s.p1);
        activeCombos.remove(s.p2);
    }

    private static void fireImpact(ServerPlayer p1, ServerPlayer p2, ComboSession s, boolean isSecond) {
        ServerLevel world = p1.serverLevel();
        Vec3 pos = s.pos;
        int c = Math.min(s.count, 30);
        float pitch = Math.min(2.0f, 1.0f + c * 0.07f);
        float vol = Math.min(1.5f, 0.9f + c * 0.04f);
        if (c >= 5 && isSecond) {
            LightningBolt bolt = new LightningBolt(EntityType.LIGHTNING_BOLT, world);
            bolt.setPos(pos.x, pos.y, pos.z);
            bolt.setVisualOnly(true);
            world.addFreshEntity(bolt);
        }
        if (c >= 3) {
            double shakeAmt = Math.min(0.12, 0.03 + c * 0.01);
            double dx = (RANDOM.nextDouble() - 0.5) * shakeAmt;
            double dz = (RANDOM.nextDouble() - 0.5) * shakeAmt;
            for (ServerPlayer tp : new ServerPlayer[]{p1, p2}) {
                tp.setDeltaMovement(tp.getDeltaMovement().add(dx, 0, dz));
                tp.hurtMarked = true;
            }
        }
        world.playSound(null, pos.x, pos.y, pos.z, ModSounds.DAP_HIT.get(), SoundSource.PLAYERS, vol, pitch);
        if (c <= 2) {
            world.sendParticles(ParticleTypes.CRIT, pos.x, pos.y, pos.z, 10 + c * 2, 0.3, 0.3, 0.3, 0.1);
            world.sendParticles(ParticleTypes.ENCHANTED_HIT, pos.x, pos.y, pos.z, 6 + c, 0.25, 0.25, 0.25, 0.07);
        } else if (c <= 4) {
            world.sendParticles(ParticleTypes.ENCHANTED_HIT, pos.x, pos.y, pos.z, 14 + c * 3, 0.35, 0.35, 0.35, 0.12);
            world.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 6 + c, 0.3, 0.3, 0.3, 0.07);
            world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 0.8f, pitch);
        } else if (c <= 7) {
            world.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, pos.x, pos.y, pos.z, 16 + c * 3, 0.4, 0.4, 0.4, 0.18);
            world.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 8 + c, 0.3, 0.3, 0.3, 0.08);
            if (isSecond) world.sendParticles(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
            world.playSound(null, pos.x, pos.y, pos.z, ModSounds.IMPACT.get(), SoundSource.PLAYERS, vol * 0.9f, pitch);
        } else {
            world.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, pos.x, pos.y, pos.z, 25 + c * 4, 0.5, 0.5, 0.5, 0.22);
            world.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 2, 0.2, 0.2, 0.2, 0);
            world.sendParticles(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 2, 0.1, 0.1, 0.1, 0);
            world.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 12 + c * 2, 0.4, 0.4, 0.4, 0.12);
            world.playSound(null, pos.x, pos.y, pos.z, ModSounds.EPIC_DAP.get(), SoundSource.PLAYERS, vol, pitch);
            world.playSound(null, pos.x, pos.y, pos.z, ModSounds.EXPLOSION_IMPACT.get(), SoundSource.PLAYERS, 0.6f, pitch * 0.8f);
        }
    }

    private static void sendTimingQTE(ServerPlayer p1, ServerPlayer p2, ComboSession s) {
        closeFusionBar(p1, p2, s);
        long halfWidthMs = Math.max(80L, 300L - s.count * 20L);
        long periodMs = Math.max(600L, 1800L - s.count * 80L);
        int centerInt = Math.round(s.greenCenterFrac * 100f);
        CoopNetwork.sendToPlayer(p1, new DapFusionHandler.FusionQTEPayload(s.p1, s.button, centerInt, halfWidthMs, periodMs, true, 2));
        CoopNetwork.sendToPlayer(p2, new DapFusionHandler.FusionQTEPayload(s.p2, s.button, centerInt, halfWidthMs, periodMs, true, 2));
        p1.serverLevel().playSound(null, s.pos.x, s.pos.y, s.pos.z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.9f, Math.min(2.0f, 1.4f + s.count * 0.05f));
    }

    private static void closeFusionBar(ServerPlayer p1, ServerPlayer p2, ComboSession s) {
        CoopNetwork.sendToPlayer(p1, new DapFusionHandler.FusionQTEPayload(s.p1, "G", 0, 0L, 0L, false, 0));
        CoopNetwork.sendToPlayer(p2, new DapFusionHandler.FusionQTEPayload(s.p2, "G", 0, 0L, 0L, false, 0));
    }

    private static String comboMessage(int count) {
        return switch (count) {
            case 1 -> "§e§l⚡ x1";
            case 2 -> "§a§l⚡⚡ x2";
            case 3 -> "§6§l⚡⚡⚡ x3 — HOT!";
            case 4 -> "§c§l x4 — ON FIRE!";
            case 5 -> "§d§l★★ x5 — INSANE!";
            case 6 -> "§b§l✦✦✦ x6 — LEGENDARY!";
            case 7 -> "§f§l⚡⚡⚡ x7 — GOD TIER!";
            default -> "§f§l x" + count + " — INFINITE DAP!";
        };
    }
}
