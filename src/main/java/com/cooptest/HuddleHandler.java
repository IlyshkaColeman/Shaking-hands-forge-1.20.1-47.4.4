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
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.*;
import java.util.function.Supplier;

/**
 * Group "huddle": two or more players hold F near each other to gather in a circle
 * around a center; releasing F starts a 3-step G/H QTE that all members must hit. Full
 * success grants buffs + XP. Ported from Fabric to Forge 1.20.1. The elaborate
 * firework/Timer success choreography is reduced to a single burst; core gameplay
 * (formation, joining, circle, QTE steps, success/fail) is preserved. QTE presses are
 * routed here by the QTEManager dispatch chain. Anim ordinals hardcoded.
 */
public final class HuddleHandler {

    private HuddleHandler() {}

    private static final double HUDDLE_RANGE = 2.0;
    private static final double HUDDLE_RADIUS = 1.0;
    private static final long HOLD_REQUIRED_MS = 800;
    private static final long F_RELEASE_GRACE_MS = 1200;
    private static final long QTE_WINDOW_MS = 1400;
    private static final long QTE_ANIM_PRE_MS = 400;
    private static final long HUDDLE_MAX_MS = 20_000;
    private static final long COOLDOWN_MS = 8_000;
    private static final long HUDDLE_START_MS = 542;
    private static final int ANIM_HUDDLE_START = 70;
    private static final int ANIM_HUDDLE_IDLE = 71;
    private static final int ANIM_HUDDLE_QTE1 = 72;
    private static final int ANIM_HUDDLE_END = 73;
    private static final int ANIM_HUDDLE_QTE2 = 77;
    private static final int ANIM_HUDDLE_QTE3 = 78;
    private static final int ANIM_NONE = 0;
    private static final Random RANDOM = new Random();

    private enum HuddleStage { ENTERING, IDLE, QTE, ENDING, DONE }

    private static class HuddleSession {
        final List<UUID> players;
        final UUID p1, p2;
        HuddleStage stage = HuddleStage.ENTERING;
        long stageStart = System.currentTimeMillis();
        final long huddleStart = System.currentTimeMillis();
        Set<UUID> holdsF;
        Long firstRelease = null;
        int qteStep = 0;
        final String qteBtn1, qteBtn2, qteBtn3;
        Set<UUID> qteHits = new HashSet<>();
        boolean qteOpen = false;
        boolean qteAnimStarted = false;
        long qteAnimStart = 0;
        long lastAuraTick = 0;
        double auraAngle = 0.0;
        int stepsHit = 0;
        HuddleSession(List<UUID> playerList) {
            this.players = new ArrayList<>(playerList);
            this.p1 = playerList.get(0);
            this.p2 = playerList.get(1);
            this.holdsF = new HashSet<>(playerList);
            qteBtn1 = RANDOM.nextBoolean() ? "G" : "H";
            qteBtn2 = qteBtn1.equals("G") ? "H" : "G";
            qteBtn3 = RANDOM.nextBoolean() ? "G" : "H";
        }
        void resetQTE() { qteHits.clear(); qteOpen = false; qteAnimStarted = false; }
        boolean allHit() { return qteHits.containsAll(players); }
        boolean anyReleasedF() { return !holdsF.containsAll(players); }
        long elapsed() { return System.currentTimeMillis() - stageStart; }
        String expectedButton() {
            return switch (qteStep) { case 1 -> qteBtn1; case 2 -> qteBtn2; case 3 -> qteBtn3; default -> "G"; };
        }
    }

    private static final Map<String, HuddleSession> sessions = new HashMap<>();
    private static final Map<UUID, String> playerSession = new HashMap<>();
    private static final Map<UUID, Long> fHoldStart = new HashMap<>();
    private static final Map<String, Long> cooldowns = new HashMap<>();
    private static final Map<String, ArmorStand> centerStands = new HashMap<>();

    private static String key(UUID a, UUID b) { return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a; }

