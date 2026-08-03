package com.cooptest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Slap — full-charge dap release with no dap partner, aimed at another player's head
 * (from behind = back-slap "I like ya cut G", from the front = front-slap). Consulted
 * by {@link ChargedDapHandler#onChargeRelease}. Ported from Fabric to Forge 1.20.1.
 *
 * Client cues (camera pitch/yaw flick + close open screen) go through CoopNetwork
 * messages to {@link com.cooptest.client.SlapClientHandler}.
 */
public final class SlapHandler {

    private SlapHandler() {}

    private static final double SLAP_RANGE = 1.2;
    private static final double SNAP_DISTANCE = 0.9;
    private static final double BACK_THRESHOLD = 0.70;
    private static final double FRONT_THRESHOLD = -0.50;
    private static final double AIM_THRESHOLD = 0.80;
    private static final long IMPACT_DELAY_MS = 130L;
    private static final long FRONT_IMPACT_MS = 250L;
    private static final int ANIM_SLAP = 67;
    private static final int ANIM_SLAP_FRONT = 82;

    // ------------------------------------------------------------------ messages
    public record CameraFlickMsg(UUID playerId, float pitchDelta) {
        public static void encode(CameraFlickMsg m, FriendlyByteBuf buf) { buf.writeUUID(m.playerId); buf.writeFloat(m.pitchDelta); }
        public static CameraFlickMsg decode(FriendlyByteBuf buf) { return new CameraFlickMsg(buf.readUUID(), buf.readFloat()); }
        public static void handle(CameraFlickMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer())
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.SlapClientHandler.onCameraFlick(m.playerId(), m.pitchDelta()));
            });
            c.setPacketHandled(true);
        }
    }

    public record CameraYawFlickMsg(UUID playerId, float yawDelta) {
        public static void encode(CameraYawFlickMsg m, FriendlyByteBuf buf) { buf.writeUUID(m.playerId); buf.writeFloat(m.yawDelta); }
        public static CameraYawFlickMsg decode(FriendlyByteBuf buf) { return new CameraYawFlickMsg(buf.readUUID(), buf.readFloat()); }
        public static void handle(CameraYawFlickMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer())
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.SlapClientHandler.onCameraYawFlick(m.playerId(), m.yawDelta()));
            });
            c.setPacketHandled(true);
        }
    }

    public record ScreenCloseMsg(UUID playerId) {
        public static void encode(ScreenCloseMsg m, FriendlyByteBuf buf) { buf.writeUUID(m.playerId); }
        public static ScreenCloseMsg decode(FriendlyByteBuf buf) { return new ScreenCloseMsg(buf.readUUID()); }
        public static void handle(ScreenCloseMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer())
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.SlapClientHandler.onScreenClose(m.playerId()));
            });
            c.setPacketHandled(true);
        }
    }

    public static void register() { }

    public static void registerMessages() {
        CoopNetwork.register(CameraFlickMsg.class, CameraFlickMsg::encode, CameraFlickMsg::decode, CameraFlickMsg::handle);
        CoopNetwork.register(CameraYawFlickMsg.class, CameraYawFlickMsg::encode, CameraYawFlickMsg::decode, CameraYawFlickMsg::handle);
        CoopNetwork.register(ScreenCloseMsg.class, ScreenCloseMsg::encode, ScreenCloseMsg::decode, ScreenCloseMsg::handle);
    }

    // ------------------------------------------------------------------ detection
    public static boolean checkSlapOnRelease(ServerPlayer attacker) {
        if (!CoopMovesConfig.get().enableSlap) return false;
        Vec3 aEye = attacker.getEyePosition();
        Vec3 aLook = attacker.getViewVector(1.0f);
        ServerPlayer victim = null;
        double closest = SLAP_RANGE + 0.001;
        for (ServerPlayer candidate : attacker.serverLevel().players()) {
            if (candidate == attacker) continue;
            double dist = attacker.position().distanceTo(candidate.position());
            if (dist >= closest) continue;
            Vec3 victimLook = candidate.getViewVector(1.0f);
            double lookDot = aLook.dot(victimLook);
            boolean isBack = lookDot >= BACK_THRESHOLD;
            boolean isFront = lookDot <= FRONT_THRESHOLD;
            if (!isBack && !isFront) continue;
            Vec3 victimHead = candidate.position().add(0, 1.6, 0);
            Vec3 toHead = victimHead.subtract(aEye);
            if (toHead.length() < 0.01) continue;
            if (toHead.normalize().dot(aLook) < AIM_THRESHOLD) continue;
            victim = candidate;
            closest = dist;
        }
        if (victim == null) return false;
        if (aLook.dot(victim.getViewVector(1.0f)) <= FRONT_THRESHOLD) executeFrontSlap(attacker, victim);
        else executeSlap(attacker, victim);
        return true;
    }

    private static void executeSlap(ServerPlayer attacker, ServerPlayer victim) {
        ServerLevel world = attacker.serverLevel();
        Vec3 victimPos = victim.position();
        Vec3 victimFwd = victim.getViewVector(1.0f);
        Vec3 victimFwdH = new Vec3(victimFwd.x, 0, victimFwd.z);
        victimFwdH = victimFwdH.lengthSqr() < 0.001 ? new Vec3(1, 0, 0) : victimFwdH.normalize();
        Vec3 snapPos = victimPos.subtract(victimFwdH.scale(SNAP_DISTANCE));
        double safeY = snapPos.y;
        for (int dy = 0; dy <= 2; dy++) {
            BlockPos check = BlockPos.containing(snapPos.x, snapPos.y - dy, snapPos.z);
            if (!world.getBlockState(check).isAir()) { safeY = check.getY() + 1.0; break; }
        }
        snapPos = new Vec3(snapPos.x, safeY, snapPos.z);
        Vec3 diff = victimPos.subtract(snapPos);
        float yaw = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90f;
        attacker.teleportTo(world, snapPos.x, snapPos.y, snapPos.z, yaw, attacker.getXRot());
        attacker.setYRot(yaw); attacker.setYBodyRot(yaw); attacker.setYHeadRot(yaw); attacker.yBodyRotO = yaw;
        attacker.swing(InteractionHand.MAIN_HAND, true);
        PoseNetworking.broadcastAnimState(attacker, ANIM_SLAP);

        final UUID victimId = victim.getUUID();
        new Thread(() -> {
            try { Thread.sleep(IMPACT_DELAY_MS); } catch (InterruptedException ignored) {}
            attacker.getServer().execute(() -> {
                ServerPlayer v = attacker.getServer().getPlayerList().getPlayer(victimId);
                if (v == null) return;
                Vec3 hitPos = v.position().add(0, 1.7, 0);
                v.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 6, false, true));
                v.setYHeadRot(v.getYHeadRot() + 90f);
                CameraFlickMsg flick = new CameraFlickMsg(victimId, 70f);
                for (ServerPlayer p : attacker.getServer().getPlayerList().getPlayers()) CoopNetwork.sendToPlayer(p, flick);
                CoopNetwork.sendToPlayer(v, new ScreenCloseMsg(victimId));
                v.displayClientMessage(Component.literal("§c§l I like ya cut G"), true);
                attacker.displayClientMessage(Component.literal("§6§l SLAP!"), true);
                world.sendParticles(ParticleTypes.CRIT, hitPos.x, hitPos.y, hitPos.z, 10, 0.15, 0.1, 0.15, 0.15);
                world.sendParticles(ParticleTypes.SWEEP_ATTACK, hitPos.x, hitPos.y, hitPos.z, 4, 0.1, 0.05, 0.1, 0.05);
                world.sendParticles(ParticleTypes.ENCHANTED_HIT, hitPos.x, hitPos.y, hitPos.z, 6, 0.1, 0.1, 0.1, 0.08);
                world.playSound(null, hitPos.x, hitPos.y, hitPos.z, ModSounds.SLAP.get(), SoundSource.PLAYERS, 1.4f, 1.0f);
                world.playSound(null, hitPos.x, hitPos.y, hitPos.z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 0.8f, 2.0f);
            });
        }).start();
    }

    private static void executeFrontSlap(ServerPlayer attacker, ServerPlayer victim) {
        ServerLevel world = attacker.serverLevel();
        Vec3 diff = victim.position().subtract(attacker.position());
        float yaw = (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
        attacker.setYRot(yaw); attacker.setYBodyRot(yaw); attacker.setYHeadRot(yaw);
        attacker.swing(InteractionHand.MAIN_HAND, true);
        PoseNetworking.broadcastAnimState(attacker, ANIM_SLAP_FRONT);

        final UUID victimId = victim.getUUID();
        new Thread(() -> {
            try { Thread.sleep(FRONT_IMPACT_MS); } catch (InterruptedException ignored) {}
            attacker.getServer().execute(() -> {
                ServerPlayer v = attacker.getServer().getPlayerList().getPlayer(victimId);
                if (v == null) return;
                Vec3 hitPos = v.position().add(0, 1.7, 0);
                v.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 5, false, true));
                CameraYawFlickMsg yawFlick = new CameraYawFlickMsg(victimId, 45f);
                for (ServerPlayer p : attacker.getServer().getPlayerList().getPlayers()) CoopNetwork.sendToPlayer(p, yawFlick);
                CoopNetwork.sendToPlayer(v, new ScreenCloseMsg(victimId));
                world.sendParticles(ParticleTypes.CRIT, hitPos.x, hitPos.y, hitPos.z, 10, 0.15, 0.1, 0.15, 0.15);
                world.sendParticles(ParticleTypes.SWEEP_ATTACK, hitPos.x, hitPos.y, hitPos.z, 4, 0.1, 0.05, 0.1, 0.05);
                world.playSound(null, hitPos.x, hitPos.y, hitPos.z, ModSounds.SLAP.get(), SoundSource.PLAYERS, 1.4f, 0.9f);
                world.playSound(null, hitPos.x, hitPos.y, hitPos.z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 0.8f, 1.8f);
            });
        }).start();
    }
}
