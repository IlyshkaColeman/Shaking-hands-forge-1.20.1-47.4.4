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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static java.util.Collections.emptySet;

/**
 * Dap Hold — hold J after a high-five/dap to enter a dapping loop; others can join
 * for a synced group release. Ported from Fabric to Forge 1.20.1.
 *
 * Started by ChargedDapHandler / HighFive interplay (tryDetect). Self-contained
 * apart from HighFiveHandler / DapSessionManager / ChargedDapHandler.cooldowns.
 * Anim ordinals are raw ints (DAPHOLD_HIGHFIVE=38 .. DAPHOLD_DAPPING_END=41,
 * END_GROUP=68). CustomPayload -> CoopNetwork messages; ServerTickEvents ->
 * CoopServerTick.
 */
public final class DapHoldHandler {

    private DapHoldHandler() {}

    private static final long ANIM_LENGTH_MS = 1042;
    private static final long J_WINDOW_START_MS = 330;
    private static final long IMPACT_MS = 420;
    private static final long J_WINDOW_END_MS = 1330;
    private static final double STOP_DISTANCE = 1.5;
    private static final double TP_SPEED = 0.08;

    private static final Map<UUID, UUID> activePairs = new HashMap<>();
    private static final Map<UUID, Long> pairStartTime = new HashMap<>();
    private static final Set<UUID> windowOpen = new HashSet<>();
    private static final Set<UUID> impactFired = new HashSet<>();
    private static final Set<UUID> looping = new HashSet<>();
    private static final Set<UUID> endingAnimation = new HashSet<>();
    private static final Map<UUID, Long> jHoldLastTick = new HashMap<>();
    private static final Map<UUID, Long> loopStartTime = new HashMap<>();
    private static final Map<UUID, ArmorStand> handStands = new HashMap<>();
    private static final Set<UUID> tpComplete = new HashSet<>();
    private static final Map<UUID, Set<UUID>> groupJoiners = new HashMap<>();
    private static final Map<UUID, UUID> joinerGroup = new HashMap<>();
    private static final Map<UUID, Long> joinerJLast = new HashMap<>();
    private static final Map<UUID, Long> releaseFirst = new HashMap<>();
    private static final Map<UUID, Set<UUID>> releasedSet = new HashMap<>();
    private static final double GROUP_JOIN_RADIUS = 2.5;
    private static final long RELEASE_WINDOW_MS = 500L;

    public static void register() { }

    // ------------------------------------------------------------------ facing helpers

    private static boolean arePlayersFacingEachOther(ServerPlayer p1, ServerPlayer p2) {
        Vec3 p1Pos = p1.position();
        Vec3 p2Pos = p2.position();
        Vec3 directionTo = p2Pos.subtract(p1Pos).normalize();
        Vec3 p1Looking = p1.getViewVector(1.0f);
        if (p1Looking.dot(directionTo) < 0.85) return false;
        Vec3 directionBack = p1Pos.subtract(p2Pos).normalize();
        Vec3 p2Looking = p2.getViewVector(1.0f);
        return p2Looking.dot(directionBack) >= 0.85;
    }

