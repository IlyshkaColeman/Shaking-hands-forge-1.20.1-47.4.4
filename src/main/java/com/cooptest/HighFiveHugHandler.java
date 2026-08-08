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
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.*;
import java.util.function.Supplier;

/**
 * Hold-to-hug: after a high-five, a 3s window opens; if both players hold the hug key
 * (F) for 800ms while close, they hug (approach, hearts, regen). Ported from Fabric to
 * Forge 1.20.1. Anim ordinals hardcoded (HUG_START=32, HUGGING=33, HUGGING2=34,
 * HUG_END=35).
 */
public final class HighFiveHugHandler {

    private HighFiveHugHandler() {}

    private static final double HUG_DISTANCE = 4.0;
    private static final long HUG_HOLD_TIME_MS = 800;
    private static final long HUG_OPPORTUNITY_MS = 3000;

    private static final int ANIM_NONE = 0;
    private static final int ANIM_HUG_START = 32;
    private static final int ANIM_HUGGING = 33;
    private static final int ANIM_HUGGING2 = 34;
    private static final int ANIM_HUG_END = 35;

    private static final Map<UUID, Long> hugHoldStart = new HashMap<>();
    private static final Map<UUID, UUID> hugPartner = new HashMap<>();
    private static final Map<UUID, Long> lastHUpdate = new HashMap<>();
    private static final Map<UUID, HugState> hugState = new HashMap<>();
    private static final Map<UUID, Long> hugStartTime = new HashMap<>();

    private enum HugState { NONE, START, HUGGING, ENDING }

