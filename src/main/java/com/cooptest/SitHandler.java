package com.cooptest;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Sit mechanic: /sit to sit down; a friend holds F nearby to help you up with a
 * scripted lift animation. Ported from Fabric to Forge 1.20.1.
 *
 * Translations: CommandRegistrationCallback -> RegisterCommandsEvent;
 * ServerCommandSource -> CommandSourceStack; ServerTickEvents -> CoopServerTick;
 * player.teleport(...) -> ServerPlayer.teleportTo(...); CustomPayload -> CoopNetwork
 * message; the freeze uses ChargedDapHandler.PerfectDapFreezePayload. AnimState
 * ordinals are hardcoded (SITTING=86, REACH_DOWN=87, REACH_PICKUP=88, STAND_UP=89)
 * because CoopAnimationHandler is client-only.
 */
public final class SitHandler {

    private SitHandler() {}

    private static final int ANIM_NONE = 0;
    private static final int ANIM_SITTING = 86;
    private static final int ANIM_REACH_DOWN = 87;
    private static final int ANIM_REACH_PICKUP = 88;
    private static final int ANIM_STAND_UP = 89;

    private static final Map<UUID, Double> sittingPlayers = new HashMap<>();
    private static final Map<UUID, UUID>   reachingSitter = new HashMap<>();
    private static final Set<String>       activePickup   = new HashSet<>();

    public static boolean isSitting(UUID id) { return sittingPlayers.containsKey(id); }

    // ------------------------------------------------------------------ lifecycle

