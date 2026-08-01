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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * "Normal facing" dap-loop: after a facing dap, both players hold to keep chaining
 * daps, escalating FX and lightning; right-click ends it. Ported from Fabric to
 * Forge 1.20.1.
 *
 * UseEntityCallback -> PlayerInteractEvent.EntityInteract; LightningEntity ->
 * LightningBolt; ServerTickEvents -> CoopServerTick; CustomPayload -> CoopNetwork
 * messages. AnimState ordinals hardcoded (DAP_HIT_FACE=81, DAP_LOOP=84,
 * DAP_LOOP_END=85) since CoopAnimationHandler is client-only.
 */
public final class NormalFacingDapHandler {

    private NormalFacingDapHandler() {}

    private static final int ANIM_DAP_HIT_FACE = 81;
    private static final int ANIM_DAP_LOOP = 84;
    private static final int ANIM_DAP_LOOP_END = 85;
    private static final int ANIM_NONE = 0;

    private static final double FACE_DIST = 1.3;
    private static final long ANIM_TOTAL = 3333L;
    private static final long LOOP_ENTRY_MS = 2080L;
    private static final long LOOP_CYCLE_MS = 1250L;
    private static final long LOOP_END_MS = 917L;
    private static final long CLICK_WINDOW = 2000L;

    private static final Map<UUID, UUID> activeSessions = new HashMap<>();
    private static final Map<UUID, Boolean> inLoop = new HashMap<>();
    private static final Map<String, Long> sessionStart = new HashMap<>();
    private static final Map<UUID, Long> lastHold = new HashMap<>();
    private static final Map<UUID, Long> cycleStart = new HashMap<>();
    private static final Map<String, Integer> loopCount = new HashMap<>();
    private static final Map<String, Long> loopStartTime = new HashMap<>();
    private static final Map<String, UUID> canonicalP1 = new HashMap<>();
    private static final Map<UUID, UUID> clickMap = new HashMap<>();
    private static final Map<UUID, Long> clickTime = new HashMap<>();

    public static boolean isActive(UUID id) { return activeSessions.containsKey(id); }

    public static void onLoopHold(ServerPlayer player) {
        if (activeSessions.containsKey(player.getUUID())) {
            lastHold.put(player.getUUID(), System.currentTimeMillis());
        }
    }

    private static String key(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
    }

    // ------------------------------------------------------------------ lifecycle

