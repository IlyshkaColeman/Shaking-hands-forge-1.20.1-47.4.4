package com.cooptest.client;

import com.cooptest.KickHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client input for kick / drop-kick. Ported from Fabric to Forge 1.20.1.
 *
 * Driven from GrabInputHandler (kick key = T when free-handed). Tap for a quick
 * kick; hold while sprinting to charge a drop-kick, release to fire.
 *
 * STAGE 6: the charge/cooldown/hit-flash HUD overlay (Fabric HudRenderCallback)
 * is deferred to the client-render stage; the input + cooldown logic is complete.
 */
@OnlyIn(Dist.CLIENT)
public final class KickClientHandler {

    private KickClientHandler() {}

    private static boolean wasHeld = false;
    private static boolean isCharging = false;
    private static long chargeStartMs = 0L;
    private static long cooldownEndMs = 0L;

    private static final Map<UUID, Float> otherCharges = new HashMap<>();
    private static final Map<UUID, Boolean> otherActive = new HashMap<>();

    private static long hitFlashUntil = 0L;
    private static boolean hitFlashDropKick = false;
    private static final long HIT_FLASH_MS = 180L;

    /** Hit-flash HUD overlay (registered from CoopMovesClient). */
    public static final IGuiOverlay HUD = (gui, g, partial, w, h) -> {
        long rem = hitFlashUntil - System.currentTimeMillis();
        if (rem <= 0) return;
        int alpha = (int) (rem / (float) HIT_FLASH_MS * (hitFlashDropKick ? 120 : 70));
        g.fill(0, 0, w, h, (alpha << 24) | (hitFlashDropKick ? 0xFF4400 : 0xFFFFFF));
    };

    public static void register() { }

    public static void handleKickTick(Minecraft client, boolean keyHeld, boolean sprinting) {
        boolean justPressed = keyHeld && !wasHeld;
        boolean justReleased = !keyHeld && wasHeld;
        if (justPressed && !isOnCooldown()) {
            if (sprinting) {
                isCharging = true;
                chargeStartMs = System.currentTimeMillis();
                KickHandler.sendKickStart(true);
            } else {
                KickHandler.sendKickStart(false);
            }
        }
        if (isCharging && !sprinting) {
            stopLocalCharge();
        }
        if (justReleased && isCharging) {
            KickHandler.sendKickRelease();
            stopLocalCharge();
        }
        if (justPressed && isOnCooldown() && client.player != null) {
            long rem = cooldownEndMs - System.currentTimeMillis();
            client.player.displayClientMessage(
                    Component.literal("§cKick cooldown! " + String.format("%.1f", rem / 1000.0) + "s"), true);
        }
        wasHeld = keyHeld;
    }

    public static void cancelIfCharging() {
        if (isCharging) {
            stopLocalCharge();
            wasHeld = false;
        }
    }

    private static void stopLocalCharge() {
        isCharging = false;
        chargeStartMs = 0L;
    }

    // ------------------------------------------------------------------ S2C receivers

    public static void onChargeSync(UUID playerId, boolean charging, float pct) {
        Minecraft client = Minecraft.getInstance();
        boolean isLocal = client.player != null && client.player.getUUID().equals(playerId);
        if (charging) {
            otherActive.put(playerId, true);
            otherCharges.put(playerId, pct);
        } else {
            otherActive.put(playerId, false);
            otherCharges.put(playerId, 0f);
            if (isLocal && isCharging) stopLocalCharge();
        }
    }

    public static void onCooldown(long cooldownMs) {
        stopLocalCharge();
        wasHeld = false;
        if (cooldownMs > 0) {
            cooldownEndMs = System.currentTimeMillis() + cooldownMs;
        }
    }

    public static void onResult(boolean isDropKick, boolean hit) {
        if (!hit) return;
        hitFlashUntil = System.currentTimeMillis() + HIT_FLASH_MS;
        hitFlashDropKick = isDropKick;
    }

    public static boolean isOnCooldown() { return System.currentTimeMillis() < cooldownEndMs; }

    public static boolean isLocalPlayerKickCharging() { return isCharging; }

    public static void cleanup() {
        stopLocalCharge();
        wasHeld = false;
        cooldownEndMs = 0L;
        otherCharges.clear();
        otherActive.clear();
    }
}
