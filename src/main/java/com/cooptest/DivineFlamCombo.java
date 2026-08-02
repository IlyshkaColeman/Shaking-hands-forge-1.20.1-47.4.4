package com.cooptest;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Divine Flame combo — after a fire dap, both players press the combo key (J) in a
 * short window to unleash a flame vortex. Ported from Fabric to Forge 1.20.1.
 *
 * Started by ChargedDapHandler (fire-dap core, ported later). Anim ordinals travel
 * as raw ints (FIRE_DAP_COMBO_P1=36/P2=37, DAPHOLD_HIGHFIVE=38 in the original).
 * CustomPayload -> CoopNetwork messages; tick driven by CoopServerTick.
 */
public final class DivineFlamCombo {

    private DivineFlamCombo() {}

    private static final Map<UUID, Long> comboWindowStart = new HashMap<>();
    private static final Map<UUID, UUID> comboPartner = new HashMap<>();
    private static final Map<UUID, Long> comboFreezeEnd = new HashMap<>();
    private static final long COMBO_WINDOW_MS = 1460;
    private static final long COMBO_FREEZE_MS = 3800;

    public static void register() { }

    public static void startDivineFlame(ServerPlayer p1, ServerPlayer p2, Vec3 midpoint) {
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        long now = System.currentTimeMillis();
        comboWindowStart.put(id1, now);
        comboWindowStart.put(id2, now);
        comboPartner.put(id1, id2);
        comboPartner.put(id2, id1);
        PoseNetworking.broadcastAnimState(p1, 36);
        PoseNetworking.broadcastAnimState(p2, 36);
        comboFreezeEnd.put(id1, now + COMBO_WINDOW_MS);
        comboFreezeEnd.put(id2, now + COMBO_WINDOW_MS);
        CoopNetwork.sendToPlayer(p1, new DivineStartMsg());
        CoopNetwork.sendToPlayer(p2, new DivineStartMsg());
        ServerLevel world = p1.serverLevel();
        world.playSound(null, midpoint.x, midpoint.y, midpoint.z,
                ModSounds.EPIC_DAP.get(), SoundSource.PLAYERS, 1.5f, 1.1f);
    }

    private static void onPlayerPressJ(ServerPlayer player) {
        UUID playerId = player.getUUID();
        Long windowStart = comboWindowStart.get(playerId);
        if (windowStart == null) return;
        long elapsed = System.currentTimeMillis() - windowStart;
        if (elapsed > COMBO_WINDOW_MS) {
            comboWindowStart.remove(playerId);
            comboPartner.remove(playerId);
            return;
        }
        UUID partnerId = comboPartner.get(playerId);
        if (partnerId == null) return;
        ServerPlayer partner = player.getServer().getPlayerList().getPlayer(partnerId);
        if (partner == null) return;
        if (!comboWindowStart.containsKey(partnerId)) return;
        executeCombo(player, partner);
    }

