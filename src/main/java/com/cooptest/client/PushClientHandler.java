package com.cooptest.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client feedback for push: plays the push arm animation and swings the local
 * player's hand. Ported from Fabric to Forge 1.20.1 — the network receiver is
 * driven by PushInteractionHandler.PushAnimMsg via DistExecutor.
 */
@OnlyIn(Dist.CLIENT)
public final class PushClientHandler {

    private PushClientHandler() {}

    private static final Map<UUID, Long> pushAnimStart = new HashMap<>();
    private static final long PUSH_ANIM_DURATION = 400;

    public static void register() { }

    public static void onPushAnim(UUID playerId) {
        pushAnimStart.put(playerId, System.currentTimeMillis());
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            for (AbstractClientPlayer player : client.level.players()) {
                if (player.getUUID().equals(playerId)) {
                    CoopAnimationHandler.playPushAnimation(player);
                    break;
                }
            }
        }
        if (client.player != null && client.player.getUUID().equals(playerId)) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    public static float getPushAnimProgress(UUID playerId) {
        Long start = pushAnimStart.get(playerId);
        if (start == null) return -1f;
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed > PUSH_ANIM_DURATION) { pushAnimStart.remove(playerId); return -1f; }
        return (float) elapsed / PUSH_ANIM_DURATION;
    }

    public static void cleanup(UUID playerId) {
        pushAnimStart.remove(playerId);
    }
}
