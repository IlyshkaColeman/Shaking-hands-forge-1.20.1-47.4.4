package com.cooptest.client;

import com.cooptest.GrabInputHandler;
import com.cooptest.PoseNetworking;
import com.cooptest.PoseState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

/**
 * Client-side juice for grab / throw / land: charge sounds & camera shake, a hit
 * cue when grabbed, held-shake while the holder is fully charged, and a fading
 * landing shake. Ported from Fabric to Forge 1.20.1.
 *
 * API translations:
 *   ClientTickEvents.END_CLIENT_TICK -> TickEvent.ClientTickEvent (Phase.END)
 *   player.playSound(...)            -> Entity.playSound(SoundEvent, vol, pitch)
 *   getPitch/setPitch, getYaw/setYaw -> getXRot/setXRot, getYRot/setYRot
 *   hasVehicle()/getUuid()           -> isPassenger()/getUUID()
 *   client.world                     -> client.level
 *   NOTE_BLOCK_* are Holder<SoundEvent> in 1.20.1, hence .value().
 */
@OnlyIn(Dist.CLIENT)
public final class GrabClientEffects {

    private GrabClientEffects() {}

    private static final float CHARGE_SOUND_PITCH_MIN = 0.5f;
    private static final float CHARGE_SOUND_PITCH_MAX = 2.0f;
    private static final int CHARGE_SOUND_INTERVAL = 4;
    private static final float CHARGE_SHAKE_INTENSITY = 0.15f;
    private static final float HELD_SHAKE_INTENSITY = 0.3f;
    private static final float LANDING_SHAKE_INTENSITY = 1.5f;
    private static final long LANDING_SHAKE_DURATION_MS = 2000;

    private static int chargeSoundTicks = 0;
    private static boolean wasFullyCharged = false;
    private static boolean wasBeingHeld = false;
    private static boolean wasGrabbed = false;
    private static long landingShakeStartTime = 0;
    private static boolean isLandingShaking = false;

    /** Called from CoopMovesClient.onClientSetup. */
    public static void register() {
        MinecraftForge.EVENT_BUS.register(GrabClientEffects.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) return;

        UUID playerId = player.getUUID();
        PoseState pose = PoseNetworking.poseStates.getOrDefault(playerId, PoseState.NONE);

        float chargeProgress = GrabInputHandler.getThrowChargeProgress();
        boolean isCharging = chargeProgress >= 0f;
        if (isCharging && pose == PoseState.GRAB_HOLDING) {
            chargeSoundTicks++;
            if (chargeSoundTicks >= CHARGE_SOUND_INTERVAL) {
                float pitch = CHARGE_SOUND_PITCH_MIN +
                        (CHARGE_SOUND_PITCH_MAX - CHARGE_SOUND_PITCH_MIN) * chargeProgress;
                player.playSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.3f, pitch);
                chargeSoundTicks = 0;
            }
            if (chargeProgress >= 0.99f && !wasFullyCharged) {
                player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 2.0f);
                player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
                wasFullyCharged = true;
            }
            if (chargeProgress >= 0.99f) {
                float shakeX = (float) (Math.random() - 0.5) * CHARGE_SHAKE_INTENSITY;
                float shakeY = (float) (Math.random() - 0.5) * CHARGE_SHAKE_INTENSITY;
                player.setXRot(player.getXRot() + shakeX);
                player.setYRot(player.getYRot() + shakeY);
            }
        } else {
            chargeSoundTicks = 0;
            wasFullyCharged = false;
        }

        boolean isBeingHeld = pose == PoseState.GRABBED && player.isPassenger();
        if (isBeingHeld && !wasBeingHeld) {
            player.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0f, 0.8f);
        }
        if (isBeingHeld && player.getVehicle() != null) {
            UUID holderId = player.getVehicle().getUUID();
            float holderCharge = PoseNetworking.chargeProgress.getOrDefault(holderId, -1f);
            if (holderCharge >= 0.99f) {
                float shakeX = (float) (Math.random() - 0.5) * HELD_SHAKE_INTENSITY;
                float shakeY = (float) (Math.random() - 0.5) * HELD_SHAKE_INTENSITY;
                player.setXRot(player.getXRot() + shakeX);
                player.setYRot(player.getYRot() + shakeY);
            }
        }
        wasBeingHeld = isBeingHeld;

        boolean wasInGrabbedPose = wasGrabbed;
        boolean nowInNormalPose = pose == PoseState.NONE;
        if (wasInGrabbedPose && nowInNormalPose && !player.isPassenger()) {
            isLandingShaking = true;
            landingShakeStartTime = System.currentTimeMillis();
            player.playSound(SoundEvents.PLAYER_BIG_FALL, 1.0f, 1.0f);
        }
        wasGrabbed = (pose == PoseState.GRABBED);

        if (isLandingShaking) {
            long elapsed = System.currentTimeMillis() - landingShakeStartTime;
            if (elapsed < LANDING_SHAKE_DURATION_MS) {
                float fadeProgress = 1.0f - (elapsed / (float) LANDING_SHAKE_DURATION_MS);
                float intensity = LANDING_SHAKE_INTENSITY * fadeProgress;
                float shakeX = (float) (Math.random() - 0.5) * intensity;
                float shakeY = (float) (Math.random() - 0.5) * intensity;
                player.setXRot(player.getXRot() + shakeX);
                player.setYRot(player.getYRot() + shakeY);
            } else {
                isLandingShaking = false;
            }
        }
    }
}
