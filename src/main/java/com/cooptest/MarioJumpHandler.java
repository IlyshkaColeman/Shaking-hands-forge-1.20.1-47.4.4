package com.cooptest;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Mario-jump: bounce off another player's head. Ported from Fabric to Forge 1.20.1.
 *
 * Self-contained — the client sends a request when the local player presses the
 * vanilla jump key while standing on another player's head. Anim ordinals travel
 * over the wire (MARIO_JUMP = 30, POP = 31).
 *
 * Fabric API translations follow the project template (ServerWorld->ServerLevel,
 * Vec3d->Vec3, Box->AABB, getVelocity/velocityModified->getDeltaMovement/hurtMarked,
 * getEntitiesByClass->getEntitiesOfClass, sendMessage->displayClientMessage,
 * CustomPayload->CoopNetwork message).
 */
public final class MarioJumpHandler {

    private MarioJumpHandler() {}

    private static final Map<UUID, Long> jumpCooldown = new HashMap<>();
    private static final long COOLDOWN_MS = 500;
    private static final Map<UUID, Long> marioAnimEnd = new HashMap<>();
    private static final Map<UUID, Long> popAnimEnd = new HashMap<>();
    private static final long MARIO_ANIM_DURATION_MS = 500;
    private static final long POP_ANIM_DURATION_MS = 417;
    private static final double LAUNCH_VELOCITY = 0.68;

    private static final int ANIM_MARIO_JUMP = 30;
    private static final int ANIM_POP = 31;

    public static void register() { }

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> marioIt = marioAnimEnd.entrySet().iterator();
        while (marioIt.hasNext()) {
            Map.Entry<UUID, Long> entry = marioIt.next();
            if (now >= entry.getValue()) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) PoseNetworking.broadcastAnimState(player, 0);
                marioIt.remove();
            }
        }
        Iterator<Map.Entry<UUID, Long>> popIt = popAnimEnd.entrySet().iterator();
        while (popIt.hasNext()) {
            Map.Entry<UUID, Long> entry = popIt.next();
            if (now >= entry.getValue()) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) PoseNetworking.broadcastAnimState(player, 0);
                popIt.remove();
            }
        }
    }

    private static void onMarioJumpRequest(ServerPlayer jumper) {
        if (jumper == null) return;
        UUID jumperId = jumper.getUUID();
        long now = System.currentTimeMillis();
        if (jumpCooldown.containsKey(jumperId) && now - jumpCooldown.get(jumperId) < COOLDOWN_MS) return;
        if (HighFiveHandler.isInBlockingState(jumperId)) return;
        ServerPlayer target = findPlayerBelow(jumper);
        if (target == null) return;
        executeMarioJump(jumper, target);
        jumpCooldown.put(jumperId, now);
    }

    private static ServerPlayer findPlayerBelow(ServerPlayer jumper) {
        ServerLevel world = jumper.serverLevel();
        Vec3 jumperPos = jumper.position();
        double jumperFeetY = jumperPos.y;
        AABB searchBox = new AABB(
                jumperPos.x - 0.8, jumperPos.y - 2.5, jumperPos.z - 0.8,
                jumperPos.x + 0.8, jumperPos.y + 0.5, jumperPos.z + 0.8);
        List<ServerPlayer> nearby = world.getEntitiesOfClass(
                ServerPlayer.class, searchBox, p -> p != jumper && p.isAlive());
        for (ServerPlayer target : nearby) {
            Vec3 targetPos = target.position();
            double targetHeadY = targetPos.y + target.getEyeHeight() + 0.15;
            double heightDiff = jumperFeetY - targetHeadY;
            if (heightDiff >= -0.35 && heightDiff <= 0.5) {
                double horizDist = Math.sqrt(
                        Math.pow(jumperPos.x - targetPos.x, 2) + Math.pow(jumperPos.z - targetPos.z, 2));
                if (horizDist <= 0.7) return target;
            }
        }
        return null;
    }

    private static void executeMarioJump(ServerPlayer jumper, ServerPlayer target) {
        ServerLevel world = jumper.serverLevel();
        Vec3 pos = jumper.position();
        long now = System.currentTimeMillis();
        Vec3 velocity = jumper.getDeltaMovement();
        jumper.setDeltaMovement(velocity.x, LAUNCH_VELOCITY, velocity.z);
        jumper.hurtMarked = true;
        PoseNetworking.broadcastAnimState(jumper, ANIM_MARIO_JUMP);
        PoseNetworking.broadcastAnimState(target, ANIM_POP);
        marioAnimEnd.put(jumper.getUUID(), now + MARIO_ANIM_DURATION_MS);
        popAnimEnd.put(target.getUUID(), now + POP_ANIM_DURATION_MS);
        world.playSound(null, pos.x, pos.y, pos.z,
                ModSounds.MARIO_JUMP.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
        jumper.displayClientMessage(Component.literal("§a WAHOO!"), true);
        target.displayClientMessage(Component.literal("§c BONK!"), true);
    }

    public static void cleanup(UUID playerId) {
        jumpCooldown.remove(playerId);
        marioAnimEnd.remove(playerId);
        popAnimEnd.remove(playerId);
    }

    // ------------------------------------------------------------------ networking

    public record MarioJumpRequestMsg() {
        public static void encode(MarioJumpRequestMsg m, FriendlyByteBuf buf) { }
        public static MarioJumpRequestMsg decode(FriendlyByteBuf buf) { return new MarioJumpRequestMsg(); }
        public static void handle(MarioJumpRequestMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player == null) return;
                if (!CoopMovesConfig.get().enableMarioJump) return;
                onMarioJumpRequest(player);
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(MarioJumpRequestMsg.class,
                MarioJumpRequestMsg::encode, MarioJumpRequestMsg::decode, MarioJumpRequestMsg::handle);
    }

    public static void sendMarioJumpRequest() {
        CoopNetwork.sendToServer(new MarioJumpRequestMsg());
    }
}
