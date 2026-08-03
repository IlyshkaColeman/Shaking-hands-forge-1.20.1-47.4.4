package com.cooptest.client;

import com.cooptest.CoopMovesConfig;
import com.cooptest.CoopNetwork;
import com.cooptest.HuddleHandler;
import com.cooptest.PoseNetworking;
import com.cooptest.PoseState;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Client huddle input. Reads F directly (edge-triggered) and streams hold/release to
 * the server, which forms/dissolves huddles. Suppressed while in a hug animation or
 * the post-high-five hug window so F stays dedicated to hug there. Ported to Forge.
 */
@OnlyIn(Dist.CLIENT)
public final class HuddleClientHandler {

    private HuddleClientHandler() {}

    private static boolean fWasHeld = false;

    public static void register() {
        MinecraftForge.EVENT_BUS.register(HuddleClientHandler.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        if (!CoopMovesConfig.get().enableHug) return;

        var animSt = CoopAnimationHandler.getAnimState(client.player.getUUID());
        boolean inHugAnim = animSt == CoopAnimationHandler.AnimState.HUG_START
                || animSt == CoopAnimationHandler.AnimState.HUGGING
                || animSt == CoopAnimationHandler.AnimState.HUGGING2
                || animSt == CoopAnimationHandler.AnimState.HUG_END
                || animSt == CoopAnimationHandler.AnimState.HIGHFIVE_HUG
                || animSt == CoopAnimationHandler.AnimState.HIGHFIVE_HUG2;
        boolean inHighFiveWindow = animSt == CoopAnimationHandler.AnimState.HIGHFIVE_HIT
                || HighFiveClientHandler.isInHugOpportunityWindow();
        PoseState pose = PoseNetworking.poseStates.getOrDefault(client.player.getUUID(), PoseState.NONE);
        boolean blocked = pose == PoseState.GRAB_READY || pose == PoseState.GRAB_HOLDING || inHugAnim || inHighFiveWindow;
        if (blocked) {
            if (fWasHeld) { CoopNetwork.sendToServer(new HuddleHandler.HuddleFHoldMsg(false)); fWasHeld = false; }
            return;
        }

        long win = client.getWindow().getWindow();
        boolean fHeld = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_F) == GLFW.GLFW_PRESS;
        if (fHeld && !fWasHeld) { CoopNetwork.sendToServer(new HuddleHandler.HuddleFHoldMsg(true)); fWasHeld = true; }
        else if (!fHeld && fWasHeld) { CoopNetwork.sendToServer(new HuddleHandler.HuddleFHoldMsg(false)); fWasHeld = false; }
    }
}