    private static void executeCombo(ServerPlayer p1, ServerPlayer p2) {
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        long now = System.currentTimeMillis();
        comboWindowStart.remove(id1);
        comboWindowStart.remove(id2);
        comboPartner.remove(id1);
        comboPartner.remove(id2);
        comboFreezeEnd.put(id1, now + COMBO_FREEZE_MS);
        comboFreezeEnd.put(id2, now + COMBO_FREEZE_MS);
        PoseNetworking.broadcastAnimState(p1, 37);
        PoseNetworking.broadcastAnimState(p2, 38);
        ServerLevel world = p1.serverLevel();
        Vec3 midpoint = p1.position().add(p2.position()).scale(0.5);
        world.playSound(null, midpoint.x, midpoint.y, midpoint.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 10.0f, 0.2f);
        world.playSound(null, midpoint.x, midpoint.y, midpoint.z, SoundEvents.GHAST_SHOOT, SoundSource.PLAYERS, 5.0f, 0.5f);
        world.sendParticles(ParticleTypes.FLAME, midpoint.x, midpoint.y + 1, midpoint.z, 100, 1.0, 1.0, 1.0, 0.2);
        world.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, midpoint.x, midpoint.y + 1, midpoint.z, 50, 0.8, 0.8, 0.8, 0.15);
        world.sendParticles(ParticleTypes.LAVA, midpoint.x, midpoint.y + 1, midpoint.z, 30, 0.5, 0.5, 0.5, 0.1);
    }

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, UUID> entry : new HashMap<>(comboPartner).entrySet()) {
            UUID id1 = entry.getKey();
            UUID id2 = entry.getValue();
            ServerPlayer p1 = server.getPlayerList().getPlayer(id1);
            ServerPlayer p2 = server.getPlayerList().getPlayer(id2);
            if (p1 != null && p2 != null) {
                double distance = p1.position().distanceTo(p2.position());
                if (distance > 1.0) {
                    Vec3 p1Pos = p1.position();
                    Vec3 p2Pos = p2.position();
                    Vec3 direction = p2Pos.subtract(p1Pos).normalize();
                    double targetDistance = 0.8;
                    Vec3 midpoint = p1Pos.add(p2Pos).scale(0.5);
                    Vec3 offset = direction.scale(targetDistance / 2.0);
                    Vec3 targetP1 = midpoint.subtract(offset);
                    Vec3 targetP2 = midpoint.add(offset);
                    double dx = p2Pos.x - p1Pos.x;
                    double dz = p2Pos.z - p1Pos.z;
                    float yawP1 = (float) (Math.atan2(dz, dx) * 180 / Math.PI) - 90;
                    float yawP2 = yawP1 + 180;
                    p1.teleportTo(p1.serverLevel(), targetP1.x, targetP1.y, targetP1.z, yawP1, p1.getXRot());
                    p2.teleportTo(p2.serverLevel(), targetP2.x, targetP2.y, targetP2.z, yawP2, p2.getXRot());
                }
            }
        }
        comboWindowStart.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > COMBO_WINDOW_MS) {
                UUID id = entry.getKey();
                comboPartner.remove(id);
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) PoseNetworking.broadcastAnimState(player, 0);
                return true;
            }
            return false;
        });
        comboFreezeEnd.entrySet().removeIf(entry -> {
            if (now >= entry.getValue()) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) PoseNetworking.broadcastAnimState(player, 0);
                return true;
            }
            return false;
        });
    }

    public static void cleanup(UUID playerId) {
        comboWindowStart.remove(playerId);
        comboPartner.remove(playerId);
        comboFreezeEnd.remove(playerId);
    }

    public static boolean isInComboFreeze(UUID playerId) {
        return comboFreezeEnd.containsKey(playerId);
    }

    // ------------------------------------------------------------------ networking

    public record DivineJPressMsg() {
        public static void encode(DivineJPressMsg m, FriendlyByteBuf buf) { }
        public static DivineJPressMsg decode(FriendlyByteBuf buf) { return new DivineJPressMsg(); }
        public static void handle(DivineJPressMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player != null) onPlayerPressJ(player);
            });
            c.setPacketHandled(true);
        }
    }

    public record DivineStartMsg() {
        public static void encode(DivineStartMsg m, FriendlyByteBuf buf) { }
        public static DivineStartMsg decode(FriendlyByteBuf buf) { return new DivineStartMsg(); }
        public static void handle(DivineStartMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.DivineFlamComboClient.onDivineStart());
                }
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(DivineJPressMsg.class, DivineJPressMsg::encode, DivineJPressMsg::decode, DivineJPressMsg::handle);
        CoopNetwork.register(DivineStartMsg.class, DivineStartMsg::encode, DivineStartMsg::decode, DivineStartMsg::handle);
    }

    public static void sendJPress() {
        CoopNetwork.sendToServer(new DivineJPressMsg());
    }
}
