package com.cooptest;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Push mechanic: sneak + right-click a nearby player to charge a launch, then that
 * player right-clicks back to be flung upward (jump-boosted for extra height,
 * capped by the ceiling). Ported from Fabric to Forge 1.20.1.
 *
 * Fabric UseEntityCallback -> Forge PlayerInteractEvent.EntityInteract;
 * ServerTickEvents.END_SERVER_TICK -> CoopServerTick call; CustomPayload ->
 * CoopNetwork message. Other translations follow the project template
 * (ServerWorld->ServerLevel, Vec3d->Vec3, getVelocity/velocityModified->
 * getDeltaMovement/hurtMarked, isSneaking->isShiftKeyDown, sendMessage->
 * displayClientMessage, isSolidBlock->isSolidRender).
 */
public final class PushInteractionHandler {

    private PushInteractionHandler() {}

    private static final float  PUSH_RANGE       = 2.5f;
    private static final long   HOLD_REQUIRED_MS = 1500L;
    private static final long   READY_WINDOW_MS  = 3000L;
    private static final long   COOLDOWN_MS      = 1500L;
    private static final long   PUSH_IMMUNITY_MS = 500L;
    private static final long   JUMP_WINDOW_MS   = 800L;
    private static final double VEL_LOW    = 0.5;
    private static final double VEL_MEDIUM = 1.8;
    private static final double VEL_HIGH   = 3.5;

    private static final HashMap<UUID, UUID> holdTarget   = new HashMap<>();
    private static final HashMap<UUID, Long> holdStart    = new HashMap<>();
    private static final HashMap<UUID, UUID> readyPushers = new HashMap<>();
    private static final HashMap<UUID, Long> readyStart   = new HashMap<>();
    private static final HashMap<UUID, Long> cooldowns    = new HashMap<>();
    public  static final HashMap<UUID, Long> pushImmunity = new HashMap<>();
    public  static final HashMap<UUID, Long> lastJumpTime = new HashMap<>();

    // ------------------------------------------------------------------ lifecycle

    public static void register() {
        MinecraftForge.EVENT_BUS.register(PushInteractionHandler.class);
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!(event.getTarget() instanceof ServerPlayer target)) return;
        if (!CoopMovesConfig.get().enablePush) return;

