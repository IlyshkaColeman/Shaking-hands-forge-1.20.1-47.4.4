package com.cooptest.client;

import com.cooptest.HighFiveHandler;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client input & feedback for high-five. Ported from Fabric to Forge 1.20.1.
 *
 * STAGE 4 NOTE — reduced "basic high-five" build (matches the reduced server
 * HighFiveHandler). Press H with empty hands to raise; the pair connects
 * automatically in range. Deferred and restored with their groups:
 *   - Combo window (H+H) + HUD prompt          -> combo group + Stage 6 HUD
 *   - Sike (right-click bait)                   -> sike group
 *   - QTE / Fusion key routing                  -> QTE group
 *   - Hug-hold (F) trigger                      -> HighFiveHugHandler
 *   - Hug/huddle camera lock                    -> hug group
 *   - Tier flash / "ready" HUD overlay          -> Stage 6 (HUD)
 *
 * API translations: KeyBinding->KeyMapping (isPressed->isDown), ClientTickEvents
 * ->TickEvent.ClientTickEvent, ClientPlayNetworking receivers -> S2C message
 * handlers routed here via DistExecutor, swingHand->swing, getUuid->getUUID.
 */
@OnlyIn(Dist.CLIENT)
public final class HighFiveClientHandler {

    private HighFiveClientHandler() {}

    private static KeyMapping highFiveKey;
    private static boolean wasKeyPressed = false;

    private static long flashStartTime = 0;
    private static int currentTier = 0;

    private static final Map<UUID, Boolean> raisedHands = new HashMap<>();
    private static final Map<UUID, Long> highFiveAnimStart = new HashMap<>();
    public static final long HIGH_FIVE_ANIM_DURATION = 1458;

    /** Called from CoopMovesClient on RegisterKeyMappingsEvent (mod bus, client). */
    public static void registerKeyBindings(RegisterKeyMappingsEvent event) {
        highFiveKey = new KeyMapping(
                "key.coopmoves.highfive", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "category.coopmoves");
        event.register(highFiveKey);
    }

    /** Called from CoopMovesClient.onClientSetup — attaches the client tick handler. */
    public static void register() {
        MinecraftForge.EVENT_BUS.register(HighFiveClientHandler.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return;
        if (highFiveKey == null) return;

        UUID myId = player.getUUID();

        // local anim timeout -> lower hand / reset
        Long animStart = highFiveAnimStart.get(myId);
        if (animStart != null && System.currentTimeMillis() - animStart > HIGH_FIVE_ANIM_DURATION) {
            highFiveAnimStart.remove(myId);
            raisedHands.put(myId, false);
        }

        boolean isKeyPressed = highFiveKey.isDown();
        if (isKeyPressed && !wasKeyPressed) {
            // STAGE 4: QTE / Fusion / combo-window / hug-hold(F) routing goes here.
            if (!player.getMainHandItem().isEmpty()) {
                player.displayClientMessage(Component.literal("§cHands must be empty for high five!"), true);
            } else {
                raisedHands.put(myId, true);
                // STAGE 4: right-click held -> SikeRequest; here we always send a plain request.
                HighFiveHandler.sendHighFiveRequest();
            }
        }
        wasKeyPressed = isKeyPressed;
    }

    // ------------------------------------------------------------------ S2C receivers

    public static void onHandRaisedSync(UUID playerId, boolean raised) {
        raisedHands.put(playerId, raised);
    }

    public static void onHighFiveAnim(UUID playerId, int animState) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        for (AbstractClientPlayer p : client.level.players()) {
            if (p.getUUID().equals(playerId)) {
                switch (animState) {
                    case HighFiveHandler.ANIM_START -> CoopAnimationHandler.playHighFiveStart(p);
                    case HighFiveHandler.ANIM_END   -> CoopAnimationHandler.playHighFiveEnd(p);
                    case HighFiveHandler.ANIM_HIT   -> CoopAnimationHandler.playHighFiveHit(p);
                    case HighFiveHandler.ANIM_SIKE  -> CoopAnimationHandler.playHighFiveSike(p);
                }
                break;
            }
        }
    }

    public static void onHighFiveSuccess(double x, double y, double z, UUID player1, UUID player2, int tier) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        UUID myId = client.player.getUUID();
        raisedHands.put(player1, false);
        raisedHands.put(player2, false);
        long now = System.currentTimeMillis();
        highFiveAnimStart.put(player1, now);
        highFiveAnimStart.put(player2, now);
        if (myId.equals(player1) || myId.equals(player2)) {
            flashStartTime = now;
            currentTier = tier;
            client.player.swing(InteractionHand.MAIN_HAND);
            String message = switch (tier) {
                case 1 -> "§e Nice High Five! ";
                case 2 -> "§a§l BIG HIGH FIVE! ";
                case 3 -> "§c§l⚡ EXPLOSIVE HIGH FIVE! ⚡";
                default -> "§6 High Five!";
            };
            client.player.displayClientMessage(Component.literal(message), true);
        }
    }

    // ------------------------------------------------------------------ queries

    public static boolean hasHandRaised(UUID playerId) {
        return raisedHands.getOrDefault(playerId, false);
    }

    public static KeyMapping getHighFiveKey() {
        return highFiveKey;
    }

    public static boolean isLocalPlayerInHighFive() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return false;
        UUID myId = client.player.getUUID();
        if (highFiveKey != null && highFiveKey.isDown()) return true;
        if (raisedHands.getOrDefault(myId, false)) return true;
        Long animStart = highFiveAnimStart.get(myId);
        if (animStart != null) {
            if (System.currentTimeMillis() - animStart > HIGH_FIVE_ANIM_DURATION) {
                highFiveAnimStart.remove(myId);
                raisedHands.put(myId, false);
            } else {
                return true;
            }
        }
        return false;
    }

    public static float getHighFiveAnimProgress(UUID playerId) {
        Long startTime = highFiveAnimStart.get(playerId);
        if (startTime == null) return -1f;
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > HIGH_FIVE_ANIM_DURATION) {
            highFiveAnimStart.remove(playerId);
            return -1f;
        }
        return (float) elapsed / HIGH_FIVE_ANIM_DURATION;
    }

    public static void cleanup(UUID playerId) {
        raisedHands.remove(playerId);
        highFiveAnimStart.remove(playerId);
    }
}
