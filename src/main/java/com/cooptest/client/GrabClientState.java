package com.cooptest.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Client-side mirror of grab / shield state, fed by the S2C messages.
 * The maps are real so renderers and input handlers can read them; the
 * remaining client behaviour lands with Stage 4/6.
 */
@OnlyIn(Dist.CLIENT)
public final class GrabClientState {

    private GrabClientState() {}

    /** holder -> held */
    public static final Map<UUID, UUID> holding = new HashMap<>();
    /** held -> holder */
    public static final Map<UUID, UUID> heldBy = new HashMap<>();
    /** holders currently in human-shield mode */
    public static final Set<UUID> shieldMode = new HashSet<>();
    /** client-tracked throw-charge progress per player (0..1) */
    public static final Map<UUID, Float> chargeProgress = new HashMap<>();

    public static void setGrabState(UUID holder, UUID held, boolean start) {
        if (start) {
            holding.put(holder, held);
            heldBy.put(held, holder);
        } else {
            holding.remove(holder);
            heldBy.remove(held);
            shieldMode.remove(holder);
            chargeProgress.remove(holder);
        }
    }

    public static void setShieldMode(UUID holder, UUID held, boolean enabled) {
        if (enabled) shieldMode.add(holder);
        else shieldMode.remove(holder);
    }

    public static boolean isInShieldMode(UUID holder) {
        return shieldMode.contains(holder);
    }

    /** True if the given player is currently holding someone (client mirror). */
    public static boolean isHolding(UUID playerUuid) {
        return holding.containsKey(playerUuid);
    }

    /** True if the given player is currently being held (client mirror). */
    public static boolean isBeingHeld(UUID playerUuid) {
        return heldBy.containsKey(playerUuid);
    }

    public static void setChargeProgress(UUID playerUuid, float progress) {
        chargeProgress.put(playerUuid, progress);
    }

    public static float getChargeProgress(UUID playerUuid) {
        return chargeProgress.getOrDefault(playerUuid, 0f);
    }

    public static void clear() {
        holding.clear();
        heldBy.clear();
        shieldMode.clear();
        chargeProgress.clear();
    }
}