        long now = System.currentTimeMillis();
        if (sp.isShiftKeyDown()) {
            if (HighFiveHandler.isInBlockingState(sp.getUUID())) return;
            if (isOnCooldown(sp.getUUID(), now)) return;
            if (readyPushers.containsKey(sp.getUUID())) return;
            if (sp.distanceTo(target) > PUSH_RANGE) return;
            UUID prevTarget = holdTarget.get(sp.getUUID());
            if (!target.getUUID().equals(prevTarget)) {
                holdTarget.put(sp.getUUID(), target.getUUID());
                holdStart.put(sp.getUUID(), now);
            }
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        UUID intendedTarget = readyPushers.get(target.getUUID());
        if (intendedTarget == null || !intendedTarget.equals(sp.getUUID())) return;
        Long rs = readyStart.get(target.getUUID());
        if (rs == null || now - rs > READY_WINDOW_MS) return;
        if (isOnCooldown(target.getUUID(), now)) return;
        double vel;
        Long jt = lastJumpTime.get(sp.getUUID());
        boolean recentJump = jt != null && (now - jt) < JUMP_WINDOW_MS;
        if      (recentJump)        vel = capToCeiling(sp, VEL_HIGH);
        else if (sp.isShiftKeyDown()) vel = capToCeiling(sp, VEL_LOW);
        else                        vel = capToCeiling(sp, VEL_MEDIUM);
        readyPushers.remove(target.getUUID());
        readyStart.remove(target.getUUID());
        executePush(target, sp, vel, now);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (var entry : new HashMap<>(holdTarget).entrySet()) {
            UUID pusherId = entry.getKey();
            UUID targetId = entry.getValue();
            Long startMs = holdStart.get(pusherId);
            if (startMs == null) { holdTarget.remove(pusherId); continue; }
            ServerPlayer pusher = server.getPlayerList().getPlayer(pusherId);
            ServerPlayer target = server.getPlayerList().getPlayer(targetId);
            if (pusher == null || !pusher.isShiftKeyDown() || target == null
                    || pusher.distanceTo(target) > PUSH_RANGE) {
                holdTarget.remove(pusherId);
                holdStart.remove(pusherId);
                continue;
            }
            if (readyPushers.containsKey(pusherId)) {
                holdTarget.remove(pusherId);
                holdStart.remove(pusherId);
                continue;
            }
            if (now - startMs >= HOLD_REQUIRED_MS) {
                holdTarget.remove(pusherId);
                holdStart.remove(pusherId);
                readyPushers.put(pusherId, targetId);
                readyStart.put(pusherId, now);
                Vec3 mid = pusher.position().add(target.position()).scale(0.5);
                pusher.serverLevel().playSound(null, mid.x, mid.y, mid.z,
                        SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.0f, 1.8f);
                pusher.displayClientMessage(Component.literal("§eTell homie to right-click!"), true);
                target.displayClientMessage(Component.literal("§e[Right-click to launch!]"), true);
            }
        }

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.onGround() && p.getDeltaMovement().y > 0.08) lastJumpTime.put(p.getUUID(), now);
        }

        for (var re : new HashMap<>(readyPushers).entrySet()) {
            UUID pusherId = re.getKey();
            UUID intendedTarget = re.getValue();
            Long rs = readyStart.get(pusherId);
            if (rs == null || now - rs > READY_WINDOW_MS) continue;
            ServerPlayer pusher = server.getPlayerList().getPlayer(pusherId);
            if (pusher == null) continue;
            for (ServerPlayer nearby : server.getPlayerList().getPlayers()) {
                if (nearby.getUUID().equals(pusherId)) continue;
                if (pusher.distanceTo(nearby) <= PUSH_RANGE) {
                    UUID nearbyId = nearby.getUUID();
                    if (!nearbyId.equals(intendedTarget)) {
                        readyPushers.put(pusherId, nearbyId);
                        nearby.displayClientMessage(Component.literal("§e[Right-click to launch!]"), true);
                    }
                    break;
                }
            }
        }

        readyPushers.entrySet().removeIf(e -> { Long t = readyStart.get(e.getKey()); return t == null || now - t > READY_WINDOW_MS; });
        readyStart.entrySet().removeIf(e -> now - e.getValue() > READY_WINDOW_MS);
        holdStart.entrySet().removeIf(e -> now - e.getValue() > 10000L);
        lastJumpTime.entrySet().removeIf(e -> now - e.getValue() > JUMP_WINDOW_MS * 4);
        cooldowns.entrySet().removeIf(e -> now - e.getValue() > COOLDOWN_MS * 2);
    }

    private static void executePush(ServerPlayer pusher, ServerPlayer target, double velocity, long now) {
        MinecraftServer server = pusher.getServer();
        PushAnimMsg pkt = new PushAnimMsg(pusher.getUUID());
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                CoopNetwork.sendToPlayer(p, pkt);
            }
        }
        target.setDeltaMovement(target.getDeltaMovement().x, 0, target.getDeltaMovement().z);
        target.setDeltaMovement(target.getDeltaMovement().add(0, velocity, 0));
        target.hurtMarked = true;
        pushImmunity.put(target.getUUID(), now);
        LaunchedPlayerTracker.markPlayerAsLaunched(target.getUUID());

        UUID carried = GrabMechanic.holding.get(target.getUUID());
        if (carried != null && server != null) {
            ServerPlayer c = server.getPlayerList().getPlayer(carried);
            if (c != null) {
                c.setDeltaMovement(c.getDeltaMovement().add(0, velocity * 0.85, 0));
                c.hurtMarked = true;
                LaunchedPlayerTracker.markPlayerAsLaunched(c.getUUID());
                pushImmunity.put(c.getUUID(), now);
            }
        }
        cooldowns.put(pusher.getUUID(), now);
        PoseNetworking.broadcastPoseChange(Objects.requireNonNull(pusher.getServer()),
                pusher.getUUID(), PoseState.PUSH_ACTION);
    }

    private static double capToCeiling(ServerPlayer t, double base) {
        BlockPos pos = t.blockPosition();
        for (int y = 1; y <= 15; y++) {
            BlockPos check = pos.above(y);
            BlockState state = t.level().getBlockState(check);
            if (!state.isAir() && state.isSolidRender(t.level(), check)) {
                return Math.min(base, Math.sqrt(2 * 0.08 * 20 * Math.max(2, y - 1)));
            }
        }
        return base;
    }

    private static boolean isOnCooldown(UUID uuid, long now) {
        Long t = cooldowns.get(uuid);
        return t != null && (now - t) < COOLDOWN_MS;
    }

    public static boolean hasPushImmunity(UUID uuid) {
        Long t = pushImmunity.get(uuid);
        if (t == null) return false;
        if (System.currentTimeMillis() - t < PUSH_IMMUNITY_MS) return true;
        pushImmunity.remove(uuid);
        return false;
    }

    public static void cleanupExpiredImmunity() {
        long now = System.currentTimeMillis();
        pushImmunity.entrySet().removeIf(e -> now - e.getValue() > PUSH_IMMUNITY_MS);
    }

    // ------------------------------------------------------------------ networking

    public record PushAnimMsg(UUID playerId) {
        public static void encode(PushAnimMsg m, FriendlyByteBuf buf) { buf.writeUUID(m.playerId); }
        public static PushAnimMsg decode(FriendlyByteBuf buf) { return new PushAnimMsg(buf.readUUID()); }
        public static void handle(PushAnimMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.PushClientHandler.onPushAnim(m.playerId()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(PushAnimMsg.class, PushAnimMsg::encode, PushAnimMsg::decode, PushAnimMsg::handle);
    }
}