    public static void register() {
        MinecraftForge.EVENT_BUS.register(NormalFacingDapHandler.class);
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!(event.getTarget() instanceof ServerPlayer)) return;
        if (sp.isShiftKeyDown()) return;
        if (Boolean.TRUE.equals(inLoop.get(sp.getUUID()))) {
            UUID partner = activeSessions.get(sp.getUUID());
            if (partner != null) endLoop(sp.getServer(), sp.getUUID(), partner);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    public static void tick(MinecraftServer server) {
        if (activeSessions.isEmpty()) return;
        Set<UUID> processed = new HashSet<>();
        for (Map.Entry<UUID, UUID> e : new HashMap<>(activeSessions).entrySet()) {
            UUID p1 = e.getKey(), p2 = e.getValue();
            if (processed.contains(p1)) continue;
            processed.add(p1); processed.add(p2);
            pin(server, p1); pin(server, p2);
            String k = key(p1, p2);
            UUID canon = canonicalP1.get(k);
            if (canon == null) continue;
            if (Boolean.TRUE.equals(inLoop.get(canon))) {
                UUID other = activeSessions.get(canon);
                if (other != null) doTickLoop(server, canon, other);
            } else {
                Long ss = sessionStart.get(k);
                long now = System.currentTimeMillis();
                if (ss != null && now - ss >= LOOP_ENTRY_MS) {
                    Long h1 = lastHold.get(p1), h2 = lastHold.get(p2);
                    boolean b1 = h1 != null && now - h1 < 2000L;
                    boolean b2 = h2 != null && now - h2 < 2000L;
                    if (b1 && b2) startLoop(server, canon, activeSessions.get(canon));
                }
            }
        }
    }

    public static void recordRightClick(ServerPlayer sp, ServerPlayer target) {
        clickMap.put(sp.getUUID(), target.getUUID());
        clickTime.put(sp.getUUID(), System.currentTimeMillis());
        sp.displayClientMessage(Component.literal("§e✦ Waiting for homie..."), true);
    }

    public static boolean isConfirmed(UUID id1, UUID id2) {
        long now = System.currentTimeMillis();
        UUID c1 = clickMap.get(id1); Long t1 = clickTime.get(id1);
        UUID c2 = clickMap.get(id2); Long t2 = clickTime.get(id2);
        return id2.equals(c1) && t1 != null && now - t1 < CLICK_WINDOW
                && id1.equals(c2) && t2 != null && now - t2 < CLICK_WINDOW;
    }

    public static boolean isConfirmedOneSide(UUID who, UUID target) {
        UUID c = clickMap.get(who); Long t = clickTime.get(who);
        return target.equals(c) && t != null && System.currentTimeMillis() - t < CLICK_WINDOW;
    }

    public static void clearConfirm(UUID id1, UUID id2) {
        clickMap.remove(id1); clickTime.remove(id1);
        if (id2 != null) { clickMap.remove(id2); clickTime.remove(id2); }
    }

    public static void start(ServerPlayer p1, ServerPlayer p2) {
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        String k = key(id1, id2);
        activeSessions.put(id1, id2);
        activeSessions.put(id2, id1);
        loopCount.put(k, 0);
        sessionStart.put(k, System.currentTimeMillis());
        canonicalP1.put(k, id1);
        MinecraftServer server = p1.getServer();
        if (server == null) return;
        Vec3 diff = p2.position().subtract(p1.position());
        Vec3 flat = new Vec3(diff.x, 0, diff.z).normalize();
        Vec3 mid = p1.position().add(p2.position()).scale(0.5);
        Vec3 pos1 = mid.subtract(flat.scale(FACE_DIST * 0.5));
        Vec3 pos2 = mid.add(flat.scale(FACE_DIST * 0.5));
        float yaw1 = (float) Math.toDegrees(Math.atan2(-flat.x, flat.z));
        float yaw2 = yaw1 + 180f;
        p1.teleportTo(p1.serverLevel(), pos1.x, p1.getY(), pos1.z, Set.<RelativeMovement>of(), yaw1, p1.getXRot());
        p2.teleportTo(p2.serverLevel(), pos2.x, p2.getY(), pos2.z, Set.<RelativeMovement>of(), yaw2, p2.getXRot());
        p1.setYRot(yaw1); p1.setYBodyRot(yaw1); p1.setYHeadRot(yaw1);
        p2.setYRot(yaw2); p2.setYBodyRot(yaw2); p2.setYHeadRot(yaw2);
        p1.swing(InteractionHand.MAIN_HAND, true);
        p2.swing(InteractionHand.MAIN_HAND, true);
        CoopNetwork.sendToPlayer(p1, new ChargedDapHandler.PerfectDapFreezePayload(true));
        CoopNetwork.sendToPlayer(p2, new ChargedDapHandler.PerfectDapFreezePayload(true));
        CoopNetwork.sendToPlayer(p1, new FaceDapSessionMsg(true));
        CoopNetwork.sendToPlayer(p2, new FaceDapSessionMsg(true));
        PoseNetworking.broadcastAnimState(p1, ANIM_DAP_HIT_FACE);
        PoseNetworking.broadcastAnimState(p2, ANIM_DAP_HIT_FACE);
        schedule(server, 420L, () -> {
            ServerPlayer a = server.getPlayerList().getPlayer(id1);
            ServerPlayer b = server.getPlayerList().getPlayer(id2);
            if (a == null || b == null) return;
            ServerLevel w = a.serverLevel();
            Vec3 m = a.position().add(b.position()).scale(0.5).add(0, 1.3, 0);
            w.playSound(null, m.x, m.y, m.z, ModSounds.DAP_HIT.get(), SoundSource.PLAYERS, 1.5f, 1.0f);
            w.playSound(null, m.x, m.y, m.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0f, 1.2f);
            w.sendParticles(ParticleTypes.CRIT, m.x, m.y, m.z, 12, 0.2, 0.2, 0.2, 0.1);
            w.sendParticles(ParticleTypes.ENCHANTED_HIT, m.x, m.y, m.z, 6, 0.15, 0.15, 0.15, 0.07);
            w.sendParticles(ParticleTypes.FLASH, m.x, m.y, m.z, 2, 0, 0, 0, 0);
        });
        long[] punches = {1333, 1417, 1583, 1667, 1833, 2000, 2167};
        for (long t : punches) {
            schedule(server, t, () -> {
                ServerPlayer a = server.getPlayerList().getPlayer(id1);
                ServerPlayer b = server.getPlayerList().getPlayer(id2);
                if (a == null || b == null) return;
                Vec3 m = a.position().add(b.position()).scale(0.5).add(0, 1.3, 0);
                a.serverLevel().sendParticles(ParticleTypes.CRIT, m.x, m.y, m.z, 3, 0.1, 0.1, 0.1, 0.06);
                a.serverLevel().playSound(null, m.x, m.y, m.z,
                        SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 0.7f, 1.0f + (float) (Math.random() * 0.3));
            });
        }
        schedule(server, ANIM_TOTAL + 100L, () -> {
            if (!Boolean.TRUE.equals(inLoop.get(id1))) cleanup(server, id1, id2);
        });
    }

    private static void startLoop(MinecraftServer server, UUID id1, UUID id2) {
        long now = System.currentTimeMillis();
        inLoop.put(id1, true);
        inLoop.put(id2, true);
        cycleStart.put(id1, now);
        loopStartTime.put(key(id1, id2), now);
        lastHold.put(id1, now); lastHold.put(id2, now);
        ServerPlayer a = server.getPlayerList().getPlayer(id1);
        ServerPlayer b = server.getPlayerList().getPlayer(id2);
        if (a != null) PoseNetworking.broadcastAnimState(a, ANIM_DAP_LOOP);
        if (b != null) PoseNetworking.broadcastAnimState(b, ANIM_DAP_LOOP);
    }

    private static void doTickLoop(MinecraftServer server, UUID id1, UUID id2) {
        Long cs = cycleStart.get(id1);
        if (cs == null) return;
        long now = System.currentTimeMillis();
        if (now - cs < LOOP_CYCLE_MS) return;
        Long h1 = lastHold.get(id1), h2 = lastHold.get(id2);
        if (h1 == null || now - h1 > 2000L || h2 == null || now - h2 > 2000L) {
            endLoop(server, id1, id2);
            return;
        }
        cycleStart.put(id1, now);
        String k = key(id1, id2);
        int count = loopCount.getOrDefault(k, 0) + 1;
        loopCount.put(k, count);
        ServerPlayer a = server.getPlayerList().getPlayer(id1);
        ServerPlayer b = server.getPlayerList().getPlayer(id2);
        if (a == null || b == null) { endLoop(server, id1, id2); return; }
        ServerLevel w = a.serverLevel();
        Vec3 m = a.position().add(b.position()).scale(0.5).add(0, 1.3, 0);
        Long ls = loopStartTime.get(k);
        long sec = ls != null ? (now - ls) / 1000L : 0;
        a.displayClientMessage(Component.literal("§e⚡ " + count + " §f" + sec + "s"), true);
        b.displayClientMessage(Component.literal("§e⚡ " + count + " §f" + sec + "s"), true);
        w.playSound(null, m.x, m.y, m.z, ModSounds.DAP_HIT.get(), SoundSource.PLAYERS, 1.0f + Math.min(count * 0.03f, 0.5f), 1.0f);
        w.sendParticles(ParticleTypes.CRIT, m.x, m.y, m.z, 4 + Math.min(count, 20), 0.2, 0.2, 0.2, 0.05);
        if (count >= 9) w.sendParticles(ParticleTypes.END_ROD, m.x, m.y, m.z, count, 0.5, 0.3, 0.5, 0.05);
        if (count >= 25 && count % 4 == 0) {
            LightningBolt l = new LightningBolt(EntityType.LIGHTNING_BOLT, w);
            l.setPos(m.x, m.y, m.z);
            l.setVisualOnly(true);
            w.addFreshEntity(l);
        }
        if (count >= 480) endLoop(server, id1, id2);
    }

    private static void endLoop(MinecraftServer server, UUID id1, UUID id2) {
        inLoop.remove(id1); inLoop.remove(id2);
        cycleStart.remove(id1);
        ServerPlayer a = server.getPlayerList().getPlayer(id1);
        ServerPlayer b = server.getPlayerList().getPlayer(id2);
        if (a != null) PoseNetworking.broadcastAnimState(a, ANIM_DAP_LOOP_END);
        if (b != null) PoseNetworking.broadcastAnimState(b, ANIM_DAP_LOOP_END);
        if (server != null) schedule(server, LOOP_END_MS, () -> cleanup(server, id1, id2));
    }

    private static void cleanup(MinecraftServer server, UUID id1, UUID id2) {
        String k = key(id1, id2);
        activeSessions.remove(id1); activeSessions.remove(id2);
        inLoop.remove(id1); inLoop.remove(id2);
        cycleStart.remove(id1);
        loopCount.remove(k); loopStartTime.remove(k);
        sessionStart.remove(k); canonicalP1.remove(k);
        lastHold.remove(id1); lastHold.remove(id2);
        ServerPlayer a = server.getPlayerList().getPlayer(id1);
        ServerPlayer b = server.getPlayerList().getPlayer(id2);
        if (a != null) { CoopNetwork.sendToPlayer(a, new ChargedDapHandler.PerfectDapFreezePayload(false)); CoopNetwork.sendToPlayer(a, new FaceDapSessionMsg(false)); PoseNetworking.broadcastAnimState(a, ANIM_NONE); }
        if (b != null) { CoopNetwork.sendToPlayer(b, new ChargedDapHandler.PerfectDapFreezePayload(false)); CoopNetwork.sendToPlayer(b, new FaceDapSessionMsg(false)); PoseNetworking.broadcastAnimState(b, ANIM_NONE); }
        long cd = System.currentTimeMillis() + ChargedDapHandler.cooldownMs();
        ChargedDapHandler.cooldowns.put(id1, cd); ChargedDapHandler.cooldowns.put(id2, cd);
    }

    public static void cleanup(UUID id) {
        UUID partner = activeSessions.remove(id);
        if (partner != null) { activeSessions.remove(partner); inLoop.remove(partner); }
        inLoop.remove(id); cycleStart.remove(id);
        clickMap.remove(id); clickTime.remove(id); lastHold.remove(id);
    }

    private static void pin(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) { p.setDeltaMovement(0, 0, 0); p.hurtMarked = true; }
    }

