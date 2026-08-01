package com.cooptest;

import com.cooptest.client.CoopAnimationHandler;
import com.cooptest.client.GrabClientState;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ElytraItem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/**
 * Client input for grab / throw / human-shield. Ported from Fabric to Forge 1.20.1.
 *
 * Fabric API translations:
 *   KeyBindingHelper.registerKeyBinding      -> RegisterKeyMappingsEvent#register
 *   ClientTickEvents.END_CLIENT_TICK         -> TickEvent.ClientTickEvent (Phase.END)
 *   KeyBinding.isPressed()                   -> KeyMapping.isDown()
 *   client.options.<key>.isPressed()         -> Minecraft.options.<key>.isDown()
 *   ClientPlayNetworking.send(payload)        -> GrabNetworking.send* helpers
 *   player.hasVehicle()/getUuid()/isOnGround -> isPassenger()/getUUID()/onGround()
 *   getEquippedStack(CHEST)                   -> getItemBySlot(EquipmentSlot.CHEST)
 *   getMainHandStack()                        -> getMainHandItem()
 *
 * The Fabric ShieldMode client receiver is gone: shield-mode mirroring is already
 * handled by GrabMechanic.ShieldModeMsg -> GrabClientState.setShieldMode.
 *
 * STAGE 4 NOTE — this is the reduced grab/throw/shield/escape build. The branches
 * that dispatch to not-yet-ported mechanics (Spin, GroundPound, Kick, Clap) are
 * intentionally omitted and marked "STAGE 4:" below; they are restored when those
 * handlers land.
 */
@OnlyIn(Dist.CLIENT)
public final class GrabInputHandler {

    private GrabInputHandler() {}

    private static KeyMapping grabKey;
    private static KeyMapping throwKey;
    private static KeyMapping shieldKey;

    private static boolean wasGrabKeyPressed = false;
    private static boolean wasThrowKeyPressed = false;
    private static boolean wasSneakPressed = false;
    private static boolean wasJumpPressed = false;
    private static boolean wasShieldKeyPressed = false;

    private static boolean isChargingThrow = false;
    private static long throwChargeStartTime = 0;
    private static final long MAX_CHARGE_TIME_MS = 1500;
    private static float lastSentChargeProgress = -1f;

