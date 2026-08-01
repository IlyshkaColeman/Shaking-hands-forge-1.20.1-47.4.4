package com.cooptest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * One dap "positioning" session — smoothly slides two players to face each other
 * at a target distance before a dap connects. Ported from Fabric to Forge 1.20.1.
 *
 * Pure vanilla API translations: ServerWorld->ServerLevel, Vec3d->Vec3,
 * getVelocity/velocityModified->setDeltaMovement/hurtMarked, getYaw/getPitch->
 * getYRot/getXRot, setYaw/setBodyYaw/setHeadYaw->setYRot/setYBodyRot/setYHeadRot,
 * prevYaw->yRotO, teleport(world,x,y,z,yaw,pitch)->teleportTo(level,...),
 * swingHand->swing.
 */
public final class DapSession {

    private final UUID playerAId;
    private final UUID playerBId;
    private Vec3 targetA;
    private Vec3 targetB;
    private final long startTime;
    private int tickCount;
    private boolean positioningComplete;
    private final double targetDistance;
    private final double lerpSpeed;
    private final DapType type;
    private Runnable onPositioningComplete;
    private boolean cancelled = false;

    public enum DapType { NORMAL_DAP, PERFECT_DAP, FIRE_DAP, FIRE_COMBO, DAP_HOLD }

    public DapSession(UUID playerA, UUID playerB, double targetDistance, DapType type) {
        this.playerAId = playerA;
        this.playerBId = playerB;
        this.targetDistance = targetDistance;
        this.type = type;
        this.startTime = System.currentTimeMillis();
        this.tickCount = 0;
        this.positioningComplete = false;
        this.lerpSpeed = (type == DapType.PERFECT_DAP) ? 0.95 : 0.75;
    }

    public void onComplete(Runnable callback) { this.onPositioningComplete = callback; }

    public void tick(MinecraftServer server) {
        if (cancelled) return;
        ServerPlayer playerA = server.getPlayerList().getPlayer(playerAId);
        ServerPlayer playerB = server.getPlayerList().getPlayer(playerBId);
        if (playerA == null || playerB == null || playerA.isRemoved() || playerB.isRemoved()) return;
        if (positioningComplete) { tickCount++; return; }
        if (tickCount > 100) { forceComplete(); return; }
        freezePlayers(playerA, playerB);
        computeTargets(playerA, playerB);
        smoothMoveToTargets(playerA, playerB);
        makeFaceEachOther(playerA, playerB);
        playerA.swing(InteractionHand.MAIN_HAND);
        playerB.swing(InteractionHand.MAIN_HAND);
        if (!positioningComplete) checkPositioningComplete(playerA, playerB);
        tickCount++;
    }

    private void freezePlayers(ServerPlayer playerA, ServerPlayer playerB) {
        playerA.setDeltaMovement(Vec3.ZERO);
        playerB.setDeltaMovement(Vec3.ZERO);
        playerA.hurtMarked = true;
        playerB.hurtMarked = true;
        playerA.fallDistance = 0;
        playerB.fallDistance = 0;
    }

    private void computeTargets(ServerPlayer playerA, ServerPlayer playerB) {
        Vec3 posA = playerA.position();
        Vec3 posB = playerB.position();
        Vec3 midpoint = posA.add(posB).scale(0.5);
        Vec3 direction = posB.subtract(posA);
        if (direction.length() < 0.001) direction = new Vec3(1, 0, 0);
        direction = direction.normalize();
        double halfDistance = targetDistance / 2.0;
        targetA = midpoint.subtract(direction.scale(halfDistance));
        targetB = midpoint.add(direction.scale(halfDistance));
        double targetY = Math.max(posA.y, posB.y);
        ServerLevel world = playerA.serverLevel();
        BlockPos groundPos = new BlockPos((int) midpoint.x, (int) targetY - 1, (int) midpoint.z);
        if (world.getBlockState(groundPos).isAir()) targetY = Math.min(posA.y, posB.y);
        targetA = new Vec3(targetA.x, targetY, targetA.z);
        targetB = new Vec3(targetB.x, targetY, targetB.z);
    }

    private void smoothMoveToTargets(ServerPlayer playerA, ServerPlayer playerB) {
        Vec3 currentA = playerA.position();
        Vec3 currentB = playerB.position();
        Vec3 newPosA = new Vec3(lerp(currentA.x, targetA.x, lerpSpeed), lerp(currentA.y, targetA.y, lerpSpeed), lerp(currentA.z, targetA.z, lerpSpeed));
        Vec3 newPosB = new Vec3(lerp(currentB.x, targetB.x, lerpSpeed), lerp(currentB.y, targetB.y, lerpSpeed), lerp(currentB.z, targetB.z, lerpSpeed));
        playerA.teleportTo(playerA.serverLevel(), newPosA.x, newPosA.y, newPosA.z, playerA.getYRot(), playerA.getXRot());
        playerB.teleportTo(playerB.serverLevel(), newPosB.x, newPosB.y, newPosB.z, playerB.getYRot(), playerB.getXRot());
    }

    private void makeFaceEachOther(ServerPlayer playerA, ServerPlayer playerB) {
        Vec3 posA = playerA.position();
        Vec3 posB = playerB.position();
        double dx = posB.x - posA.x;
        double dz = posB.z - posA.z;
        float yawA = (float) (Math.atan2(dz, dx) * 180 / Math.PI) - 90;
        float yawB = yawA + 180;
        playerA.setYRot(yawA); playerA.setYBodyRot(yawA); playerA.setYHeadRot(yawA); playerA.yRotO = yawA;
        playerB.setYRot(yawB); playerB.setYBodyRot(yawB); playerB.setYHeadRot(yawB); playerB.yRotO = yawB;
        playerA.teleportTo(playerA.serverLevel(), posA.x, posA.y, posA.z, yawA, playerA.getXRot());
        playerB.teleportTo(playerB.serverLevel(), posB.x, posB.y, posB.z, yawB, playerB.getXRot());
    }

    private void checkPositioningComplete(ServerPlayer playerA, ServerPlayer playerB) {
        double distA = playerA.position().distanceTo(targetA);
        double distB = playerB.position().distanceTo(targetB);
        double threshold = (type == DapType.PERFECT_DAP) ? 0.35 : 0.25;
        if (distA < threshold && distB < threshold) {
            positioningComplete = true;
            if (onPositioningComplete != null) onPositioningComplete.run();
            this.tickCount = 99;
        }
    }

    private void forceComplete() {
        if (!positioningComplete) {
            positioningComplete = true;
            if (onPositioningComplete != null) onPositioningComplete.run();
        }
    }

    private double lerp(double current, double target, double factor) {
        return current + (target - current) * factor;
    }

    public UUID getPlayerAId() { return playerAId; }
    public UUID getPlayerBId() { return playerBId; }
    public boolean isPositioningComplete() { return positioningComplete; }
    public void cancel() { cancelled = true; }
    public int getTickCount() { return tickCount; }
    public DapType getType() { return type; }
    public long getStartTime() { return startTime; }
    public Vec3 getTargetA() { return targetA; }
    public Vec3 getTargetB() { return targetB; }
}
