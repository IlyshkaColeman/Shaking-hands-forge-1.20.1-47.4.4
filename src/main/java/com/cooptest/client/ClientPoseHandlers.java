package com.cooptest.client;

import com.cooptest.ArmPoseTracker;
import com.cooptest.CoopMoves;
import com.cooptest.PoseNetworking;
import com.cooptest.PoseState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * Client-side halves of the PoseNetworking message handlers.
 *
 * Kept in a separate @OnlyIn(CLIENT) class on purpose: PoseNetworking is loaded on
 * dedicated servers too, and referencing Minecraft/client classes directly from it
 * risks NoClassDefFoundError there. PoseNetworking reaches these methods only via
 * DistExecutor.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientPoseHandlers {

    private ClientPoseHandlers() {}

    private static Player findPlayer(UUID id) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return null;
        for (Player player : client.level.players()) {
            if (player.getUUID().equals(id)) return player;
        }
        return null;
    }

    public static void onPoseSync(UUID id, int poseOrdinal) {
        if (poseOrdinal < 0 || poseOrdinal >= PoseState.values().length) {
            CoopMoves.LOGGER.warn("Ignoring invalid pose ordinal {} from server", poseOrdinal);
            return;
        }
        PoseState state = PoseState.values()[poseOrdinal];
        PoseNetworking.poseStates.put(id, state);
        Player player = findPlayer(id);
        if (player != null) {
            CoopAnimationHandler.updatePlayerAnimation(player, state);
        }
    }

    public static void onChargeSync(UUID id, float progress) {
        PoseNetworking.chargeProgress.put(id, progress);
    }

    public static void onThrowAnim(UUID id) {
        ArmPoseTracker.throwAnimationStart.put(id, System.currentTimeMillis());
    }

    public static void onAnimStateSync(UUID id, int animState) {
        if (animState < 0 || animState >= CoopAnimationHandler.AnimState.values().length) {
            CoopMoves.LOGGER.warn("Ignoring invalid animation ordinal {} from server", animState);
            return;
        }
        if (animState == 0) {
            ChargedDapClientHandler.cleanup(id);
            CoopAnimationHandler.cleanup(id);
            HighFiveClientHandler.cleanup(id);
            PushClientHandler.cleanup(id);
            MahitoClientHandler.cleanup(id);
            FallDapClientHandler.cleanup(id);
            ArmPoseTracker.cleanup(id);
        }
        Player target = findPlayer(id);
        if (target != null) {
            CoopAnimationHandler.setAnimStateFromNetwork(target, animState);
        }
    }

    /** Client receiver for NormalFacingDapHandler's face-dap session flag. */
    public static void onFaceDapSession(boolean active) {
        ChargedDapClientHandler.setInFaceDapSession(active);
    }
}
