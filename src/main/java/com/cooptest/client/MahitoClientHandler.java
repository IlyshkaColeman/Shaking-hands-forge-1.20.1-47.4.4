package com.cooptest.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client feedback for Mahito's Curse: plays the mahito animation on the cursed
 * player. Ported from Fabric to Forge 1.20.1 — the receiver is driven by
 * MahitoTrollHandler.MahitoAnimMsg via DistExecutor.
 */
@OnlyIn(Dist.CLIENT)
public final class MahitoClientHandler {

    private MahitoClientHandler() {}

    private static final Map<UUID, Long> mahitoStartTime = new HashMap<>();

    public static void register() { }

    public static void onMahitoAnim(UUID playerId) {
        mahitoStartTime.put(playerId, System.currentTimeMillis());
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            for (AbstractClientPlayer player : client.level.players()) {
                if (player.getUUID().equals(playerId)) {
                    CoopAnimationHandler.playMahitoAnimation(player);
                    break;
                }
            }
        }
    }

    public static boolean isBeingMahitod(UUID playerId) {
        return mahitoStartTime.containsKey(playerId);
    }

    public static void cleanup(UUID playerId) {
        mahitoStartTime.remove(playerId);
    }
}
