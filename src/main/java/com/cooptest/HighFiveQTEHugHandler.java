package com.cooptest;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * High-five QTE-hug — after a high-five, a G-QTE window opens; pressing G turns the
 * high-five into a choreographed hug (with an inner QTE for the "hug2" finish).
 * Ported from Fabric to Forge 1.20.1. Reuses {@link DapFusionHandler.FusionQTEPayload}
 * for the QTE UI; the button press is routed here by the QTEManager dispatch chain.
 * Anim ordinals hardcoded (HIGHFIVE_HUG=59, HIGHFIVE_HUG2=60).
 */
public final class HighFiveQTEHugHandler {

    private HighFiveQTEHugHandler() {}

    private static final long HUG_QTE_WINDOW_MS = 1000;
    private static final int TICK_RIGHT_PARTICLES = 6;
    private static final int TICK_DISTANCE_ADJUST = 22;
    private static final int TICK_LEFT_FX_1 = 34;
    private static final int TICK_LEFT_FX_2 = 38;
    private static final int TICK_INNER_QTE_OPEN = 33;
    private static final int TICK_PUSH_BACK = 68;
    private static final int TICK_INNER_EVALUATE = 70;
    private static final int TICK_HUG2 = 73;
    private static final int TICK_END = 88;
    private static final double DIST_CLOSE = 1.0;
    private static final double DIST_FAR = 1.2;
    private static final int ANIM_HIGHFIVE_HUG = 59;
    private static final int ANIM_HIGHFIVE_HUG2 = 60;

    public static void register() { }
    public static void registerMessages() { }

    private static class HugSession {
        final UUID p1Id, p2Id;
        ServerPlayer p1Ref, p2Ref;
        final long entryWindowEnd;
        boolean started = false;
        int tick = 0;
        boolean innerQTESent = false;
        boolean p1InnerHit = false, p2InnerHit = false;
        boolean innerEvaluated = false;
        boolean hug2Started = false;
        int hug2Tick = 0;
        HugSession(ServerPlayer p1, ServerPlayer p2) {
            p1Id = p1.getUUID(); p2Id = p2.getUUID();
            p1Ref = p1; p2Ref = p2;
            entryWindowEnd = System.currentTimeMillis() + HUG_QTE_WINDOW_MS;
        }
    }

    private static final Map<UUID, HugSession> sessions = new HashMap<>();

    public static void startHugQTE(ServerPlayer p1, ServerPlayer p2) {
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        if (sessions.containsKey(id1) || sessions.containsKey(id2)) return;
        HugSession s = new HugSession(p1, p2);
        sessions.put(id1, s);
        sessions.put(id2, s);
        CoopNetwork.sendToPlayer(p1, new DapFusionHandler.FusionQTEPayload(id1, "G", 1, 0L, HUG_QTE_WINDOW_MS, true, 0));
        CoopNetwork.sendToPlayer(p2, new DapFusionHandler.FusionQTEPayload(id2, "G", 1, 0L, HUG_QTE_WINDOW_MS, true, 0));
    }

    public static boolean isInHugSession(UUID id) { return sessions.containsKey(id); }

    public static boolean onButtonPress(ServerPlayer player, String button) {
        UUID id = player.getUUID();
        HugSession s = sessions.get(id);
        if (s == null) return false;
        if (!s.started) {
            if (System.currentTimeMillis() > s.entryWindowEnd) return false;
            CoopNetwork.sendToPlayer(s.p1Ref, new DapFusionHandler.FusionQTEPayload(s.p1Id, "G", 0, 0L, 0L, false, 0));
            CoopNetwork.sendToPlayer(s.p2Ref, new DapFusionHandler.FusionQTEPayload(s.p2Id, "G", 0, 0L, 0L, false, 0));
            if ("G".equals(button)) executeHug(s); else cleanup(id);
            return true;
        }
        if (s.innerQTESent && !s.innerEvaluated) {
            if ("G".equals(button)) {
                if (id.equals(s.p1Id)) s.p1InnerHit = true;
                else if (id.equals(s.p2Id)) s.p2InnerHit = true;
            }
            return true;
        }
        return false;
    }

    public static void cleanup(UUID id) {
        HugSession s = sessions.remove(id);
        if (s == null) return;
        sessions.remove(s.p1Id);
        sessions.remove(s.p2Id);
    }