    private static void schedule(MinecraftServer server, long delayMs, Runnable task) {
        new Timer(true).schedule(new TimerTask() {
            @Override public void run() { server.execute(task); }
        }, delayMs);
    }

    // ------------------------------------------------------------------ networking

    /** C2S: keep the dap loop alive (hold). */
    public record DapLoopHoldMsg() {
        public static void encode(DapLoopHoldMsg m, FriendlyByteBuf buf) { }
        public static DapLoopHoldMsg decode(FriendlyByteBuf buf) { return new DapLoopHoldMsg(); }
        public static void handle(DapLoopHoldMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player != null) onLoopHold(player);
            });
            c.setPacketHandled(true);
        }
    }

    /** S2C: toggle the client's "in face-dap session" flag. */
    public record FaceDapSessionMsg(boolean active) {
        public static void encode(FaceDapSessionMsg m, FriendlyByteBuf buf) { buf.writeBoolean(m.active); }
        public static FaceDapSessionMsg decode(FriendlyByteBuf buf) { return new FaceDapSessionMsg(buf.readBoolean()); }
        public static void handle(FaceDapSessionMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.ChargedDapClientHandler.setInFaceDapSession(m.active()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(DapLoopHoldMsg.class, DapLoopHoldMsg::encode, DapLoopHoldMsg::decode, DapLoopHoldMsg::handle);
        CoopNetwork.register(FaceDapSessionMsg.class, FaceDapSessionMsg::encode, FaceDapSessionMsg::decode, FaceDapSessionMsg::handle);
    }

    public static void sendLoopHold() {
        CoopNetwork.sendToServer(new DapLoopHoldMsg());
    }
}
