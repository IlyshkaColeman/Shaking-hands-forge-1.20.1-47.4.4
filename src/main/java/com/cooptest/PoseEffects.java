package com.cooptest;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Small server-side particle/sound helpers for the push family. Ported from Fabric
 * to Forge 1.20.1 (ServerWorld->ServerLevel, Vec3d->Vec3, SoundCategory->SoundSource,
 * spawnParticles->sendParticles, getYaw->getYRot, getVelocity/velocityModified->
 * getDeltaMovement/hurtMarked).
 */
public final class PoseEffects {

    private PoseEffects() {}

    public static void playIdleEffects(ServerPlayer player) {
        ServerLevel world = player.serverLevel();
        Vec3 pos = player.position();
        world.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0f, 1.2f);
    }

    public static void playActionEffects(ServerPlayer pusher, ServerPlayer target) {
        ServerLevel world = pusher.serverLevel();
        Vec3 pusherPos = pusher.position();
        Vec3 targetPos = target.position();
        float yaw = pusher.getYRot();
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians) * 0.8;
        double forwardZ = Math.cos(radians) * 0.8;
        double rightX = Math.cos(radians) * 0.3;
        double rightZ = Math.sin(radians) * 0.3;
        double leftX = -rightX;
        double leftZ = -rightZ;
        double handY = pusherPos.y + 1.0;
        double rightHandX = pusherPos.x + forwardX + rightX;
        double rightHandZ = pusherPos.z + forwardZ + rightZ;
        double leftHandX = pusherPos.x + forwardX + leftX;
        double leftHandZ = pusherPos.z + forwardZ + leftZ;
        world.playSound(null, pusherPos.x, pusherPos.y, pusherPos.z,
                SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.0f, 0.8f);
        world.playSound(null, pusherPos.x, pusherPos.y, pusherPos.z,
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0f, 1.0f);
        for (int i = 0; i < 5; i++) {
            world.sendParticles(ParticleTypes.CLOUD, rightHandX, handY, rightHandZ, 1, 0.1, 0.1, 0.1, 0.02);
        }
        for (int i = 0; i < 5; i++) {
            world.sendParticles(ParticleTypes.CLOUD, leftHandX, handY, leftHandZ, 1, 0.1, 0.1, 0.1, 0.02);
        }
        world.sendParticles(ParticleTypes.POOF, rightHandX, handY, rightHandZ, 3, 0.1, 0.1, 0.1, 0.02);
        world.sendParticles(ParticleTypes.POOF, leftHandX, handY, leftHandZ, 3, 0.1, 0.1, 0.1, 0.02);
        world.playSound(null, targetPos.x, targetPos.y, targetPos.z,
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.8f, 1.5f);
        world.sendParticles(ParticleTypes.POOF, targetPos.x, targetPos.y + 0.5, targetPos.z, 3, 0.2, 0.2, 0.2, 0.02);
        pusher.setDeltaMovement(pusher.getDeltaMovement().add(0, -0.15, 0));
        pusher.hurtMarked = true;
    }

    public static void playLaunchTrailEffects(ServerPlayer target) {
        ServerLevel world = target.serverLevel();
        Vec3 pos = target.position();
        world.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y + 0.5, pos.z, 2, 0.2, 0.2, 0.2, 0.02);
    }
}