    // ------------------------------------------------------------------ messages
    public record HuddleFHoldMsg(boolean holding) {
        public static void encode(HuddleFHoldMsg m, FriendlyByteBuf buf) { buf.writeBoolean(m.holding); }
        public static HuddleFHoldMsg decode(FriendlyByteBuf buf) { return new HuddleFHoldMsg(buf.readBoolean()); }
        public static void handle(HuddleFHoldMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { ServerPlayer p = c.getSender(); if (p != null) onFHold(p, m.holding()); });
            c.setPacketHandled(true);
        }
    }

    public record HuddleEndMsg(UUID p1, UUID p2, boolean success) {
        public static void encode(HuddleEndMsg m, FriendlyByteBuf buf) { buf.writeUUID(m.p1); buf.writeUUID(m.p2); buf.writeBoolean(m.success); }
        public static HuddleEndMsg decode(FriendlyByteBuf buf) { return new HuddleEndMsg(buf.readUUID(), buf.readUUID(), buf.readBoolean()); }
        public static void handle(HuddleEndMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { /* client cue only; no-op */ });
            c.setPacketHandled(true);
        }
    }

    public static void register() { }

    public static void registerMessages() {
        CoopNetwork.register(HuddleFHoldMsg.class, HuddleFHoldMsg::encode, HuddleFHoldMsg::decode, HuddleFHoldMsg::handle);
        CoopNetwork.register(HuddleEndMsg.class, HuddleEndMsg::encode, HuddleEndMsg::decode, HuddleEndMsg::handle);
    }

    private static void onFHold(ServerPlayer player, boolean holding) {
        UUID id = player.getUUID();
        String sk = playerSession.get(id);
        if (holding && sk == null && HighFiveHandler.isInBlockingState(id)) return;
        if (sk != null) {
            HuddleSession s = sessions.get(sk);
            if (s != null) {
                if (holding) s.holdsF.add(id); else s.holdsF.remove(id);
                if (s.stage == HuddleStage.IDLE && !holding && s.firstRelease == null)
                    s.firstRelease = System.currentTimeMillis();
                return;
            }
        }
        PoseState pose = PoseNetworking.poseStates.getOrDefault(id, PoseState.NONE);
        if (pose == PoseState.GRAB_READY || pose == PoseState.GRAB_HOLDING
                || pose == PoseState.GRABBED || pose == PoseState.PUSH_IDLE) return;
        if (holding) fHoldStart.putIfAbsent(id, System.currentTimeMillis());
        else fHoldStart.remove(id);
    }

    public static boolean onButtonPress(ServerPlayer player, String button) {
        UUID id = player.getUUID();
        String sk = playerSession.get(id);
        if (sk == null) return false;
        HuddleSession s = sessions.get(sk);
        if (s == null || s.stage != HuddleStage.QTE || !s.qteOpen) return false;
        if ("FAIL".equals(button)) { failHuddle(s, player.getServer()); return true; }
        if (!"G".equals(button) && !"H".equals(button)) return false;
        if (!button.equals(s.expectedButton())) { failHuddle(s, player.getServer()); return true; }
        if (s.players.contains(id)) s.qteHits.add(id);
        return true;
    }

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (server.getTickCount() % 4 == 0) detectNewHuddles(server, now);
        Set<HuddleSession> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (HuddleSession s : new ArrayList<>(sessions.values())) {
            if (!seen.add(s)) continue;
            tickSession(s, server, now);
        }
        cooldowns.entrySet().removeIf(e -> now - e.getValue() > COOLDOWN_MS);
    }

    private static void detectNewHuddles(MinecraftServer server, long now) {
        List<UUID> holders = new ArrayList<>(fHoldStart.keySet());
        // join existing idle huddles
        for (UUID joiner : holders) {
            if (playerSession.containsKey(joiner)) continue;
            Long startJ = fHoldStart.get(joiner);
            if (startJ == null || now - startJ < HOLD_REQUIRED_MS) continue;
            ServerPlayer pj = server.getPlayerList().getPlayer(joiner);
            if (pj == null) continue;
            for (HuddleSession s : new ArrayList<>(sessions.values())) {
                if (s.stage != HuddleStage.IDLE || s.players.contains(joiner)) continue;
                ArmorStand stand = centerStands.get(key(s.p1, s.p2));
                if (stand == null || pj.distanceTo(stand) > HUDDLE_RANGE * 1.5) continue;
                s.players.add(joiner);
                s.holdsF.add(joiner);
                playerSession.put(joiner, key(s.p1, s.p2));
                fHoldStart.remove(joiner);
                repositionCircle(s, server);
                PoseNetworking.broadcastAnimState(pj, ANIM_HUDDLE_START);
                pj.swing(InteractionHand.MAIN_HAND, true);
                Vec3 sPos = stand.position();
                pj.serverLevel().playSound(null, sPos.x, sPos.y, sPos.z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.0f, 1.7f);
                pj.displayClientMessage(Component.literal("§aYou joined the huddle!"), true);
                for (UUID pid : s.players) {
                    if (pid.equals(joiner)) continue;
                    ServerPlayer pp = server.getPlayerList().getPlayer(pid);
                    if (pp != null) pp.displayClientMessage(Component.literal("§a" + pj.getName().getString() + " joined the huddle!"), true);
                }
                break;
            }
        }
        // form new 2-player huddles
        for (int i = 0; i < holders.size(); i++) {
            UUID a = holders.get(i);
            if (playerSession.containsKey(a)) continue;
            Long startA = fHoldStart.get(a);
            if (startA == null || now - startA < HOLD_REQUIRED_MS) continue;
            ServerPlayer pa = server.getPlayerList().getPlayer(a);
            if (pa == null) continue;
            for (int j = i + 1; j < holders.size(); j++) {
                UUID b = holders.get(j);
                if (playerSession.containsKey(b)) continue;
                Long startB = fHoldStart.get(b);
                if (startB == null || now - startB < HOLD_REQUIRED_MS) continue;
                ServerPlayer pb = server.getPlayerList().getPlayer(b);
                if (pb == null || pa.distanceTo(pb) > HUDDLE_RANGE) continue;
                String k = key(a, b);
                if (cooldowns.containsKey(k)) continue;
                HuddleSession s = new HuddleSession(new ArrayList<>(List.of(a, b)));
                sessions.put(k, s);
                playerSession.put(a, k);
                playerSession.put(b, k);
                fHoldStart.remove(a);
                fHoldStart.remove(b);
                positionAndSpawnStand(s, pa, pb, server);
                break;
            }
        }
    }

    private static void positionAndSpawnStand(HuddleSession s, ServerPlayer pa, ServerPlayer pb, MinecraftServer server) {
        Vec3 mid = pa.position().add(pb.position()).scale(0.5);
        ServerLevel world = pa.serverLevel();
        ArmorStand stand = new ArmorStand(world, mid.x, mid.y, mid.z);
        stand.setInvisible(true); stand.setNoGravity(true); stand.setInvulnerable(true); stand.setSilent(true);
        world.addFreshEntity(stand);
        centerStands.put(key(s.p1, s.p2), stand);
        repositionCircle(s, server);
        for (UUID uid : s.players) {
            ServerPlayer p = server.getPlayerList().getPlayer(uid);
            if (p != null) { PoseNetworking.broadcastAnimState(p, ANIM_HUDDLE_START); p.swing(InteractionHand.MAIN_HAND, true); }
        }
        world.playSound(null, mid.x, mid.y, mid.z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.0f, 1.5f);
        world.sendParticles(ParticleTypes.ENCHANTED_HIT, mid.x, mid.y + 1, mid.z, 12, 0.4, 0.4, 0.4, 0.06);
    }

    private static void repositionCircle(HuddleSession s, MinecraftServer server) {
        ArmorStand stand = centerStands.get(key(s.p1, s.p2));
        if (stand == null) return;
        Vec3 center = stand.position();
        int n = s.players.size();
        ServerPlayer p1p = server.getPlayerList().getPlayer(s.p1);
        double baseAngle = 0;
        if (p1p != null) {
            Vec3 toP1 = p1p.position().subtract(center);
            if (toP1.horizontalDistanceSqr() > 0.001) baseAngle = Math.atan2(toP1.z, toP1.x);
        }
        for (int ci = 0; ci < n; ci++) {
            ServerPlayer p = server.getPlayerList().getPlayer(s.players.get(ci));
            if (p == null) continue;
            double angle = baseAngle + (Math.PI * 2 * ci / n);
            double px = center.x + HUDDLE_RADIUS * Math.cos(angle);
            double pz = center.z + HUDDLE_RADIUS * Math.sin(angle);
            float yaw = (float) (-Math.toDegrees(Math.atan2(center.x - px, center.z - pz)));
            p.teleportTo(p.serverLevel(), px, p.getY(), pz, yaw, 0);
            p.setYRot(yaw); p.setYBodyRot(yaw); p.setYHeadRot(yaw);
        }
    }

    private static void tickSession(HuddleSession s, MinecraftServer server, long now) {
        ServerPlayer p1 = server.getPlayerList().getPlayer(s.p1);
        ServerPlayer p2 = server.getPlayerList().getPlayer(s.p2);
        if (p1 == null || p2 == null) { failHuddle(s, server); return; }
        List<ServerPlayer> live = new ArrayList<>();
        for (UUID uid : s.players) {
            ServerPlayer lp = server.getPlayerList().getPlayer(uid);
            if (lp == null) { failHuddle(s, server); return; }
            live.add(lp);
        }
        if (now - s.huddleStart > HUDDLE_MAX_MS) { failHuddle(s, server); return; }

        switch (s.stage) {
            case ENTERING -> {
                if (s.elapsed() >= HUDDLE_START_MS) {
                    s.stage = HuddleStage.IDLE;
                    s.stageStart = now;
                    PoseNetworking.broadcastAnimState(p1, ANIM_HUDDLE_IDLE);
                    PoseNetworking.broadcastAnimState(p2, ANIM_HUDDLE_IDLE);
                }
            }
            case IDLE -> {
                if (now - s.lastAuraTick >= 80) {
                    s.lastAuraTick = now;
                    s.auraAngle += 0.35;
                    Vec3 center = p1.position().add(p2.position()).scale(0.5);
                    for (int i = 0; i < 4; i++) {
                        double a = s.auraAngle + (Math.PI / 2 * i);
                        p1.serverLevel().sendParticles(ParticleTypes.END_ROD,
                                center.x + 1.4 * Math.cos(a), center.y + 0.05, center.z + 1.4 * Math.sin(a), 1, 0, 0, 0, 0.01);
                    }
                }
                if (s.anyReleasedF()) {
                    if (s.firstRelease == null) s.firstRelease = now;
                    boolean bothReleased = s.holdsF.isEmpty();
                    boolean graceExpired = (now - s.firstRelease) > F_RELEASE_GRACE_MS;
                    if (bothReleased) {
                        Vec3 mid = p1.position().add(p2.position()).scale(0.5).add(0, 1, 0);
                        p1.serverLevel().playSound(null, mid.x, mid.y, mid.z, ModSounds.DAP_HIT.get(), SoundSource.PLAYERS, 0.9f, 1.4f);
                        p1.serverLevel().sendParticles(ParticleTypes.CRIT, mid.x, mid.y, mid.z, 12, 0.25, 0.25, 0.25, 0.1);
                        startQTEStep(s, server, now);
                    } else if (graceExpired) {
                        failHuddle(s, server);
                    }
                } else {
                    s.firstRelease = null;
                }
            }
            case QTE -> {
                if (!s.qteAnimStarted) return;
                long animElapsed = now - s.qteAnimStart;
                if (!s.qteOpen && animElapsed >= QTE_ANIM_PRE_MS) {
                    s.qteOpen = true;
                    s.stageStart = now;
                    String btn = s.expectedButton();
                    for (ServerPlayer lp : live)
                        CoopNetwork.sendToPlayer(lp, new DapFusionHandler.FusionQTEPayload(lp.getUUID(), btn, s.qteStep, 0L, QTE_WINDOW_MS, true, 0));
                    Vec3 mid = p1.position().add(p2.position()).scale(0.5);
                    p1.serverLevel().playSound(null, mid.x, mid.y, mid.z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.9f, 1.4f + s.qteStep * 0.1f);
                }
                if (!s.qteOpen) return;
                if (s.allHit()) {
                    s.stepsHit++;
                    for (ServerPlayer lp : live) {
                        lp.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 400, s.stepsHit, false, true));
                        lp.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 400, Math.max(0, s.stepsHit - 1), false, true));
                    }
                    Vec3 flashMid = p1.position().add(p2.position()).scale(0.5).add(0, 1, 0);
                    p1.serverLevel().sendParticles(ParticleTypes.TOTEM_OF_UNDYING, flashMid.x, flashMid.y, flashMid.z, 4 + s.stepsHit * 4, 0.4, 0.4, 0.4, 0.2);
                    p1.serverLevel().playSound(null, flashMid.x, flashMid.y, flashMid.z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.0f, 1.4f + s.qteStep * 0.15f);
                    if (s.qteStep == 3) {
                        for (ServerPlayer lp : live) {
                            PoseNetworking.broadcastAnimState(lp, ANIM_HUDDLE_END);
                            CoopNetwork.sendToPlayer(lp, new DapFusionHandler.FusionQTEPayload(lp.getUUID(), "G", 0, 0L, 0L, false, 0));
                        }
                        successHuddle(s, server, live, now);
                    } else {
                        s.qteStep++;
                        s.resetQTE();
                        s.stageStart = now;
                        s.qteAnimStarted = true;
                        s.qteAnimStart = now;
                        int nextAnim = switch (s.qteStep) { case 2 -> ANIM_HUDDLE_QTE2; case 3 -> ANIM_HUDDLE_QTE3; default -> ANIM_HUDDLE_QTE1; };
                        for (ServerPlayer lp : live) PoseNetworking.broadcastAnimState(lp, nextAnim);
                    }
                    return;
                }
                if (s.elapsed() > QTE_WINDOW_MS + QTE_ANIM_PRE_MS) failHuddle(s, server);
            }
            case ENDING -> {
                if (s.elapsed() >= 1375) {
                    for (ServerPlayer lp : live) CoopNetwork.sendToPlayer(lp, new ChargedDapHandler.PerfectDapFreezePayload(false));
                    cleanupSession(s, server);
                }
            }
            case DONE -> cleanupSession(s, server);
        }
    }

    private static void startQTEStep(HuddleSession s, MinecraftServer server, long now) {
        s.qteStep = 1;
        s.stage = HuddleStage.QTE;
        s.stageStart = now;
        s.resetQTE();
        s.qteAnimStarted = true;
        s.qteAnimStart = now;
        for (UUID uid : s.players) {
            ServerPlayer lp = server.getPlayerList().getPlayer(uid);
            if (lp != null) PoseNetworking.broadcastAnimState(lp, ANIM_HUDDLE_QTE1);
        }
    }

    private static void successHuddle(HuddleSession s, MinecraftServer server, List<ServerPlayer> live, long now) {
        s.stage = HuddleStage.ENDING;
        s.stageStart = now;
        cooldowns.put(key(s.p1, s.p2), now);
        for (ServerPlayer lp : live) {
            lp.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 2));
            lp.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 400, 2));
            lp.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 400, 1));
            lp.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 200, 0));
            lp.giveExperienceLevels(2);
            lp.displayClientMessage(Component.literal("§d§l✦ HUDDLE! ✦ §7+Regen III, Speed III, Strength II, Resistance I"), true);
            CoopNetwork.sendToPlayer(lp, new HuddleEndMsg(s.p1, s.p2, true));
        }
        ServerPlayer p1 = live.get(0);
        Vec3 mid = p1.position().add(live.get(Math.min(1, live.size() - 1)).position()).scale(0.5).add(0, 1, 0);
        ServerLevel world = p1.serverLevel();
        world.sendParticles(ParticleTypes.HAPPY_VILLAGER, mid.x, mid.y, mid.z, 80, 0.8, 0.8, 0.8, 0.5);
        world.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, mid.x, mid.y, mid.z, 40, 0.6, 0.6, 0.6, 0.3);
        world.sendParticles(ParticleTypes.FIREWORK, mid.x, mid.y, mid.z, 40, 0.5, 0.6, 0.5, 0.25);
        world.sendParticles(ParticleTypes.HEART, mid.x, mid.y, mid.z, 15, 0.6, 0.4, 0.6, 0.1);
        world.playSound(null, mid.x, mid.y, mid.z, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 2.0f, 1.0f);
        world.playSound(null, mid.x, mid.y, mid.z, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.5f, 1.0f);
        ItemStack fw = new ItemStack(Items.FIREWORK_ROCKET);
        for (int ri = 0; ri < 5; ri++) {
            double ox = (RANDOM.nextDouble() - 0.5) * 1.2, oz = (RANDOM.nextDouble() - 0.5) * 1.2;
            world.addFreshEntity(new FireworkRocketEntity(world, mid.x + ox, mid.y + 0.5, mid.z + oz, fw));
        }
    }

    private static void failHuddle(HuddleSession s, MinecraftServer server) {
        cooldowns.put(key(s.p1, s.p2), System.currentTimeMillis());
        if (server == null) { cleanupSession(s, server); return; }
        List<ServerPlayer> live = new ArrayList<>();
        for (UUID uid : s.players) {
            ServerPlayer lp = server.getPlayerList().getPlayer(uid);
            if (lp != null) live.add(lp);
        }
        for (ServerPlayer lp : live) {
            CoopNetwork.sendToPlayer(lp, new DapFusionHandler.FusionQTEPayload(lp.getUUID(), "G", 0, 0L, 0L, false, 0));
            PoseNetworking.broadcastAnimState(lp, ANIM_NONE);
            lp.removeEffect(MobEffects.MOVEMENT_SPEED);
            lp.removeEffect(MobEffects.DAMAGE_BOOST);
            lp.displayClientMessage(Component.literal("§c✗ Huddle failed!"), true);
            CoopNetwork.sendToPlayer(lp, new HuddleEndMsg(s.p1, s.p2, false));
        }
        if (live.size() >= 2) {
            ArmorStand stand = centerStands.get(key(s.p1, s.p2));
            Vec3 center = stand != null ? stand.position() : live.get(0).position().add(live.get(1).position()).scale(0.5);
            for (ServerPlayer lp : live) {
                Vec3 dir = lp.position().subtract(center).normalize();
                lp.setDeltaMovement(lp.getDeltaMovement().add(dir.x * 0.6, 0.4, dir.z * 0.6));
                lp.hurtMarked = true;
            }
            live.get(0).serverLevel().playSound(null, center.x, center.y, center.z, SoundEvents.ZOMBIE_INFECT, SoundSource.PLAYERS, 0.7f, 1.5f);
        }
        cleanupSession(s, server);
    }

    private static void cleanupSession(HuddleSession s, MinecraftServer server) {
        String k = key(s.p1, s.p2);
        sessions.remove(k);
        for (UUID uid : s.players) {
            playerSession.remove(uid);
            if (server != null) {
                ServerPlayer lp = server.getPlayerList().getPlayer(uid);
                if (lp != null) CoopNetwork.sendToPlayer(lp, new ChargedDapHandler.PerfectDapFreezePayload(false));
            }
        }
        ArmorStand stand = centerStands.remove(k);
        if (stand != null && !stand.isRemoved()) stand.discard();
    }

    public static boolean isInHuddle(UUID id) { return playerSession.containsKey(id); }

    public static void cleanup(UUID id) {
        fHoldStart.remove(id);
        String k = playerSession.remove(id);
        if (k != null) {
            HuddleSession s = sessions.remove(k);
            if (s != null) playerSession.remove(s.p1.equals(id) ? s.p2 : s.p1);
        }
    }
}
