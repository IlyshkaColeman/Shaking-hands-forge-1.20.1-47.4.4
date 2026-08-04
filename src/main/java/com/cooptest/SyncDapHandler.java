package com.cooptest;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Synchronized "ping-pong" skill-check dap.
 *
 * Flow:
 *   1. A player holds G with an empty hand -> "waiting" (hand extended pose).
 *   2. A second player nearby & facing also holds G -> both engage: a marker
 *      ping-pongs up/down a shared bar on each client (client-side visual).
 *   3. Each player releases G to lock their marker (0..100).
 *   4. When both have locked, the zones are compared:
 *        - both in the PERFECT cube  -> best dap
 *        - both in the MEDIUM band   -> medium dap
 *        - both outside (basic)      -> weak dap
 *        - different zones           -> FAIL: fart/miss sound, animation cancels,
 *                                        "You're shitting yourself" subtitle.
 *
 * Gated by {@link CoopMovesConfig#enableSyncDap}. When on, the client routes G to
 * this system instead of the classic hold-to-charge dap.
 */
public final class SyncDapHandler {

    private SyncDapHandler() {}

    // ---- anim ordinals (mirror CoopAnimationHandler.AnimState) ----
    private static final int ANIM_NONE = 0;
    private static final int ANIM_DAP_CHARGING = 7;
    private static final int ANIM_DAP_CHARGE_IDLE = 8;
    private static final int ANIM_DAP_HIT = 9;
    private static final int ANIM_PERFECT_DAP_HIT = 26;

    // ---- tuning ----
    private static final double ENGAGE_RANGE = 3.0;
    private static final long HOLD_TIMEOUT_MS = 8000;
    private static final long LOCK_WAIT_MS = 2500;

    // zone boundaries on the 0..100 bar (keep in sync with the client HUD)
    public static final int PERFECT_MIN = 42, PERFECT_MAX = 58;
    public static final int MEDIUM_MIN = 22, MEDIUM_MAX = 78;

    /** 2 = perfect cube, 1 = medium band, 0 = basic (outside). */
    public static int zoneOf(int marker) {
        if (marker >= PERFECT_MIN && marker <= PERFECT_MAX) return 2;
        if (marker >= MEDIUM_MIN && marker <= MEDIUM_MAX) return 1;
        return 0;
    }

    // ---- state ----
    private static final Map<UUID, Long> holding = new HashMap<>();   // player -> hold start time (unpaired)
    private static final Map<UUID, UUID> pair = new HashMap<>();      // player <-> partner (engaged)
    private static final Map<UUID, Long> pairStart = new HashMap<>(); // player -> engage time
    private static final Map<UUID, Integer> locked = new HashMap<>(); // player -> locked marker

    // ------------------------------------------------------------------ messages

    public record SyncHoldMsg() {
        public static void encode(SyncHoldMsg m, FriendlyByteBuf b) { }
        public static SyncHoldMsg decode(FriendlyByteBuf b) { return new SyncHoldMsg(); }
        public static void handle(SyncHoldMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { ServerPlayer p = c.getSender(); if (p != null) onHold(p); });
            c.setPacketHandled(true);
        }
    }

    public record SyncLockMsg(int marker) {
        public static void encode(SyncLockMsg m, FriendlyByteBuf b) { b.writeInt(m.marker); }
        public static SyncLockMsg decode(FriendlyByteBuf b) { return new SyncLockMsg(b.readInt()); }
        public static void handle(SyncLockMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> { ServerPlayer p = c.getSender(); if (p != null) onLock(p, m.marker()); });
            c.setPacketHandled(true);
        }
    }

    public record SyncActiveMsg(boolean active) {
        public static void encode(SyncActiveMsg m, FriendlyByteBuf b) { b.writeBoolean(m.active); }
        public static SyncActiveMsg decode(FriendlyByteBuf b) { return new SyncActiveMsg(b.readBoolean()); }
        public static void handle(SyncActiveMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer())
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.SyncDapClientHandler.onActive(m.active()));
            });
            c.setPacketHandled(true);
        }
    }

    /** S2C: tells a client the partner locked their marker (freezes the partner mini-bar). */
    public record SyncPartnerLockMsg(int marker) {
        public static void encode(SyncPartnerLockMsg m, FriendlyByteBuf b) { b.writeInt(m.marker); }
        public static SyncPartnerLockMsg decode(FriendlyByteBuf b) { return new SyncPartnerLockMsg(b.readInt()); }
        public static void handle(SyncPartnerLockMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer())
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.SyncDapClientHandler.onPartnerLock(m.marker()));
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(SyncHoldMsg.class, SyncHoldMsg::encode, SyncHoldMsg::decode, SyncHoldMsg::handle);
        CoopNetwork.register(SyncLockMsg.class, SyncLockMsg::encode, SyncLockMsg::decode, SyncLockMsg::handle);
        CoopNetwork.register(SyncActiveMsg.class, SyncActiveMsg::encode, SyncActiveMsg::decode, SyncActiveMsg::handle);
        CoopNetwork.register(SyncPartnerLockMsg.class, SyncPartnerLockMsg::encode, SyncPartnerLockMsg::decode, SyncPartnerLockMsg::handle);
    }

    // ------------------------------------------------------------------ server logic

    private static void onHold(ServerPlayer player) {
        if (!CoopMovesConfig.get().enableSyncDap) return;
        UUID id = player.getUUID();
        if (!player.getMainHandItem().isEmpty()) return;
        if (pair.containsKey(id) || holding.containsKey(id)) return;

        ServerPlayer partner = findWaitingPartner(player);
        if (partner != null) {
            UUID pid = partner.getUUID();
            holding.remove(pid);
            long now = System.currentTimeMillis();
            pair.put(id, pid); pair.put(pid, id);
            pairStart.put(id, now); pairStart.put(pid, now);
            CoopNetwork.sendToPlayer(player, new SyncActiveMsg(true));
            CoopNetwork.sendToPlayer(partner, new SyncActiveMsg(true));
            PoseNetworking.broadcastAnimState(player, ANIM_DAP_CHARGE_IDLE);
            PoseNetworking.broadcastAnimState(partner, ANIM_DAP_CHARGE_IDLE);
            player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.6f, 1.4f);
        } else {
            holding.put(id, System.currentTimeMillis());
            PoseNetworking.broadcastAnimState(player, ANIM_DAP_CHARGING);
            player.displayClientMessage(Component.literal("§7Waiting for a partner to press G..."), true);
        }
    }

    /** Another player currently waiting (holding, unpaired), in range and roughly facing. */
    private static ServerPlayer findWaitingPartner(ServerPlayer player) {
        Vec3 look = player.getViewVector(1.0f);
        ServerPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayer other : player.serverLevel().players()) {
            if (other == player) continue;
            if (!holding.containsKey(other.getUUID())) continue;
            double dist = player.distanceTo(other);
            if (dist > ENGAGE_RANGE) continue;
            Vec3 toOther = other.position().subtract(player.position());
            if (toOther.lengthSqr() > 0.0001 && look.dot(toOther.normalize()) < -0.2) continue; // must be roughly in front
            if (dist < bestDist) { bestDist = dist; best = other; }
        }
        return best;
    }

    private static void onLock(ServerPlayer player, int marker) {
        UUID id = player.getUUID();
        if (holding.remove(id) != null) {
            // Was holding G with no partner engaged. If aiming at another (non-dapping)
            // player's head, slap them (back / front slap); otherwise drop the pose.
            if (!SlapHandler.checkSlapOnRelease(player)) {
                PoseNetworking.broadcastAnimState(player, ANIM_NONE);
            }
            return;
        }
        if (!pair.containsKey(id)) return;
        int m = Math.max(0, Math.min(100, marker));
        locked.put(id, m);
        UUID pid = pair.get(id);
        ServerPlayer partner = player.getServer().getPlayerList().getPlayer(pid);
        // Let the partner's client freeze this player's mini-bar at the locked spot.
        if (partner != null) CoopNetwork.sendToPlayer(partner, new SyncPartnerLockMsg(m));
        if (locked.containsKey(pid)) {
            if (partner != null) evaluate(player, partner);
            else clearPair(id, pid);
        }
    }

    private static void evaluate(ServerPlayer a, ServerPlayer b) {
        UUID ida = a.getUUID(), idb = b.getUUID();
        int ma = locked.getOrDefault(ida, 0), mb = locked.getOrDefault(idb, 0);
        int za = zoneOf(ma), zb = zoneOf(mb);
        clearPair(ida, idb);

        // Face each other for the impact.
        Vec3 pa = a.position(), pb = b.position();
        double dx = pb.x - pa.x, dz = pb.z - pa.z;
        float yawA = (float) (Math.atan2(dz, dx) * 180 / Math.PI) - 90f;
        a.setYRot(yawA); a.setYBodyRot(yawA); a.setYHeadRot(yawA);
        b.setYRot(yawA + 180f); b.setYBodyRot(yawA + 180f); b.setYHeadRot(yawA + 180f);
        a.swing(InteractionHand.MAIN_HAND); b.swing(InteractionHand.MAIN_HAND);

        if (za != zb) { fail(a, b); return; }
        switch (za) {
            case 2 -> impact(a, b, 2);
            case 1 -> impact(a, b, 1);
            default -> impact(a, b, 0);
        }
    }

    private static void fail(ServerPlayer a, ServerPlayer b) {
        ServerLevel world = a.serverLevel();
        Vec3 mid = a.position().add(b.position()).scale(0.5).add(0, 1.2, 0);
        // "Fart" cue — reuse the existing miss sound (assets/testcoop/sounds/miss.ogg).
        world.playSound(null, mid.x, mid.y, mid.z, ModSounds.DAP_MISS.get(), SoundSource.PLAYERS, 1.2f, 0.8f);
        world.sendParticles(ParticleTypes.SMOKE, mid.x, mid.y - 0.6, mid.z, 12, 0.2, 0.1, 0.2, 0.02);
        PoseNetworking.broadcastAnimState(a, ANIM_NONE);
        PoseNetworking.broadcastAnimState(b, ANIM_NONE);
        a.displayClientMessage(Component.literal("§6You're shitting yourself"), true);
        b.displayClientMessage(Component.literal("§6You're shitting yourself"), true);
    }

    private static void impact(ServerPlayer a, ServerPlayer b, int tier) {
        // Reuse the classic dap tier effects (particles, sounds, and — for the green
        // cube — the perfect-dap impact frames + silhouette shader).
        ChargedDapHandler.runSyncDap(a, b, tier);
        String msg = switch (tier) {
            case 2 -> "§6§l✋ PERFECT SYNC DAP! ✋";
            case 1 -> "§a✋ Good Dap! ✋";
            default -> "§7Dap";
        };
        a.displayClientMessage(Component.literal(msg), true);
        b.displayClientMessage(Component.literal(msg), true);
    }

    // ------------------------------------------------------------------ tick / cleanup

    public static void tick(MinecraftServer server) {
        if (holding.isEmpty() && pair.isEmpty()) return;
        long now = System.currentTimeMillis();
        // drop stale unpaired holds
        holding.entrySet().removeIf(e -> {
            if (now - e.getValue() > HOLD_TIMEOUT_MS) {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p != null) PoseNetworking.broadcastAnimState(p, ANIM_NONE);
                return true;
            }
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            return p == null;
        });
        // drop pairs where one side never locked in time or a player left
        for (UUID id : new java.util.ArrayList<>(pairStart.keySet())) {
            Long start = pairStart.get(id);
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) { UUID pid = pair.get(id); clearPair(id, pid); continue; }
            if (start != null && now - start > LOCK_WAIT_MS + HOLD_TIMEOUT_MS) {
                UUID pid = pair.get(id);
                PoseNetworking.broadcastAnimState(p, ANIM_NONE);
                clearPair(id, pid);
            }
        }
    }

    private static void clearPair(UUID a, UUID b) {
        pair.remove(a); pairStart.remove(a); locked.remove(a);
        if (b != null) { pair.remove(b); pairStart.remove(b); locked.remove(b); }
    }

    public static void cleanup(UUID id) {
        holding.remove(id);
        UUID pid = pair.get(id);
        clearPair(id, pid);
    }

    public static boolean isBusy(UUID id) {
        return holding.containsKey(id) || pair.containsKey(id);
    }
}