    private static void executeHug(HugSession s) {
        s.started = true;
        s.tick = 0;
        ServerPlayer p1 = s.p1Ref, p2 = s.p2Ref;
        Vec3 pos1 = p1.position(), pos2 = p2.position();
        double dx = pos2.x - pos1.x, dz = pos2.z - pos1.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len >= 0.001) {
            float yaw1 = (float) (Math.atan2(dz, dx) * 180 / Math.PI) - 90;
            float yaw2 = yaw1 + 180;
            p1.setYRot(yaw1); p1.setYBodyRot(yaw1); p1.setYHeadRot(yaw1);
            p1.yRotO = yaw1; p1.yBodyRotO = yaw1; p1.yHeadRotO = yaw1;
            p2.setYRot(yaw2); p2.setYBodyRot(yaw2); p2.setYHeadRot(yaw2);
            p2.yRotO = yaw2; p2.yBodyRotO = yaw2; p2.yHeadRotO = yaw2;
        }
        DapSessionManager.createSession(p1.getUUID(), p2.getUUID(), 1.2, DapSession.DapType.NORMAL_DAP);
        CoopNetwork.sendToPlayer(p1, new ChargedDapHandler.PerfectDapFreezePayload(true));
        CoopNetwork.sendToPlayer(p2, new ChargedDapHandler.PerfectDapFreezePayload(true));
        PoseNetworking.broadcastAnimState(p1, ANIM_HIGHFIVE_HUG);
        PoseNetworking.broadcastAnimState(p2, ANIM_HIGHFIVE_HUG);
        p1.swing(InteractionHand.MAIN_HAND, true);
        p2.swing(InteractionHand.MAIN_HAND, true);
    }

    public static void tick(MinecraftServer server) {
        Set<HugSession> processed = new HashSet<>();
        for (HugSession s : new ArrayList<>(sessions.values())) {
            if (!processed.add(s)) continue;
            ServerPlayer p1 = server.getPlayerList().getPlayer(s.p1Id);
            ServerPlayer p2 = server.getPlayerList().getPlayer(s.p2Id);
            if (p1 == null || p2 == null) { cleanup(s.p1Id); continue; }
            s.p1Ref = p1; s.p2Ref = p2;
            if (!s.started) {
                if (System.currentTimeMillis() > s.entryWindowEnd) {
                    CoopNetwork.sendToPlayer(p1, new DapFusionHandler.FusionQTEPayload(s.p1Id, "G", 0, 0L, 0L, false, 0));
                    CoopNetwork.sendToPlayer(p2, new DapFusionHandler.FusionQTEPayload(s.p2Id, "G", 0, 0L, 0L, false, 0));
                    cleanup(s.p1Id);
                }
                continue;
            }
            s.tick++;
            ServerLevel world = p1.serverLevel();

            if (s.tick == TICK_RIGHT_PARTICLES) {
                for (ServerPlayer p : List.of(p1, p2)) {
                    Vec3 arm = getRightArmTip(p);
                    world.sendParticles(ParticleTypes.CRIT, arm.x, arm.y, arm.z, 8, 0.1, 0.1, 0.1, 0.08);
                    world.sendParticles(ParticleTypes.ENCHANTED_HIT, arm.x, arm.y, arm.z, 5, 0.08, 0.08, 0.08, 0.05);
                }
                world.playSound(null, p1.getX(), p1.getY(), p1.getZ(), ModSounds.DAP_HIT.get(), SoundSource.PLAYERS, 1.2f, 1.0f);
            }
            if (s.tick == TICK_DISTANCE_ADJUST) adjustDistance(p1, p2, DIST_CLOSE);
            if (s.tick == TICK_LEFT_FX_1 || s.tick == TICK_LEFT_FX_2) playLeftArmFX(p1, p2, world);
            if (s.tick == TICK_INNER_QTE_OPEN && !s.innerQTESent) {
                s.innerQTESent = true;
                CoopNetwork.sendToPlayer(p1, new DapFusionHandler.FusionQTEPayload(s.p1Id, "G", 1, 1100L, 1400L, true, 1));
                CoopNetwork.sendToPlayer(p2, new DapFusionHandler.FusionQTEPayload(s.p2Id, "G", 1, 1100L, 1400L, true, 1));
            }
            if (s.tick == TICK_PUSH_BACK) adjustDistance(p1, p2, DIST_FAR);
            if (s.tick == TICK_INNER_EVALUATE && !s.innerEvaluated) {
                s.innerEvaluated = true;
                CoopNetwork.sendToPlayer(p1, new DapFusionHandler.FusionQTEPayload(s.p1Id, "", 0, 0, 0, false, 0));
                CoopNetwork.sendToPlayer(p2, new DapFusionHandler.FusionQTEPayload(s.p2Id, "", 0, 0, 0, false, 0));
            }
            if (s.tick == TICK_HUG2 && !s.hug2Started) {
                s.hug2Started = true;
                if (s.p1InnerHit && s.p2InnerHit) {
                    PoseNetworking.broadcastAnimState(p1, ANIM_HIGHFIVE_HUG2);
                    PoseNetworking.broadcastAnimState(p2, ANIM_HIGHFIVE_HUG2);
                }
            }
            if (s.hug2Started && s.p1InnerHit && s.p2InnerHit) {
                s.hug2Tick++;
                if (s.hug2Tick == 6) {
                    for (ServerPlayer p : List.of(p1, p2)) {
                        Vec3 arm = getRightArmTip(p);
                        world.sendParticles(ParticleTypes.CRIT, arm.x, arm.y, arm.z, 10, 0.1, 0.1, 0.1, 0.08);
                        world.sendParticles(ParticleTypes.ENCHANTED_HIT, arm.x, arm.y, arm.z, 6, 0.08, 0.08, 0.08, 0.05);
                    }
                    world.playSound(null, p1.getX(), p1.getY(), p1.getZ(), ModSounds.DAP_HIT.get(), SoundSource.PLAYERS, 1.3f, 1.0f);
                    world.playSound(null, p1.getX(), p1.getY(), p1.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 0.8f, 1.2f);
                }
                if (s.hug2Tick == 26) {
                    world.playSound(null, p1.getX(), p1.getY(), p1.getZ(), ModSounds.SNAP.get(), SoundSource.PLAYERS, 1.5f, 1.0f);
                    world.playSound(null, p1.getX(), p1.getY(), p1.getZ(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.8f, 2.0f);
                    Vec3 mid = p1.position().add(p2.position()).scale(0.5).add(0, 1.2, 0);
                    world.sendParticles(ParticleTypes.CLOUD, mid.x, mid.y, mid.z, 12, 0.2, 0.2, 0.2, 0.04);
                    world.sendParticles(ParticleTypes.POOF, mid.x, mid.y, mid.z, 8, 0.15, 0.15, 0.15, 0.03);
                }
            }
            int endTick = (s.hug2Started && s.p1InnerHit && s.p2InnerHit) ? 124 : TICK_END;
            if (s.tick >= endTick) {
                CoopNetwork.sendToPlayer(p1, new ChargedDapHandler.PerfectDapFreezePayload(false));
                CoopNetwork.sendToPlayer(p2, new ChargedDapHandler.PerfectDapFreezePayload(false));
                PoseNetworking.broadcastAnimState(p1, 0);
                PoseNetworking.broadcastAnimState(p2, 0);
                DapSessionManager.removeSessionForPlayer(s.p1Id);
                cleanup(s.p1Id);
            }
        }
    }

    private static void adjustDistance(ServerPlayer p1, ServerPlayer p2, double targetDist) {
        Vec3 pos1 = p1.position(), pos2 = p2.position();
        double dx = pos2.x - pos1.x, dz = pos2.z - pos1.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return;
        Vec3 dir = new Vec3(dx / len, 0, dz / len);
        Vec3 mid = pos1.add(pos2).scale(0.5);
        Vec3 t1 = mid.subtract(dir.scale(targetDist / 2.0));
        Vec3 t2 = mid.add(dir.scale(targetDist / 2.0));
        p1.teleportTo(p1.serverLevel(), t1.x, t1.y, t1.z, p1.getYRot(), p1.getXRot());
        p2.teleportTo(p2.serverLevel(), t2.x, t2.y, t2.z, p2.getYRot(), p2.getXRot());
    }

    private static void playLeftArmFX(ServerPlayer p1, ServerPlayer p2, ServerLevel world) {
        for (ServerPlayer p : List.of(p1, p2)) {
            Vec3 arm = getLeftArmTip(p);
            world.sendParticles(ParticleTypes.CRIT, arm.x, arm.y, arm.z, 6, 0.1, 0.1, 0.1, 0.06);
        }
        world.playSound(null, p1.getX(), p1.getY(), p1.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.9f, 1.1f);
        p1.swing(InteractionHand.MAIN_HAND, true);
        p2.swing(InteractionHand.MAIN_HAND, true);
    }

    private static Vec3 getRightArmTip(ServerPlayer p) {
        double yaw = Math.toRadians(p.yBodyRot);
        return new Vec3(p.getX() + -Math.cos(yaw) * 0.3 + -Math.sin(yaw) * 0.4, p.getY() + 1.0,
                p.getZ() + Math.sin(yaw) * 0.3 + Math.cos(yaw) * 0.4);
    }

    private static Vec3 getLeftArmTip(ServerPlayer p) {
        double yaw = Math.toRadians(p.yBodyRot);
        return new Vec3(p.getX() + Math.cos(yaw) * 0.4 + -Math.sin(yaw) * 0.3, p.getY() + 1.3,
                p.getZ() + -Math.sin(yaw) * 0.4 + Math.cos(yaw) * 0.3);
    }
}