    public static void startDapHold(ServerPlayer hfPlayer, ServerPlayer dapPlayer) {
        UUID hfId = hfPlayer.getUUID();
        UUID dapId = dapPlayer.getUUID();
        if (isInDapHold(hfId) || isInDapHold(dapId)) return;
        if (!arePlayersFacingEachOther(hfPlayer, dapPlayer)) {
            hfPlayer.displayClientMessage(Component.literal("§cNot facing each other!"), true);
            dapPlayer.displayClientMessage(Component.literal("§cNot facing each other!"), true);
            return;
        }
        HighFiveHandler.handRaisedTime.remove(hfId);
        HighFiveHandler.startAnimTime.remove(hfId);
        HighFiveHandler.syncHandRaised(hfPlayer, false);
        DapSessionManager.createSession(hfId, dapId, 1.5, DapSession.DapType.PERFECT_DAP);
        activePairs.put(hfId, dapId);
        pairStartTime.put(hfId, System.currentTimeMillis());
        MinecraftServer server = hfPlayer.getServer();
        sendFreeze(server, hfId, true);
        sendFreeze(server, dapId, true);
        spawnHandStand(hfPlayer, dapPlayer);
        sendToAll(server, new DapHoldStartMsg(hfId, dapId, 0));
        sendToAll(server, new DapHoldStartMsg(dapId, hfId, 1));
        PoseNetworking.broadcastAnimState(hfPlayer, 38);
        PoseNetworking.broadcastAnimState(dapPlayer, 39);
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        Set<UUID> toCleanup = new HashSet<>();
        for (Map.Entry<UUID, UUID> entry : new HashMap<>(activePairs).entrySet()) {
            UUID hfId = entry.getKey();
            UUID dapId = entry.getValue();
            ServerPlayer hfPlayer = server.getPlayerList().getPlayer(hfId);
            ServerPlayer dapPlayer = server.getPlayerList().getPlayer(dapId);
            if (hfPlayer == null || dapPlayer == null) { toCleanup.add(hfId); continue; }
            Long startMs = pairStartTime.get(hfId);
            if (startMs == null) { toCleanup.add(hfId); continue; }
            long elapsed = now - startMs;
            tpComplete.add(hfId);
            updateHandStand(hfPlayer, dapPlayer, hfId);
            if (elapsed % 500 < 50) {
                hfPlayer.swing(InteractionHand.MAIN_HAND);
                dapPlayer.swing(InteractionHand.MAIN_HAND);
            }
            if (!windowOpen.contains(hfId) && elapsed >= J_WINDOW_START_MS) {
                windowOpen.add(hfId);
                sendToAll(server, new DapHoldWindowMsg(true));
                hfPlayer.displayClientMessage(Component.literal("§e⚡ HOLD J "), true);
                dapPlayer.displayClientMessage(Component.literal("§e⚡ HOLD J "), true);
            }
            if (!impactFired.contains(hfId) && elapsed >= IMPACT_MS) {
                impactFired.add(hfId);
                spawnImpactParticles(hfPlayer, dapPlayer, hfId);
            }
            if (windowOpen.contains(hfId) && !looping.contains(hfId)
                    && !endingAnimation.contains(hfId) && elapsed >= J_WINDOW_END_MS) {
                if (isHoldingJ(hfId, now) && isHoldingJ(dapId, now)) {
                    looping.add(hfId);
                    loopStartTime.put(hfId, now);
                    DapSessionManager.removeSession(hfId);
                    sendToAll(server, new DapHoldLoopMsg(true));
                    PoseNetworking.broadcastAnimState(hfPlayer, 40);
                    PoseNetworking.broadcastAnimState(dapPlayer, 40);
                } else {
                    endingAnimation.add(hfId);
                    sendToAll(server, new DapHoldWindowMsg(false));
                    doUnfreeze(server, hfId, dapId);
                }
            }
            if (endingAnimation.contains(hfId) && elapsed >= ANIM_LENGTH_MS) {
                sendToAll(server, new DapHoldEndMsg(false));
                toCleanup.add(hfId);
            }
        }
        Set<UUID> groupResultNeeded = new HashSet<>();
        for (UUID hfId : new HashSet<>(looping)) {
            ServerPlayer hfPlayer = server.getPlayerList().getPlayer(hfId);
            UUID dapId = activePairs.get(hfId);
            ServerPlayer dapPlayer = dapId != null ? server.getPlayerList().getPlayer(dapId) : null;
            if (hfPlayer == null || dapPlayer == null) continue;
            ServerLevel world = hfPlayer.serverLevel();
            ArmorStand stand = handStands.get(hfId);
            if (stand != null && !stand.isRemoved()) {
                Vec3 impactPos = stand.position();
                world.sendParticles(ParticleTypes.CRIT, impactPos.x, impactPos.y, impactPos.z, 2, 0.1, 0.1, 0.1, 0.02);
            }
            Set<UUID> joiners = groupJoiners.get(hfId);
            if (joiners != null && !joiners.isEmpty()) {
                Long first = releaseFirst.get(hfId);
                if (first != null && now - first > RELEASE_WINDOW_MS) { groupResultNeeded.add(hfId); continue; }
                Set<UUID> toEvict = new HashSet<>();
                for (UUID jId : joiners) {
                    Long lastJ = joinerJLast.get(jId);
                    if (lastJ == null || now - lastJ > 300) toEvict.add(jId);
                }
                for (UUID jId : toEvict) {
                    joiners.remove(jId);
                    joinerGroup.remove(jId);
                    joinerJLast.remove(jId);
                    sendFreeze(server, jId, false);
                    ServerPlayer jp = server.getPlayerList().getPlayer(jId);
                    if (jp != null) {
                        PoseNetworking.broadcastAnimState(jp, 41);
                        jp.displayClientMessage(Component.literal("§7Left the group"), true);
                    }
                }
                if (server.getTickCount() % 4 == 0) {
                    faceGroupCenter(hfId, server);
                    ServerPlayer hfP2 = server.getPlayerList().getPlayer(hfId);
                    UUID dapId2 = activePairs.get(hfId);
                    ServerPlayer dapP2 = dapId2 != null ? server.getPlayerList().getPlayer(dapId2) : null;
                    if (hfP2 != null) hfP2.setYHeadRot(hfP2.yBodyRot);
                    if (dapP2 != null) dapP2.setYHeadRot(dapP2.yBodyRot);
                    for (UUID jId : joiners) {
                        ServerPlayer jp = server.getPlayerList().getPlayer(jId);
                        if (jp != null) jp.setYHeadRot(jp.yBodyRot);
                    }
                }
                Vec3 mid = getGroupMidpoint(hfId, server);
                int chargeParticles = joiners.size() + 1;
                world.sendParticles(ParticleTypes.ENCHANTED_HIT, mid.x, mid.y + 1.2, mid.z, chargeParticles, 0.3, 0.2, 0.3, 0.05);
            }
        }
        for (UUID hfId : groupResultNeeded) {
            if (looping.contains(hfId)) doGroupResult(hfId, server, false);
        }
        toCleanup.forEach(hfId -> cleanupPair(hfId, server));
    }

