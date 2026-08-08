package com.cooptest.client;

import com.cooptest.CoopMoves;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Forge replacement for the Fabric KeyboardInput movement-freeze mixin. */
@OnlyIn(Dist.CLIENT)
public final class ClientMovementHandler {

    private ClientMovementHandler() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.register(ClientMovementHandler.class);
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        LocalPlayer local = Minecraft.getInstance().player;
        if (local == null || event.getEntity() != local) return;

        boolean frozen = ChargedDapClientHandler.isPlayerFrozen()
                || ChargedDapClientHandler.isDapBadBlocking()
                || HighFiveClientHandler.isLocalPlayerFrozen()
                || DapHoldClientHandler.isFrozen(local.getUUID())
                || DivineFlamComboClient.isLocalPlayerInCombo()
                || HugClientHandler.isLocalPlayerInHug()
                || CoopAnimationHandler.isInHuddleAnim(local.getUUID());
        if (!frozen) return;

        Input input = event.getInput();
        input.leftImpulse = 0.0f;
        input.forwardImpulse = 0.0f;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
    }
}