    // ------------------------------------------------------------------ message
    public record HugHoldMsg() {
        public static void encode(HugHoldMsg m, FriendlyByteBuf buf) { }
        public static HugHoldMsg decode(FriendlyByteBuf buf) { return new HugHoldMsg(); }
        public static void handle(HugHoldMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { ServerPlayer p = c.getSender(); if (p != null
                    && CoopMovesConfig.get().enableHighFiveHug) onPlayerHoldingH(p); });
            c.setPacketHandled(true);
        }
    }

    public static void register() { }

    public static void registerMessages() {
        CoopNetwork.register(HugHoldMsg.class, HugHoldMsg::encode, HugHoldMsg::decode, HugHoldMsg::handle);
    }

    /** Called by HighFiveHandler on a successful high-five to open the hug window. */
    public static void startHugHold(ServerPlayer p1, ServerPlayer p2) {
        long now = System.currentTimeMillis();
        hugHoldStart.put(p1.getUUID(), now);
        hugHoldStart.put(p2.getUUID(), now);
        hugPartner.put(p1.getUUID(), p2.getUUID());
        hugPartner.put(p2.getUUID(), p1.getUUID());
    }

    private static void onPlayerHoldingH(ServerPlayer player) {
        UUID id = player.getUUID();
        long now = System.currentTimeMillis();
        lastHUpdate.put(id, now);
        Long holdStart = hugHoldStart.get(id);
        if (holdStart == null) return;
        if (now - holdStart < HUG_HOLD_TIME_MS) return;
        UUID partnerId = hugPartner.get(id);
        if (partnerId == null) return;
        ServerPlayer partner = player.getServer().getPlayerList().getPlayer(partnerId);
        if (partner == null) return;
        Long partnerHoldStart = hugHoldStart.get(partnerId);
        if (partnerHoldStart == null || now - partnerHoldStart < HUG_HOLD_TIME_MS) return;
        Long partnerLastH = lastHUpdate.get(partnerId);
        if (partnerLastH == null || now - partnerLastH > 1000) return;
        if (player.position().distanceTo(partner.position()) > HUG_DISTANCE) {
            player.displayClientMessage(Component.literal("§c❤ Get closer to hug! ❤"), true);
            return;
        }
        startHug(player, partner);
    }

    private static void startHug(ServerPlayer p1, ServerPlayer p2) {
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        long now = System.currentTimeMillis();
        hugHoldStart.remove(id1);
        hugHoldStart.remove(id2);
        hugState.put(id1, HugState.START);
        hugState.put(id2, HugState.START);
        hugStartTime.put(id1, now);
        hugStartTime.put(id2, now);
        PoseNetworking.broadcastAnimState(p1, ANIM_HUG_START);
        PoseNetworking.broadcastAnimState(p2, ANIM_HUG_START);
        p1.displayClientMessage(Component.literal("§d❤ Hugging... ❤"), true);
        p2.displayClientMessage(Component.literal("§d❤ Hugging... ❤"), true);
    }

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<UUID, Long>> holdIt = hugHoldStart.entrySet().iterator();
        while (holdIt.hasNext()) {
            Map.Entry<UUID, Long> entry = holdIt.next();
            if (now - entry.getValue() > HUG_OPPORTUNITY_MS) {
                holdIt.remove();
                hugPartner.remove(entry.getKey());
            }
        }

        Set<UUID> processed = new HashSet<>();
        Map<UUID, HugState> copy = new HashMap<>(hugState);
        List<Runnable> changes = new ArrayList<>();
        for (Map.Entry<UUID, HugState> entry : copy.entrySet()) {
            UUID id = entry.getKey();
            HugState state = entry.getValue();
            if (processed.contains(id)) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) { clearOne(id); continue; }
            UUID partnerId = hugPartner.get(id);
            if (partnerId == null) { hugState.remove(id); hugStartTime.remove(id); lastHUpdate.remove(id); continue; }
            ServerPlayer partner = server.getPlayerList().getPlayer(partnerId);
            if (partner == null) { clearOne(id); continue; }
            Long startTime = hugStartTime.get(id);
            if (startTime == null) continue;
            long elapsed = now - startTime;
            processed.add(id);
            processed.add(partnerId);

            if (state == HugState.START) {
                if (elapsed >= 333) changes.add(() -> transitionToHugging(player, partner));
            } else if (state == HugState.HUGGING) {
                Long l1 = lastHUpdate.get(id), l2 = lastHUpdate.get(partnerId);
                if (l1 == null || l2 == null || now - l1 > 1000 || now - l2 > 1000) {
                    changes.add(() -> endHug(player, partner));
                    continue;
                }
                double distance = player.position().distanceTo(partner.position());
                if (distance > 1.0) {
                    Vec3 pPos = player.position(), qPos = partner.position();
                    Vec3 dir = qPos.subtract(pPos).normalize();
                    Vec3 mid = pPos.add(qPos).scale(0.5);
                    Vec3 offset = dir.scale(0.4);
                    Vec3 tP = mid.subtract(offset), tQ = mid.add(offset);
                    player.teleportTo(player.serverLevel(), tP.x, tP.y, tP.z, player.getYRot(), player.getXRot());
                    partner.teleportTo(partner.serverLevel(), tQ.x, tQ.y, tQ.z, partner.getYRot(), partner.getXRot());
                } else if (distance > HUG_DISTANCE + 0.5) {
                    changes.add(() -> endHug(player, partner));
                    continue;
                }
                if (elapsed % 1000 < 50) applyHugEffects(player, partner);
            } else if (state == HugState.ENDING) {
                if (elapsed >= 542) {
                    clearOne(id);
                    PoseNetworking.broadcastAnimState(player, ANIM_NONE);
                }
            }
        }
        for (Runnable r : changes) r.run();
    }

    private static void clearOne(UUID id) {
        hugState.remove(id); hugPartner.remove(id); hugStartTime.remove(id); lastHUpdate.remove(id);
    }

    private static void transitionToHugging(ServerPlayer p1, ServerPlayer p2) {
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        long now = System.currentTimeMillis();
        hugState.put(id1, HugState.HUGGING);
        hugState.put(id2, HugState.HUGGING);
        hugStartTime.put(id1, now);
        hugStartTime.put(id2, now);
        if (new Random().nextBoolean()) {
            PoseNetworking.broadcastAnimState(p1, ANIM_HUGGING);
            PoseNetworking.broadcastAnimState(p2, ANIM_HUGGING2);
        } else {
            PoseNetworking.broadcastAnimState(p1, ANIM_HUGGING2);
            PoseNetworking.broadcastAnimState(p2, ANIM_HUGGING);
        }
        applyHugEffects(p1, p2);
    }

    private static void applyHugEffects(ServerPlayer p1, ServerPlayer p2) {
        p1.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 40, 1, false, false));
        p2.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 40, 1, false, false));
        Vec3 pos = p1.position().add(p2.position()).scale(0.5).add(0, 1, 0);
        ServerLevel world = p1.serverLevel();
        world.sendParticles(ParticleTypes.HEART, pos.x, pos.y, pos.z, 5, 0.3, 0.3, 0.3, 0.1);
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.1f, 1.5f);
    }

    private static void endHug(ServerPlayer p1, ServerPlayer p2) {
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        long now = System.currentTimeMillis();
        hugState.put(id1, HugState.ENDING);
        hugState.put(id2, HugState.ENDING);
        hugStartTime.put(id1, now);
        hugStartTime.put(id2, now);
        PoseNetworking.broadcastAnimState(p1, ANIM_HUG_END);
        PoseNetworking.broadcastAnimState(p2, ANIM_HUG_END);
        p1.displayClientMessage(Component.literal("§e Hug ended "), true);
        p2.displayClientMessage(Component.literal("§e Hug ended "), true);
    }

    public static boolean isInHugFreeze(UUID playerId) {
        HugState state = hugState.get(playerId);
        return state == HugState.START || state == HugState.HUGGING;
    }

    /** Alias kept for callers that used the stub name. */
    public static boolean isInHug(UUID playerId) { return isInHugFreeze(playerId); }

    public static void cleanup(UUID playerId) { clearOne(playerId); hugHoldStart.remove(playerId); }
}