    // ------------------------------------------------------------------ J hold / release

    private static void onJHold(ServerPlayer player) {
        UUID id = player.getUUID();
        long now = System.currentTimeMillis();
        UUID hfId = getPairHfId(id);
        if (hfId != null && windowOpen.contains(hfId)) { jHoldLastTick.put(id, now); return; }
        if (joinerGroup.containsKey(id)) { joinerJLast.put(id, now); return; }
        if (hfId == null) tryJoinGroup(player, now);
    }

    private static void onJRelease(ServerPlayer player) {
        UUID id = player.getUUID();
        jHoldLastTick.remove(id);
        joinerJLast.remove(id);
        UUID joinerHfId = joinerGroup.get(id);
        if (joinerHfId != null) { logGroupRelease(id, joinerHfId, player.getServer()); return; }
        UUID hfId = getPairHfId(id);
        if (hfId == null || !looping.contains(hfId)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (groupJoiners.containsKey(hfId) && !groupJoiners.get(hfId).isEmpty()) { logGroupRelease(id, hfId, server); return; }
        UUID dapId = activePairs.get(hfId);
        looping.remove(hfId);
        loopStartTime.remove(hfId);
        doUnfreeze(server, hfId, dapId);
        sendToAll(server, new DapHoldEndMsg(true));
        ServerPlayer hfP = server.getPlayerList().getPlayer(hfId);
        ServerPlayer dapP = dapId != null ? server.getPlayerList().getPlayer(dapId) : null;
        if (hfP != null) PoseNetworking.broadcastAnimState(hfP, 41);
        if (dapP != null) PoseNetworking.broadcastAnimState(dapP, 41);
        pairStartTime.put(hfId, System.currentTimeMillis() + 100 - 1042L);
    }

    private static void tryJoinGroup(ServerPlayer player, long now) {
        UUID id = player.getUUID();
        for (UUID hfId : looping) {
            UUID dapId = activePairs.get(hfId);
            if (hfId.equals(id) || (dapId != null && dapId.equals(id))) continue;
            Vec3 mid = getGroupMidpoint(hfId, player.getServer());
            if (player.position().distanceTo(mid) > GROUP_JOIN_RADIUS) continue;
            addGroupJoiner(player, hfId);
            return;
        }
    }

    private static void addGroupJoiner(ServerPlayer joiner, UUID hfId) {
        UUID id = joiner.getUUID();
        MinecraftServer server = joiner.getServer();
        groupJoiners.computeIfAbsent(hfId, k -> new HashSet<>()).add(id);
        joinerGroup.put(id, hfId);
        joinerJLast.put(id, System.currentTimeMillis());
        sendFreeze(server, id, true);
        PoseNetworking.broadcastAnimState(joiner, 38);
        int total = 2 + groupJoiners.get(hfId).size();
        sendToAll(server, new GroupJoinedMsg(id, hfId, total));
        faceGroupCenter(hfId, server);
        joiner.displayClientMessage(Component.literal("§a§l⚡ JOINED GROUP DAP! (" + total + " players)"), true);
        ServerPlayer hfP = server.getPlayerList().getPlayer(hfId);
        if (hfP != null) hfP.displayClientMessage(Component.literal("§e§l+" + joiner.getName().getString() + " joined! (" + total + " total)"), true);
    }

    private static void logGroupRelease(UUID id, UUID hfId, MinecraftServer server) {
        if (server == null) return;
        releasedSet.computeIfAbsent(hfId, k -> new HashSet<>()).add(id);
        releaseFirst.putIfAbsent(hfId, System.currentTimeMillis());
        checkGroupRelease(hfId, server);
    }

    private static void checkGroupRelease(UUID hfId, MinecraftServer server) {
        Set<UUID> joiners = groupJoiners.getOrDefault(hfId, emptySet());
        int total = 2 + joiners.size();
        int released = releasedSet.getOrDefault(hfId, emptySet()).size();
        long elapsed = System.currentTimeMillis() - releaseFirst.getOrDefault(hfId, Long.MAX_VALUE);
        if (released >= total) doGroupResult(hfId, server, elapsed <= RELEASE_WINDOW_MS);
    }

    private static void doGroupResult(UUID hfId, MinecraftServer server, boolean perfect) {
        UUID dapId = activePairs.get(hfId);
        Set<UUID> joiners = new HashSet<>(groupJoiners.getOrDefault(hfId, emptySet()));
        int memberCount = 2 + joiners.size();
        List<ServerPlayer> all = new ArrayList<>();
        ServerPlayer hfP = server.getPlayerList().getPlayer(hfId);
        ServerPlayer dapP = dapId != null ? server.getPlayerList().getPlayer(dapId) : null;
        if (hfP != null) all.add(hfP);
        if (dapP != null) all.add(dapP);
        for (UUID jId : joiners) {
            ServerPlayer jp = server.getPlayerList().getPlayer(jId);
            if (jp != null) all.add(jp);
        }
        Vec3 center = all.stream().map(ServerPlayer::position).reduce(Vec3.ZERO, Vec3::add).scale(1.0 / Math.max(1, all.size()));
        ServerLevel world = hfP != null ? hfP.serverLevel() : server.overworld();
        if (perfect) {
            for (ServerPlayer p : all) PoseNetworking.broadcastAnimState(p, 68);
            for (ServerPlayer p : all) sendFreeze(server, p.getUUID(), false);
            final List<ServerPlayer> allFinal = all;
            final Vec3 centerFinal = center;
            final ServerLevel worldFinal = world;
            final int mc = memberCount;
            ServerTaskScheduler.scheduleMillis(server, 1670, () -> {
                    for (ServerPlayer p : allFinal) {
                        if (!p.isAlive()) continue;
                        p.setDeltaMovement(p.getDeltaMovement().add(0, 0.4 + mc * 0.1, 0));
                        p.hurtMarked = true;
                        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 120, Math.min(2, mc - 1)));
                        p.addEffect(new MobEffectInstance(MobEffects.JUMP, 120, 0));
                        p.displayClientMessage(Component.literal("§6§l✨ PERFECT GROUP DAP! §e" + mc + " players!"), true);
                    }
                    for (int i = 0; i < mc * 3; i++) {
                        double ox = (worldFinal.getRandom().nextDouble() - 0.5) * 3;
                        double oz = (worldFinal.getRandom().nextDouble() - 0.5) * 3;
                        worldFinal.sendParticles(ParticleTypes.FIREWORK, centerFinal.x + ox, centerFinal.y + 2 + i * 0.5, centerFinal.z + oz, 6, 0.3, 0.1, 0.3, 0.12);
                    }
                    worldFinal.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, centerFinal.x, centerFinal.y + 1.5, centerFinal.z, mc * 5, 0.6, 0.6, 0.6, 0.3);
                    worldFinal.sendParticles(ParticleTypes.EXPLOSION_EMITTER, centerFinal.x, centerFinal.y + 1, centerFinal.z, mc, 0.4, 0.3, 0.4, 0);
                    worldFinal.playSound(null, centerFinal.x, centerFinal.y, centerFinal.z, ModSounds.EPIC_DAP.get(), SoundSource.PLAYERS, 1.5f, 0.9f + mc * 0.05f);
                    worldFinal.playSound(null, centerFinal.x, centerFinal.y, centerFinal.z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.PLAYERS, 1.2f, 0.8f);
            });
        } else {
            for (ServerPlayer p : all) {
                Vec3 dir = p.position().subtract(center).normalize();
                if (dir.lengthSqr() < 0.01) dir = new Vec3(1, 0, 0);
                p.setDeltaMovement(p.getDeltaMovement().add(dir.x * 0.9, 0.3, dir.z * 0.9));
                p.hurtMarked = true;
                p.displayClientMessage(Component.literal("§c❌ Release not synced!"), true);
            }
            world.sendParticles(ParticleTypes.POOF, center.x, center.y + 1, center.z, 12, 0.4, 0.3, 0.4, 0.05);
            world.playSound(null, center.x, center.y, center.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.6f, 0.8f);
        }
        sendToAll(server, new GroupResultMsg(perfect, memberCount));
        if (!perfect) {
            sendToAll(server, new DapHoldEndMsg(false));
            if (hfP != null) PoseNetworking.broadcastAnimState(hfP, 41);
            if (dapP != null) PoseNetworking.broadcastAnimState(dapP, 41);
            for (UUID jId : joiners) {
                ServerPlayer jp = server.getPlayerList().getPlayer(jId);
                if (jp != null) PoseNetworking.broadcastAnimState(jp, 41);
            }
        }
        for (UUID jId : joiners) {
            sendFreeze(server, jId, false);
            joinerGroup.remove(jId);
            joinerJLast.remove(jId);
        }
        groupJoiners.remove(hfId);
        releaseFirst.remove(hfId);
        releasedSet.remove(hfId);
        looping.remove(hfId);
        loopStartTime.remove(hfId);
        if (dapId != null) doUnfreeze(server, hfId, dapId);
        pairStartTime.put(hfId, System.currentTimeMillis() + 100 - 1042L);
    }

    private static Vec3 getGroupMidpoint(UUID hfId, MinecraftServer server) {
        List<Vec3> positions = new ArrayList<>();
        ServerPlayer hfP = server.getPlayerList().getPlayer(hfId);
        UUID dapId = activePairs.get(hfId);
        ServerPlayer dapP = dapId != null ? server.getPlayerList().getPlayer(dapId) : null;
        if (hfP != null) positions.add(hfP.position());
        if (dapP != null) positions.add(dapP.position());
        for (UUID jId : groupJoiners.getOrDefault(hfId, emptySet())) {
            ServerPlayer jp = server.getPlayerList().getPlayer(jId);
            if (jp != null) positions.add(jp.position());
        }
        if (positions.isEmpty()) return Vec3.ZERO;
        return positions.stream().reduce(Vec3.ZERO, Vec3::add).scale(1.0 / positions.size());
    }

    private static void faceGroupCenter(UUID hfId, MinecraftServer server) {
        List<ServerPlayer> members = new ArrayList<>();
        ServerPlayer hfP = server.getPlayerList().getPlayer(hfId);
        UUID dapId = activePairs.get(hfId);
        ServerPlayer dapP = dapId != null ? server.getPlayerList().getPlayer(dapId) : null;
        if (hfP != null) members.add(hfP);
        if (dapP != null) members.add(dapP);
        for (UUID jId : groupJoiners.getOrDefault(hfId, emptySet())) {
            ServerPlayer jp = server.getPlayerList().getPlayer(jId);
            if (jp != null) members.add(jp);
        }
        if (members.size() < 2) return;
        Vec3 center = members.stream().map(ServerPlayer::position).reduce(Vec3.ZERO, Vec3::add).scale(1.0 / members.size());
        for (ServerPlayer p : members) {
            Vec3 diff = center.subtract(p.position());
            if (diff.horizontalDistanceSqr() < 0.001) continue;
            float yaw = (float) (Math.toDegrees(Math.atan2(diff.z, diff.x))) - 90f;
            p.setYRot(yaw); p.setYBodyRot(yaw); p.setYHeadRot(yaw);
        }
    }

    public static void forceUnfreeze(MinecraftServer server, UUID id) { sendFreeze(server, id, false); }

    public static UUID getPairHfId(UUID id) {
        if (activePairs.containsKey(id)) return id;
        for (Map.Entry<UUID, UUID> e : activePairs.entrySet())
            if (e.getValue().equals(id)) return e.getKey();
        return null;
    }

    private static boolean isHoldingJ(UUID id, long now) {
        Long last = jHoldLastTick.get(id);
        return last != null && (now - last) < 200;
    }

    private static void spawnHandStand(ServerPlayer hf, ServerPlayer dap) {
        ServerLevel world = hf.serverLevel();
        Vec3 mid = hf.position().add(0, 1.4, 0).add(dap.position().add(0, 1.4, 0)).scale(0.5);
        ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, world);
        stand.setPos(mid.x, mid.y, mid.z);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        world.addFreshEntity(stand);
        handStands.put(hf.getUUID(), stand);
    }

    private static void updateHandStand(ServerPlayer hf, ServerPlayer dap, UUID hfId) {
        ArmorStand stand = handStands.get(hfId);
        if (stand == null || stand.isRemoved()) return;
        Vec3 mid = hf.position().add(0, 1.4, 0).add(dap.position().add(0, 1.4, 0)).scale(0.5);
        stand.setPos(mid.x, mid.y, mid.z);
    }

    private static void spawnImpactParticles(ServerPlayer hf, ServerPlayer dap, UUID hfId) {
        ServerLevel world = hf.serverLevel();
        ArmorStand stand = handStands.get(hfId);
        double x, y, z;
        if (stand != null && !stand.isRemoved()) {
            x = stand.getX(); y = stand.getY(); z = stand.getZ();
        } else {
            Vec3 mid = hf.position().add(dap.position()).scale(0.5).add(0, 1.4, 0);
            x = mid.x; y = mid.y; z = mid.z;
        }
        world.sendParticles(ParticleTypes.FLASH, x, y, z, 3, 0, 0, 0, 0);
        world.sendParticles(ParticleTypes.END_ROD, x, y, z, 40, 0.4, 0.4, 0.4, 0.15);
        world.sendParticles(ParticleTypes.WHITE_ASH, x, y, z, 80, 0.6, 0.6, 0.6, 0.08);
        world.sendParticles(ParticleTypes.CLOUD, x, y, z, 20, 0.3, 0.3, 0.3, 0.05);
        world.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 5, 0.3, 0.3, 0.3, 0);
        double groundY = hf.getY() + 0.1;
        for (double angle = 0; angle < 360; angle += 8) {
            double rad = Math.toRadians(angle);
            for (double r = 0.5; r <= 3.0; r += 0.5) {
                world.sendParticles(ParticleTypes.END_ROD, x + Math.cos(rad) * r, groundY, z + Math.sin(rad) * r, 2, 0.05, 0.05, 0.05, 0.02);
            }
        }
        world.playSound(null, x, y, z, ModSounds.DAP_WEAK.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
    }

    private static void sendFreeze(MinecraftServer server, UUID targetId, boolean freeze) {
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            CoopNetwork.sendToPlayer(p, new DapHoldFreezeMsg(targetId, freeze));
    }

    private static void doUnfreeze(MinecraftServer server, UUID hfId, UUID dapId) {
        sendFreeze(server, hfId, false);
        if (dapId != null) sendFreeze(server, dapId, false);
        DapSessionManager.removeSession(hfId);
        ArmorStand stand = handStands.remove(hfId);
        if (stand != null && !stand.isRemoved()) stand.discard();
    }

    private static void sendToAll(MinecraftServer server, Object payload) {
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            CoopNetwork.sendToPlayer(p, payload);
    }

    private static void cleanupPair(UUID hfId, MinecraftServer server) {
        UUID dapId = activePairs.remove(hfId);
        pairStartTime.remove(hfId); windowOpen.remove(hfId); impactFired.remove(hfId);
        looping.remove(hfId); endingAnimation.remove(hfId); tpComplete.remove(hfId);
        loopStartTime.remove(hfId);
        jHoldLastTick.remove(hfId);
        if (dapId != null) jHoldLastTick.remove(dapId);
        ArmorStand stand = handStands.remove(hfId);
        if (stand != null && !stand.isRemoved()) stand.discard();
        Set<UUID> joiners = groupJoiners.remove(hfId);
        if (joiners != null) {
            for (UUID jId : joiners) {
                joinerGroup.remove(jId);
                joinerJLast.remove(jId);
                sendFreeze(server, jId, false);
                ServerPlayer jp = server.getPlayerList().getPlayer(jId);
                if (jp != null) PoseNetworking.broadcastAnimState(jp, 41);
            }
        }
        releaseFirst.remove(hfId);
        releasedSet.remove(hfId);
        DapSessionManager.removeSession(hfId);
        sendFreeze(server, hfId, false);
        if (dapId != null) sendFreeze(server, dapId, false);
        long now = System.currentTimeMillis();
        ChargedDapHandler.cooldowns.put(hfId, now + 1000);
        if (dapId != null) ChargedDapHandler.cooldowns.put(dapId, now + 1000);
        HighFiveHandler.highFiveCooldown.put(hfId, now);
        if (dapId != null) HighFiveHandler.highFiveCooldown.put(dapId, now);
    }

    public static boolean tryDetect(ServerPlayer player, ServerPlayer partner) {
        boolean playerHF = HighFiveHandler.hasHandRaised(player.getUUID());
        boolean partnerHF = HighFiveHandler.hasHandRaised(partner.getUUID());
        if (playerHF && !partnerHF) { startDapHold(player, partner); return true; }
        if (partnerHF && !playerHF) { startDapHold(partner, player); return true; }
        return false;
    }

    public static boolean isInDapHold(UUID playerId) {
        return activePairs.containsKey(playerId) || activePairs.containsValue(playerId) || joinerGroup.containsKey(playerId);
    }

    /** Clears a pair or group membership when a player dies, respawns or disconnects. */
    public static void cleanup(UUID playerId, MinecraftServer server) {
        UUID hfId = getPairHfId(playerId);
        if (hfId != null) {
            cleanupPair(hfId, server);
            return;
        }
        UUID groupId = joinerGroup.remove(playerId);
        joinerJLast.remove(playerId);
        if (groupId != null) {
            Set<UUID> members = groupJoiners.get(groupId);
            if (members != null) {
                members.remove(playerId);
                if (members.isEmpty()) groupJoiners.remove(groupId);
            }
        }
        sendFreeze(server, playerId, false);
    }

    // ------------------------------------------------------------------ networking

    public record DapHoldStartMsg(UUID playerId, UUID partnerId, int role) {
        public static void encode(DapHoldStartMsg m, FriendlyByteBuf b) { b.writeUUID(m.playerId); b.writeUUID(m.partnerId); b.writeInt(m.role); }
        public static DapHoldStartMsg decode(FriendlyByteBuf b) { return new DapHoldStartMsg(b.readUUID(), b.readUUID(), b.readInt()); }
        public static void handle(DapHoldStartMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { if (!c.getDirection().getReceptionSide().isServer())
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.cooptest.client.DapHoldClientHandler.onStart(m.playerId(), m.partnerId(), m.role())); });
            c.setPacketHandled(true);
        }
    }

    public record DapHoldWindowMsg(boolean open) {
        public static void encode(DapHoldWindowMsg m, FriendlyByteBuf b) { b.writeBoolean(m.open); }
        public static DapHoldWindowMsg decode(FriendlyByteBuf b) { return new DapHoldWindowMsg(b.readBoolean()); }
        public static void handle(DapHoldWindowMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { if (!c.getDirection().getReceptionSide().isServer())
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.cooptest.client.DapHoldClientHandler.onWindow(m.open())); });
            c.setPacketHandled(true);
        }
    }

    public record DapHoldLoopMsg(boolean looping) {
        public static void encode(DapHoldLoopMsg m, FriendlyByteBuf b) { b.writeBoolean(m.looping); }
        public static DapHoldLoopMsg decode(FriendlyByteBuf b) { return new DapHoldLoopMsg(b.readBoolean()); }
        public static void handle(DapHoldLoopMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { if (!c.getDirection().getReceptionSide().isServer())
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.cooptest.client.DapHoldClientHandler.onLoop(m.looping())); });
            c.setPacketHandled(true);
        }
    }

    public record DapHoldEndMsg(boolean wasLooping) {
        public static void encode(DapHoldEndMsg m, FriendlyByteBuf b) { b.writeBoolean(m.wasLooping); }
        public static DapHoldEndMsg decode(FriendlyByteBuf b) { return new DapHoldEndMsg(b.readBoolean()); }
        public static void handle(DapHoldEndMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { if (!c.getDirection().getReceptionSide().isServer())
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.cooptest.client.DapHoldClientHandler.onEnd(m.wasLooping())); });
            c.setPacketHandled(true);
        }
    }

    public record DapHoldFreezeMsg(UUID playerId, boolean frozen) {
        public static void encode(DapHoldFreezeMsg m, FriendlyByteBuf b) { b.writeUUID(m.playerId); b.writeBoolean(m.frozen); }
        public static DapHoldFreezeMsg decode(FriendlyByteBuf b) { return new DapHoldFreezeMsg(b.readUUID(), b.readBoolean()); }
        public static void handle(DapHoldFreezeMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { if (!c.getDirection().getReceptionSide().isServer())
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.cooptest.client.DapHoldClientHandler.onFreeze(m.playerId(), m.frozen())); });
            c.setPacketHandled(true);
        }
    }

    public record GroupJoinedMsg(UUID joinerId, UUID hfId, int memberCount) {
        public static void encode(GroupJoinedMsg m, FriendlyByteBuf b) { b.writeUUID(m.joinerId); b.writeUUID(m.hfId); b.writeInt(m.memberCount); }
        public static GroupJoinedMsg decode(FriendlyByteBuf b) { return new GroupJoinedMsg(b.readUUID(), b.readUUID(), b.readInt()); }
        public static void handle(GroupJoinedMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { if (!c.getDirection().getReceptionSide().isServer())
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.cooptest.client.DapHoldClientHandler.onGroupJoined(m.joinerId(), m.hfId(), m.memberCount())); });
            c.setPacketHandled(true);
        }
    }

    public record GroupResultMsg(boolean perfect, int memberCount) {
        public static void encode(GroupResultMsg m, FriendlyByteBuf b) { b.writeBoolean(m.perfect); b.writeInt(m.memberCount); }
        public static GroupResultMsg decode(FriendlyByteBuf b) { return new GroupResultMsg(b.readBoolean(), b.readInt()); }
        public static void handle(GroupResultMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { if (!c.getDirection().getReceptionSide().isServer())
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.cooptest.client.DapHoldClientHandler.onGroupResult(m.perfect(), m.memberCount())); });
            c.setPacketHandled(true);
        }
    }

    public record DapHoldJHoldMsg() {
        public static void encode(DapHoldJHoldMsg m, FriendlyByteBuf b) { }
        public static DapHoldJHoldMsg decode(FriendlyByteBuf b) { return new DapHoldJHoldMsg(); }
        public static void handle(DapHoldJHoldMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { ServerPlayer p = c.getSender(); if (p != null) onJHold(p); });
            c.setPacketHandled(true);
        }
    }

    public record DapHoldJReleaseMsg() {
        public static void encode(DapHoldJReleaseMsg m, FriendlyByteBuf b) { }
        public static DapHoldJReleaseMsg decode(FriendlyByteBuf b) { return new DapHoldJReleaseMsg(); }
        public static void handle(DapHoldJReleaseMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { ServerPlayer p = c.getSender(); if (p != null) onJRelease(p); });
            c.setPacketHandled(true);
        }
    }

    public record GroupJoinMsg() {
        public static void encode(GroupJoinMsg m, FriendlyByteBuf b) { }
        public static GroupJoinMsg decode(FriendlyByteBuf b) { return new GroupJoinMsg(); }
        public static void handle(GroupJoinMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer p = c.getSender();
                if (p == null) return;
                if (isInDapHold(p.getUUID())) return;
                tryJoinGroup(p, System.currentTimeMillis());
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(DapHoldStartMsg.class, DapHoldStartMsg::encode, DapHoldStartMsg::decode, DapHoldStartMsg::handle);
        CoopNetwork.register(DapHoldWindowMsg.class, DapHoldWindowMsg::encode, DapHoldWindowMsg::decode, DapHoldWindowMsg::handle);
        CoopNetwork.register(DapHoldLoopMsg.class, DapHoldLoopMsg::encode, DapHoldLoopMsg::decode, DapHoldLoopMsg::handle);
        CoopNetwork.register(DapHoldEndMsg.class, DapHoldEndMsg::encode, DapHoldEndMsg::decode, DapHoldEndMsg::handle);
        CoopNetwork.register(DapHoldFreezeMsg.class, DapHoldFreezeMsg::encode, DapHoldFreezeMsg::decode, DapHoldFreezeMsg::handle);
        CoopNetwork.register(GroupJoinedMsg.class, GroupJoinedMsg::encode, GroupJoinedMsg::decode, GroupJoinedMsg::handle);
        CoopNetwork.register(GroupResultMsg.class, GroupResultMsg::encode, GroupResultMsg::decode, GroupResultMsg::handle);
        CoopNetwork.register(DapHoldJHoldMsg.class, DapHoldJHoldMsg::encode, DapHoldJHoldMsg::decode, DapHoldJHoldMsg::handle);
        CoopNetwork.register(DapHoldJReleaseMsg.class, DapHoldJReleaseMsg::encode, DapHoldJReleaseMsg::decode, DapHoldJReleaseMsg::handle);
        CoopNetwork.register(GroupJoinMsg.class, GroupJoinMsg::encode, GroupJoinMsg::decode, GroupJoinMsg::handle);
    }

    public static void sendJHold() { CoopNetwork.sendToServer(new DapHoldJHoldMsg()); }
    public static void sendJRelease() { CoopNetwork.sendToServer(new DapHoldJReleaseMsg()); }
    public static void sendGroupJoin() { CoopNetwork.sendToServer(new GroupJoinMsg()); }
}
