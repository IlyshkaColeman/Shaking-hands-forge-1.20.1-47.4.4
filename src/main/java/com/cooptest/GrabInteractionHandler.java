package com.cooptest;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

/**
 * "Use on player to be grabbed" interaction. Ported from Fabric to Forge 1.20.1.
 *
 * Fabric used {@code UseEntityCallback.EVENT} (fires on right-click of an entity).
 * The Forge equivalent is {@link PlayerInteractEvent.EntityInteract}, which fires
 * on the same right-click-entity action for each hand.
 *
 * Semantics are preserved exactly: when player A right-clicks player B, and B is in
 * the GRAB_READY pose (hand out) while A is free, B grabs A —
 * i.e. {@code GrabMechanic.tryGrab(target=B, clicker=A)}. On success the interaction
 * is consumed so no other use-on-entity behaviour runs.
 */
public final class GrabInteractionHandler {

    private GrabInteractionHandler() {}

    /** Registers the interaction listener on the Forge game event bus. Called from common setup. */
    public static void register() {
        MinecraftForge.EVENT_BUS.register(GrabInteractionHandler.class);
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        // Server-authoritative only (Fabric: `if (world.isClient) return PASS;`).
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer clicker)) return;
        if (!(event.getTarget() instanceof ServerPlayer target)) return;

        UUID targetId = target.getUUID();
        UUID clickerId = clicker.getUUID();

        PoseState targetPose = PoseNetworking.poseStates.getOrDefault(targetId, PoseState.NONE);
        if (targetPose != PoseState.GRAB_READY) return;

        PoseState clickerPose = PoseNetworking.poseStates.getOrDefault(clickerId, PoseState.NONE);
        if (clickerPose == PoseState.GRAB_HOLDING || clickerPose == PoseState.GRABBED) return;

        if (target.distanceTo(clicker) > 3.0f) return;

        boolean success = GrabMechanic.tryGrab(target, clicker);
        if (success) {
            // Equivalent of Fabric ActionResult.SUCCESS: consume the interaction.
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }
}
