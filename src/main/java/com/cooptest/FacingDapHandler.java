package com.cooptest;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Facing dap: two players looking directly at each other slide together for a
 * cinematic dap with a swirling aura. Ported from Fabric to Forge 1.20.1.
 *
 * Started by ChargedDapHandler (core, ported later). Translations follow the
 * project template; teleport(...)->teleportTo(...Set<RelativeMovement>...),
 * getRotationVec->getViewVector, getEyePos->getEyePosition, dotProduct->dot,
 * NOTE_BLOCK_BELL.value(); the freeze/impact use ChargedDapHandler payloads.
 */
public final class FacingDapHandler {

    private FacingDapHandler() {}

    private static final double FACING_DOT = 0.97;
    private static final long ANIM_P2_MS      = 4083L;
    private static final long IMPACT_MS       = 670L;
    private static final long IMPACT_FRAME_MS = 750L;
    private static final long AURA_END_MS     = 3500L;
    private static final long AURA_TICK_MS    = 60L;
    private static final double START_DIST    = 2.5;
    private static final double TARGET_DIST   = 1.3;
    private static final long APPROACH_MS     = 420L;

    private static final int ANIM_P1   = 75;
    private static final int ANIM_P2   = 76;
    private static final int ANIM_NONE = 0;

    private static class FacingSession {
        final UUID p1, p2;
        final long startMs;
        boolean impactFired = false;
        boolean impactFrameFired = false;
        boolean auraActive = false;
        long lastAuraTick = 0;
        double auraAngle = 0.0;
        FacingSession(UUID p1, UUID p2) {
            this.p1 = p1; this.p2 = p2;
            this.startMs = System.currentTimeMillis();
        }
        long elapsed() { return System.currentTimeMillis() - startMs; }
    }

    private static final Map<UUID, FacingSession> sessions = new HashMap<>();

    public static void register() { }

    public static boolean areFacingEachOther(ServerPlayer p1, ServerPlayer p2) {
        Vec3 eyes1 = p1.getEyePosition();
        Vec3 eyes2 = p2.getEyePosition();
        Vec3 look1 = p1.getViewVector(1.0f);
        Vec3 look2 = p2.getViewVector(1.0f);
        Vec3 to2 = eyes2.subtract(eyes1).normalize();
        Vec3 to1 = eyes1.subtract(eyes2).normalize();
        return look1.dot(to2) >= FACING_DOT && look2.dot(to1) >= FACING_DOT;
    }

    public static void start(ServerPlayer p1, ServerPlayer p2) {
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        if (sessions.containsKey(id1) || sessions.containsKey(id2)) return;
        FacingSession s = new FacingSession(id1, id2);
        sessions.put(id1, s);
        sessions.put(id2, s);
        ServerLevel world = p1.serverLevel();
        Vec3 mid = p1.position().add(p2.position()).scale(0.5);
        Vec3 flatDir = p2.position().subtract(p1.position());
        flatDir = new Vec3(flatDir.x, 0, flatDir.z).normalize();
        Vec3 startPos1 = mid.subtract(flatDir.scale(START_DIST * 0.5));
        Vec3 startPos2 = mid.add(flatDir.scale(START_DIST * 0.5));
        float yaw1 = (float) (-Math.toDegrees(Math.atan2(flatDir.x, flatDir.z)));
        float yaw2 = yaw1 + 180f;
        p1.teleportTo(world, startPos1.x, p1.getY(), startPos1.z, Set.<RelativeMovement>of(), yaw1, 0);
        p2.teleportTo(world, startPos2.x, p2.getY(), startPos2.z, Set.<RelativeMovement>of(), yaw2, 0);
        PoseNetworking.broadcastAnimState(p1, ANIM_P1);
        PoseNetworking.broadcastAnimState(p2, ANIM_P2);
        CoopNetwork.sendToPlayer(p1, new ChargedDapHandler.PerfectDapFreezePayload(true));
        CoopNetwork.sendToPlayer(p2, new ChargedDapHandler.PerfectDapFreezePayload(true));
        world.playSound(null, mid.x, mid.y + 1, mid.z,
                SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.2f, 1.8f);
        world.playSound(null, mid.x, mid.y + 1, mid.z,
                ModSounds.DAP_HIT.get(), SoundSource.PLAYERS, 1.0f, 1.5f);
    }

    public static void tick(MinecraftServer server) {
        Set<FacingSession> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (FacingSession s : new ArrayList<>(sessions.values())) {
            if (!seen.add(s)) continue;
            tickSession(s, server);
        }
    }

