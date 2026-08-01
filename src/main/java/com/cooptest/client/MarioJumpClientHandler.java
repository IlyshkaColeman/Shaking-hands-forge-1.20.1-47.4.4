package com.cooptest.client;

import com.cooptest.MarioJumpHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

/**
 * Client detector for Mario-jump. Ported from Fabric to Forge 1.20.1.
 * On the rising edge of the vanilla jump key, if the local player is standing on
 * another player's head, sends a jump request; the server validates and executes.
 */
@OnlyIn(Dist.CLIENT)
public final class MarioJumpClientHandler {

    private MarioJumpClientHandler() {}

    private static boolean wasJumpPressed = false;

    public static void register() {
        MinecraftForge.EVENT_BUS.register(MarioJumpClientHandler.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        boolean isJumpPressed = client.options.keyJump.isDown();
        if (isJumpPressed && !wasJumpPressed && isOnPlayerHead(client)) {
            MarioJumpHandler.sendMarioJumpRequest();
        }
        wasJumpPressed = isJumpPressed;
    }

    private static boolean isOnPlayerHead(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return false;
        Vec3 playerPos = player.position();
        double playerFeetY = playerPos.y;
        AABB searchBox = new AABB(
                playerPos.x - 0.8, playerPos.y - 2.5, playerPos.z - 0.8,
                playerPos.x + 0.8, playerPos.y + 0.5, playerPos.z + 0.8);
        List<Player> nearby = client.level.getEntitiesOfClass(
                Player.class, searchBox, p -> p != player && p.isAlive());
        for (Player target : nearby) {
            Vec3 targetPos = target.position();
            double targetHeadY = targetPos.y + target.getEyeHeight() + 0.15;
            double heightDiff = playerFeetY - targetHeadY;
            if (heightDiff >= -0.35 && heightDiff <= 0.5) {
                double horizDist = Math.sqrt(
                        Math.pow(playerPos.x - targetPos.x, 2) + Math.pow(playerPos.z - targetPos.z, 2));
                if (horizDist <= 0.7) return true;
            }
        }
        return false;
    }
}