    public static void register() {
        MinecraftForge.EVENT_BUS.register(SitHandler.class);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("sit").executes(SitHandler::executeSit));
    }

    public static void tick(MinecraftServer server) {
        for (Map.Entry<UUID, Double> e : new HashMap<>(sittingPlayers).entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            double sitY = e.getValue() - 0.5;
            p.setDeltaMovement(0, 0, 0);
            p.hurtMarked = true;
            if (Math.abs(p.getY() - sitY) > 0.05) {
                p.teleportTo(p.serverLevel(), p.getX(), sitY, p.getZ(), Set.of(), p.getYRot(), p.getXRot());
            }
        }
    }

    // ------------------------------------------------------------------ command

    public static int executeSit(CommandContext<CommandSourceStack> ctx) {
        Entity e = ctx.getSource().getEntity();
        if (!(e instanceof ServerPlayer player)) return 0;
        UUID id = player.getUUID();
        if (isSitting(id)) {
            if (!isInPickup(id)) standup(player);
        } else {
            sit(player);
        }
        return 1;
    }

    private static void sit(ServerPlayer player) {
        UUID id = player.getUUID();
        double originalY = player.getY();
        double sitY = originalY - 0.5;
        sittingPlayers.put(id, originalY);
        player.teleportTo(player.serverLevel(), player.getX(), sitY, player.getZ(), Set.of(), player.getYRot(), player.getXRot());
        CoopNetwork.sendToPlayer(player, new ChargedDapHandler.PerfectDapFreezePayload(true));
        PoseNetworking.broadcastAnimState(player, ANIM_SITTING);
        player.displayClientMessage(Component.literal("§7[Sitting — a friend can hold F to help you up]"), true);
    }

    private static void onFHold(ServerPlayer helper, boolean holding) {
        UUID hid = helper.getUUID();
        if (isSitting(hid)) return;
        if (!holding) {
            UUID sitterId = reachingSitter.remove(hid);
            if (sitterId == null) return;
            ServerPlayer sitter = helper.getServer().getPlayerList().getPlayer(sitterId);
            if (sitter == null || !isSitting(sitterId)) return;
            if (helper.distanceTo(sitter) > 1.5f) {
                PoseNetworking.broadcastAnimState(helper, ANIM_NONE);
                return;
            }
            startPickup(helper, sitter);
        } else {
            ServerPlayer nearest = null;
            double closest = 3.0;
            for (UUID sid : sittingPlayers.keySet()) {
                ServerPlayer s = helper.getServer().getPlayerList().getPlayer(sid);
                if (s != null && helper.distanceTo(s) < closest) { closest = helper.distanceTo(s); nearest = s; }
            }
            if (nearest == null) return;
            if (isInPickup(nearest.getUUID())) return;
            reachingSitter.put(hid, nearest.getUUID());
            PoseNetworking.broadcastAnimState(helper, ANIM_REACH_DOWN);
        }
    }

    private static void startPickup(ServerPlayer helper, ServerPlayer sitter) {
        UUID hid = helper.getUUID(), sid = sitter.getUUID();
        String k = hid + ":" + sid;
        final Double originalY = sittingPlayers.get(sid);
        final double sitY = originalY != null ? originalY - 0.5 : sitter.getY();
        activePickup.add(k);
        Vec3 diff = sitter.position().subtract(helper.position());
        float helperYaw = (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
        float sitterYaw = helperYaw + 180f;
        helper.setYRot(helperYaw); helper.setYBodyRot(helperYaw); helper.setYHeadRot(helperYaw);
        sitter.setYRot(sitterYaw); sitter.setYBodyRot(sitterYaw); sitter.setYHeadRot(sitterYaw);
        helper.swing(InteractionHand.MAIN_HAND, true);
        CoopNetwork.sendToPlayer(helper, new ChargedDapHandler.PerfectDapFreezePayload(true));
        CoopNetwork.sendToPlayer(sitter, new ChargedDapHandler.PerfectDapFreezePayload(true));
        PoseNetworking.broadcastAnimState(helper, ANIM_REACH_PICKUP);
        PoseNetworking.broadcastAnimState(sitter, ANIM_STAND_UP);
        MinecraftServer server = helper.getServer();

        schedule(server, 2880L, () -> {
            ServerPlayer h = server.getPlayerList().getPlayer(hid);
            ServerPlayer s = server.getPlayerList().getPlayer(sid);
            if (h == null || s == null) return;
            Vec3 dir = s.position().subtract(h.position()).normalize();
            Vec3 mid = h.position().add(0, 1.2, 0).add(dir.scale(0.5));
            h.serverLevel().playSound(null, mid.x, mid.y, mid.z, ModSounds.DAP_HIT.get(), SoundSource.PLAYERS, 1.2f, 0.8f);
            h.serverLevel().playSound(null, mid.x, mid.y, mid.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0f, 1.0f);
            h.serverLevel().sendParticles(ParticleTypes.CRIT, mid.x, mid.y, mid.z, 8, 0.15, 0.15, 0.15, 0.06);
            h.serverLevel().sendParticles(ParticleTypes.ENCHANTED_HIT, mid.x, mid.y, mid.z, 4, 0.1, 0.1, 0.1, 0.04);
        });

        final long LIFT_START_MS = 3880L, LIFT_END_MS = 5170L;
        final int LIFT_STEPS = 10;
        final long stepInterval = (LIFT_END_MS - LIFT_START_MS) / LIFT_STEPS;
        for (int i = 0; i <= LIFT_STEPS; i++) {
            final int step = i;
            long delay = LIFT_START_MS + step * stepInterval;
            schedule(server, delay, () -> {
                ServerPlayer s = server.getPlayerList().getPlayer(sid);
                if (s == null) return;
                if (step == 0) sittingPlayers.remove(sid);
                if (originalY == null) return;
                double t = (double) step / LIFT_STEPS;
                double liftY = sitY + (originalY - sitY) * t;
                s.teleportTo(s.serverLevel(), s.getX(), liftY, s.getZ(), Set.of(), s.getYRot(), s.getXRot());
            });
        }

        final double SITTER_PUSH_FORWARD = 0.2, HELPER_PUSH_BACKWARD = 0.2;
        schedule(server, 4290L, () -> {
            ServerPlayer h = server.getPlayerList().getPlayer(hid);
            ServerPlayer s = server.getPlayerList().getPlayer(sid);
            if (h == null || s == null) return;
            Vec3 dir2 = s.position().subtract(h.position()).normalize();
            Vec3 newHelperPos = h.position().add(dir2.scale(-HELPER_PUSH_BACKWARD));
            h.teleportTo(h.serverLevel(), newHelperPos.x, h.getY(), newHelperPos.z, Set.of(), h.getYRot(), h.getXRot());
            Vec3 newSitterPos = s.position().add(dir2.scale(-SITTER_PUSH_FORWARD));
            s.teleportTo(s.serverLevel(), newSitterPos.x, s.getY(), newSitterPos.z, Set.of(), s.getYRot(), s.getXRot());
        });

        schedule(server, 5200L, () -> {
            ServerPlayer h = server.getPlayerList().getPlayer(hid);
            ServerPlayer s = server.getPlayerList().getPlayer(sid);
            if (h != null) CoopNetwork.sendToPlayer(h, new ChargedDapHandler.PerfectDapFreezePayload(false));
            if (s != null) CoopNetwork.sendToPlayer(s, new ChargedDapHandler.PerfectDapFreezePayload(false));
        });

        schedule(server, 6100L, () -> {
            activePickup.remove(k);
            ServerPlayer h = server.getPlayerList().getPlayer(hid);
            ServerPlayer s = server.getPlayerList().getPlayer(sid);
            if (h != null) PoseNetworking.broadcastAnimState(h, ANIM_NONE);
            if (s != null) PoseNetworking.broadcastAnimState(s, ANIM_NONE);
        });
    }

    private static void standup(ServerPlayer player) {
        UUID id = player.getUUID();
        Double oy = sittingPlayers.remove(id);
        if (oy != null) {
            player.teleportTo(player.serverLevel(), player.getX(), oy, player.getZ(), Set.of(), player.getYRot(), player.getXRot());
        }
        CoopNetwork.sendToPlayer(player, new ChargedDapHandler.PerfectDapFreezePayload(false));
        PoseNetworking.broadcastAnimState(player, ANIM_NONE);
    }

    private static boolean isInPickup(UUID id) {
        return activePickup.stream().anyMatch(k -> k.contains(id.toString()));
    }

    private static void schedule(MinecraftServer server, long ms, Runnable r) {
        new Timer(true).schedule(new TimerTask() {
            @Override public void run() { server.execute(r); }
        }, ms);
    }

    public static void cleanup(UUID id) {
        sittingPlayers.remove(id);
        reachingSitter.remove(id);
        activePickup.removeIf(k -> k.contains(id.toString()));
    }

    // ------------------------------------------------------------------ networking

    public record SitFHoldMsg(boolean holding) {
        public static void encode(SitFHoldMsg m, FriendlyByteBuf buf) { buf.writeBoolean(m.holding); }
        public static SitFHoldMsg decode(FriendlyByteBuf buf) { return new SitFHoldMsg(buf.readBoolean()); }
        public static void handle(SitFHoldMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player != null) onFHold(player, m.holding());
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(SitFHoldMsg.class, SitFHoldMsg::encode, SitFHoldMsg::decode, SitFHoldMsg::handle);
    }

    public static void sendFHold(boolean holding) {
        CoopNetwork.sendToServer(new SitFHoldMsg(holding));
    }
}
