package com.cooptest.client;

import com.cooptest.ChargedDapHandler;
import com.cooptest.CoopMovesConfig;
import com.cooptest.CoopNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Charged-dap client input + light client state. Ported to Forge 1.20.1.
 *
 * The dap charge is a held key (default G) with an empty main hand: press sends
 * {@link ChargedDapHandler.ChargeStartMsg}, release sends
 * {@link ChargedDapHandler.ChargeReleaseMsg}. A second key (default J) sends
 * {@link ChargedDapHandler.FireDapJPressMsg} for the fire-dap combo. Charge/fire
 * progress and heaven-ready flags arrive via S2C and are stored here for a future
 * HUD overlay; the full charge-bar/QTE HUD is not yet ported.
 */
@OnlyIn(Dist.CLIENT)
public final class ChargedDapClientHandler {

    private ChargedDapClientHandler() {}

    private static KeyMapping dapKey;
    private static KeyMapping fireComboKey;

    private static boolean wasDapKeyDown = false;
    private static boolean wasFireKeyDown = false;
    private static boolean localCharging = false;

    private static boolean inFaceDapSession = false;
    private static boolean playerFrozen = false;

    /** Charge progress broadcast from server, keyed by player. */
    public static final Map<UUID, Float> chargeProgress = new HashMap<>();
    public static final Map<UUID, Float> fireProgress = new HashMap<>();
    public static final Set<UUID> heavenReady = new HashSet<>();

    // -------------------------------------------------------------- registration
    public static void registerKeyBindings(RegisterKeyMappingsEvent event) {
        dapKey = new KeyMapping("key.coopmoves.dap", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.coopmoves");
        fireComboKey = new KeyMapping("key.coopmoves.firecombo", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.coopmoves");
        event.register(dapKey);
        event.register(fireComboKey);
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(ChargedDapClientHandler.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (dapKey == null) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!CoopMovesConfig.get().enableDap) return;

        // --- Dap charge (G): press to charge, release to dap ---
        boolean dapDown = dapKey.isDown();
        if (dapDown && !wasDapKeyDown) {
            if (player.getMainHandItem().isEmpty()) {
                localCharging = true;
                CoopNetwork.sendToServer(new ChargedDapHandler.ChargeStartMsg());
            }
        } else if (!dapDown && wasDapKeyDown) {
            if (localCharging) {
                localCharging = false;
                CoopNetwork.sendToServer(new ChargedDapHandler.ChargeReleaseMsg());
            }
        }
        wasDapKeyDown = dapDown;

        // --- Fire combo (J) ---
        boolean fireDown = fireComboKey.isDown();
        if (fireDown && !wasFireKeyDown) {
            CoopNetwork.sendToServer(new ChargedDapHandler.FireDapJPressMsg());
        }
        wasFireKeyDown = fireDown;
    }

    public static boolean isLocalPlayerCharging() { return localCharging; }

    public static void cleanup(UUID playerId) {
        chargeProgress.remove(playerId);
        fireProgress.remove(playerId);
        heavenReady.remove(playerId);
    }

    // -------------------------------------------------------------- S2C targets
    public static void onChargeSync(UUID playerId, float charge, float fire, boolean charging) {
        if (charging) {
            chargeProgress.put(playerId, charge);
            fireProgress.put(playerId, fire);
        } else {
            chargeProgress.remove(playerId);
            fireProgress.remove(playerId);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getUUID().equals(playerId) && !charging) {
            localCharging = false;
        }
    }

    public static void onHeavenReady(UUID playerId, boolean ready) {
        if (ready) heavenReady.add(playerId); else heavenReady.remove(playerId);
    }

    /** Set by ChargedDapHandler.PerfectDapFreezePayload (sit / perfect-dap freeze). */
    public static void onPerfectDapFreeze(boolean frozen) { playerFrozen = frozen; }

    /** Read by MovementFreezeMixin (Stage 5) to lock local movement. */
    public static boolean isPlayerFrozen() { return playerFrozen; }

    /** STAGE 6: facing-dap impact frame flash. */
    public static void onFacingDapImpact() { }

    public static void triggerDapBadBlock() { }

    public static void setInFaceDapSession(boolean active) { inFaceDapSession = active; }
    public static boolean isInFaceDapSession() { return inFaceDapSession; }
}