    private static void tickSession(FacingSession s, MinecraftServer server) {
        ServerPlayer p1 = server.getPlayerList().getPlayer(s.p1);
        ServerPlayer p2 = server.getPlayerList().getPlayer(s.p2);
        if (p1 == null || p2 == null) { cleanup(s); return; }
        long elapsed = s.elapsed();
        ServerLevel world = p1.serverLevel();
        if (elapsed <= APPROACH_MS) {
            double t = (double) elapsed / APPROACH_MS;
            double currentDist = START_DIST + (TARGET_DIST - START_DIST) * t;
            Vec3 mid2 = p1.position().add(p2.position()).scale(0.5);
            Vec3 fd = p2.position().subtract(p1.position());
            fd = new Vec3(fd.x, 0, fd.z).normalize();
            double halfDist = currentDist * 0.5;
            Vec3 np1 = mid2.subtract(fd.scale(halfDist));
            Vec3 np2 = mid2.add(fd.scale(halfDist));
            p1.teleportTo(world, np1.x, p1.getY(), np1.z, Set.<RelativeMovement>of(), p1.getYRot(), 0);
            p2.teleportTo(world, np2.x, p2.getY(), np2.z, Set.<RelativeMovement>of(), p2.getYRot(), 0);
        }
        if (!s.impactFired && elapsed >= IMPACT_MS) {
            s.impactFired = true;
            s.auraActive = true;
            Vec3 mid = p1.position().add(p2.position()).scale(0.5).add(0, 1.2, 0);
            world.sendParticles(ParticleTypes.CRIT, mid.x, mid.y, mid.z, 20, 0.4, 0.4, 0.4, 0.15);
            world.sendParticles(ParticleTypes.ENCHANTED_HIT, mid.x, mid.y, mid.z, 15, 0.3, 0.3, 0.3, 0.12);
            world.sendParticles(ParticleTypes.FLASH, mid.x, mid.y, mid.z, 2, 0, 0, 0, 0);
            world.sendParticles(ParticleTypes.END_ROD, mid.x, mid.y, mid.z, 12, 0.3, 0.3, 0.3, 0.10);
        }
        if (!s.impactFrameFired && elapsed >= IMPACT_FRAME_MS) {
            s.impactFrameFired = true;
            Vec3 mid = p1.position().add(p2.position()).scale(0.5).add(0, 1.2, 0);
            world.playSound(null, mid.x, mid.y, mid.z, ModSounds.PERFECT_DAP.get(), SoundSource.PLAYERS, 1.5f, 1.0f);
            int ringPoints = 16;
            double ringRadius = 0.2;
            for (int pass = 0; pass < 3; pass++) {
                double r = ringRadius + pass * 1.2;
                for (int i = 0; i < ringPoints; i++) {
                    double angle = (Math.PI * 2 * i / ringPoints);
                    double px = mid.x + r * Math.cos(angle);
                    double pz = mid.z + r * Math.sin(angle);
                    world.sendParticles(ParticleTypes.END_ROD, px, mid.y, pz, 1, 0, 0.1, 0, 0.02);
                    world.sendParticles(ParticleTypes.CRIT, px, mid.y, pz, 1, 0, 0.05, 0, 0.01);
                }
            }
            world.sendParticles(ParticleTypes.FLASH, mid.x, mid.y, mid.z, 3, 0.05, 0.05, 0.05, 0);
            world.sendParticles(ParticleTypes.EXPLOSION_EMITTER, mid.x, mid.y, mid.z, 1, 0, 0, 0, 0);
            CoopNetwork.sendToPlayer(p1, new ChargedDapHandler.FacingDapImpactPayload());
            CoopNetwork.sendToPlayer(p2, new ChargedDapHandler.FacingDapImpactPayload());
        }
        if (s.auraActive && elapsed < AURA_END_MS) {
            long nowMs = System.currentTimeMillis();
            if (nowMs - s.lastAuraTick >= AURA_TICK_MS) {
                s.lastAuraTick = nowMs;
                s.auraAngle += 0.3;
                float alpha = Math.max(0f, 1f - (float) (elapsed - IMPACT_MS) / (AURA_END_MS - IMPACT_MS));
                int orbCount = Math.max(3, (int) (8 * alpha));
                double orbRadius = 0.65 + 0.1 * Math.sin(elapsed / 400.0);
                for (ServerPlayer tgt : new ServerPlayer[]{p1, p2}) {
                    Vec3 pp = tgt.position().add(0, 1.0, 0);
                    for (int i = 0; i < orbCount; i++) {
                        double a = s.auraAngle + (Math.PI * 2 * i / orbCount);
                        world.sendParticles(ParticleTypes.END_ROD,
                                pp.x + Math.cos(a) * orbRadius, pp.y, pp.z + Math.sin(a) * orbRadius, 1, 0, 0.02, 0, 0);
                        if (alpha > 0.5f) {
                            double a2 = -s.auraAngle * 1.6 + (Math.PI * 2 * i / orbCount);
                            world.sendParticles(ParticleTypes.ENCHANTED_HIT,
                                    pp.x + Math.cos(a2) * orbRadius * 0.5, pp.y + 0.3, pp.z + Math.sin(a2) * orbRadius * 0.5, 1, 0, 0, 0, 0);
                        }
                    }
                }
            }
        }
        if (elapsed >= ANIM_P2_MS) {
            CoopNetwork.sendToPlayer(p1, new ChargedDapHandler.PerfectDapFreezePayload(false));
            CoopNetwork.sendToPlayer(p2, new ChargedDapHandler.PerfectDapFreezePayload(false));
            PoseNetworking.broadcastAnimState(p1, ANIM_NONE);
            PoseNetworking.broadcastAnimState(p2, ANIM_NONE);
            cleanup(s);
        }
    }

    private static void cleanup(FacingSession s) {
        sessions.remove(s.p1);
        sessions.remove(s.p2);
    }

    public static boolean isActive(UUID id) { return sessions.containsKey(id); }
}