    /** Called from CoopMovesClient on RegisterKeyMappingsEvent (mod bus, client). */
    public static void registerKeyBindings(RegisterKeyMappingsEvent event) {
        grabKey = new KeyMapping(
                "key.coopmoves.grab", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.coopmoves");
        throwKey = new KeyMapping(
                "key.coopmoves.throw", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_T, "category.coopmoves");
        shieldKey = new KeyMapping(
                "key.coopmoves.shield", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.coopmoves");
        event.register(grabKey);
        event.register(throwKey);
        event.register(shieldKey);
    }

    /** Called from CoopMovesClient.onClientSetup — attaches the client tick handler. */
    public static void register() {
        MinecraftForge.EVENT_BUS.register(GrabInputHandler.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return;
        if (grabKey == null) return; // key mappings not registered yet

        UUID playerId = player.getUUID();
        PoseState pose = PoseNetworking.poseStates.getOrDefault(playerId, PoseState.NONE);

        // --- Elytra boost: jump while thrown/airborne with an elytra equipped ---
        boolean isJumpPressed = client.options.keyJump.isDown();
        if (isJumpPressed && !wasJumpPressed) {
            if (pose == PoseState.GRABBED && !player.isPassenger()) {
                if (player.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof ElytraItem) {
                    GrabNetworking.sendElytraBoostRequest();
                }
            }
        }
        wasJumpPressed = isJumpPressed;

        // --- Air control while thrown (not riding anyone) ---
        if (pose == PoseState.GRABBED && !player.isPassenger()) {
            float forward = 0f;
            float strafe = 0f;
            if (client.options.keyUp.isDown()) forward += 1f;
            if (client.options.keyDown.isDown()) forward -= 1f;
            if (client.options.keyLeft.isDown()) strafe += 1f;
            if (client.options.keyRight.isDown()) strafe -= 1f;
            if (Math.abs(forward) > 0.01f || Math.abs(strafe) > 0.01f) {
                GrabNetworking.sendAirMovement(forward, strafe);
            }
        }

        boolean isHolding = pose == PoseState.GRAB_HOLDING || GrabClientState.isHolding(playerId);
        boolean isBeingHeld = (pose == PoseState.GRABBED && player.isPassenger())
                || GrabClientState.isBeingHeld(playerId);

        // --- Grab key (R): toggle ready / drop held ---
        boolean isGrabKeyPressed = grabKey.isDown();
        if (isGrabKeyPressed && !wasGrabKeyPressed) {
            if (isHolding) {
                GrabNetworking.sendDropRequest();
            } else if (pose == PoseState.GRAB_READY) {
                PoseNetworking.poseStates.put(playerId, PoseState.NONE);
                PoseNetworking.sendPoseToServer(playerId, PoseState.NONE);
            } else if (pose == PoseState.NONE && handsEmpty(player)) {
                PoseNetworking.poseStates.put(playerId, PoseState.GRAB_READY);
                PoseNetworking.sendPoseToServer(playerId, PoseState.GRAB_READY);
            }
        }
        wasGrabKeyPressed = isGrabKeyPressed;

        // --- Shield key (V): toggle human-shield while holding ---
        boolean isShieldKeyPressed = shieldKey.isDown();
        if (isShieldKeyPressed && !wasShieldKeyPressed) {
            if (isHolding) {
                GrabNetworking.sendShieldToggle();
            }
            // STAGE 4: else -> Clap (ClapHandler) once the Clap group is ported.
        }
        wasShieldKeyPressed = isShieldKeyPressed;

        // --- Sneak: escape when being held ---
        // STAGE 4: the airborne spin / ground-pound branches (Spin + GroundPound
        // groups) attach here once those handlers are ported.
        boolean isSneakPressed = client.options.keyShift.isDown();
        boolean sneakJustPressed = isSneakPressed && !wasSneakPressed;
        if (sneakJustPressed && isBeingHeld) {
            GrabNetworking.sendEscapeRequest();
        }
        wasSneakPressed = isSneakPressed;

        // --- Throw key (T): charge & release while holding ---
        boolean isThrowKeyPressed = throwKey.isDown();
        boolean justThrowPressed = isThrowKeyPressed && !wasThrowKeyPressed;
        boolean justThrowReleased = !isThrowKeyPressed && wasThrowKeyPressed;
        if (isHolding) {
            if (justThrowPressed) {
                isChargingThrow = true;
                throwChargeStartTime = System.currentTimeMillis();
                lastSentChargeProgress = 0f;
                CoopAnimationHandler.startGrabCharge(player);
            } else if (isThrowKeyPressed && isChargingThrow) {
                float currentProgress = getThrowChargeProgress();
                GrabClientState.setChargeProgress(playerId, currentProgress);
                if (Math.abs(currentProgress - lastSentChargeProgress) >= 0.1f) {
                    PoseNetworking.sendChargeProgress(playerId, currentProgress);
                    lastSentChargeProgress = currentProgress;
                }
            } else if (justThrowReleased && isChargingThrow) {
                long chargeTime = System.currentTimeMillis() - throwChargeStartTime;
                float power = Math.min(1.0f, (float) chargeTime / MAX_CHARGE_TIME_MS);
                GrabNetworking.sendThrowRequest(power);
                isChargingThrow = false;
                CoopAnimationHandler.playThrowAnimation(player);
                ArmPoseTracker.throwAnimationStart.put(playerId, System.currentTimeMillis());
                PoseNetworking.sendThrowAnimation(playerId);
                GrabClientState.setChargeProgress(playerId, 0f);
                PoseNetworking.sendChargeProgress(playerId, -1f);
                lastSentChargeProgress = -1f;
            }
        } else {
            if (isChargingThrow) {
                GrabClientState.setChargeProgress(playerId, 0f);
                PoseNetworking.sendChargeProgress(playerId, -1f);
                lastSentChargeProgress = -1f;
                isChargingThrow = false;
            }
            // STAGE 4: KickClientHandler.handleKickTick(...) when free (Kick group).
        }
        wasThrowKeyPressed = isThrowKeyPressed;

        // --- Auto-cancel ready pose if the player draws an item ---
        if (pose == PoseState.GRAB_READY && !handsEmpty(player)) {
            PoseNetworking.poseStates.put(playerId, PoseState.NONE);
            PoseNetworking.sendPoseToServer(playerId, PoseState.NONE);
        }
    }

    private static boolean handsEmpty(LocalPlayer player) {
        return player.getMainHandItem().isEmpty();
    }

    public static float getThrowChargeProgress() {
        if (!isChargingThrow) return -1f;
        long chargeTime = System.currentTimeMillis() - throwChargeStartTime;
        return Math.min(1.0f, (float) chargeTime / MAX_CHARGE_TIME_MS);
    }

    public static float getChargeProgressFor(UUID playerId) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.player.getUUID().equals(playerId)) {
            return getThrowChargeProgress();
        }
        return PoseNetworking.chargeProgress.getOrDefault(playerId, -1f);
    }
}
