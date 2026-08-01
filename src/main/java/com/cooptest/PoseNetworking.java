package com.cooptest;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Pose / charge / animation-state synchronisation.
 *
 * Ported from the Fabric CustomPayload API (1.21) to Forge SimpleChannel messages.
 * Wire format, broadcast semantics and handler logic are unchanged.
 *
 * IMPORTANT: this class is loaded on dedicated servers, so it must not reference
 * client-only types. All client-side handling is delegated to
 * com.cooptest.client.ClientPoseHandlers via DistExecutor.
 */
public class PoseNetworking {

    public static final HashMap<UUID, PoseState> poseStates = new HashMap<>();
    public static final HashMap<UUID, Float> chargeProgress = new HashMap<>();

    private static boolean isServerSide(NetworkEvent.Context c) {
        return c.getDirection().getReceptionSide().isServer();
    }

    // ------------------------------------------------------------------ messages

    public record PoseSyncMsg(UUID playerId, int poseOrdinal) {
        public static void encode(PoseSyncMsg m, FriendlyByteBuf buf) {
            buf.writeUUID(m.playerId);
            buf.writeInt(m.poseOrdinal);
        }
        public static PoseSyncMsg decode(FriendlyByteBuf buf) {
            return new PoseSyncMsg(buf.readUUID(), buf.readInt());
        }
        public static void handle(PoseSyncMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (isServerSide(c)) {
                    handleServer(m, c);
                } else {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.ClientPoseHandlers.onPoseSync(m.playerId(), m.poseOrdinal()));
                }
            });
            c.setPacketHandled(true);
        }

        private static void handleServer(PoseSyncMsg m, NetworkEvent.Context c) {
            ServerPlayer sender = c.getSender();
            if (sender == null) return;
            MinecraftServer server = sender.getServer();
            if (server == null) return;

            UUID id = m.playerId();
            PoseState state = PoseState.values()[m.poseOrdinal()];
            ServerPlayer requester = server.getPlayerList().getPlayer(id);

            if (state == PoseState.GRAB_READY && HighFiveHandler.isInBlockingState(id)) {
                if (requester != null) {
                    CoopNetwork.sendToPlayer(requester, new PoseSyncMsg(id, PoseState.NONE.ordinal()));
                }
                return;
            }
            if (state == PoseState.PUSH_IDLE) {
                if (requester != null && !requester.getMainHandItem().isEmpty()) {
                    requester.displayClientMessage(
                            Component.literal("§cHold nothing in your main hand to push!"), true);
                    CoopNetwork.sendToPlayer(requester, new PoseSyncMsg(id, PoseState.NONE.ordinal()));
                    return;
                }
            }
            poseStates.put(id, state);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                CoopNetwork.sendToPlayer(p, new PoseSyncMsg(id, state.ordinal()));
            }
        }
    }

    public record ChargeSyncMsg(UUID playerId, float progress) {
        public static void encode(ChargeSyncMsg m, FriendlyByteBuf buf) {
            buf.writeUUID(m.playerId);
            buf.writeFloat(m.progress);
        }
        public static ChargeSyncMsg decode(FriendlyByteBuf buf) {
            return new ChargeSyncMsg(buf.readUUID(), buf.readFloat());
        }
        public static void handle(ChargeSyncMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (isServerSide(c)) {
                    ServerPlayer sender = c.getSender();
                    if (sender == null || sender.getServer() == null) return;
                    chargeProgress.put(m.playerId(), m.progress());
                    for (ServerPlayer p : sender.getServer().getPlayerList().getPlayers()) {
                        if (!p.getUUID().equals(m.playerId())) {
                            CoopNetwork.sendToPlayer(p, new ChargeSyncMsg(m.playerId(), m.progress()));
                        }
                    }
                } else {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.ClientPoseHandlers.onChargeSync(m.playerId(), m.progress()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    public record ThrowAnimMsg(UUID playerId) {
        public static void encode(ThrowAnimMsg m, FriendlyByteBuf buf) {
            buf.writeUUID(m.playerId);
        }
        public static ThrowAnimMsg decode(FriendlyByteBuf buf) {
            return new ThrowAnimMsg(buf.readUUID());
        }
        public static void handle(ThrowAnimMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (isServerSide(c)) {
                    ServerPlayer sender = c.getSender();
                    if (sender == null || sender.getServer() == null) return;
                    for (ServerPlayer p : sender.getServer().getPlayerList().getPlayers()) {
                        if (!p.getUUID().equals(m.playerId())) {
                            CoopNetwork.sendToPlayer(p, new ThrowAnimMsg(m.playerId()));
                        }
                    }
                } else {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.ClientPoseHandlers.onThrowAnim(m.playerId()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    public record AnimStateSyncMsg(UUID playerId, int animStateOrdinal) {
        public static void encode(AnimStateSyncMsg m, FriendlyByteBuf buf) {
            buf.writeUUID(m.playerId);
            buf.writeInt(m.animStateOrdinal);
        }
        public static AnimStateSyncMsg decode(FriendlyByteBuf buf) {
            return new AnimStateSyncMsg(buf.readUUID(), buf.readInt());
        }
        public static void handle(AnimStateSyncMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (isServerSide(c)) {
                    ServerPlayer sender = c.getSender();
                    if (sender == null || sender.getServer() == null) return;
                    for (ServerPlayer p : sender.getServer().getPlayerList().getPlayers()) {
                        if (!p.getUUID().equals(m.playerId())) {
                            CoopNetwork.sendToPlayer(p, new AnimStateSyncMsg(m.playerId(), m.animStateOrdinal()));
                        }
                    }
                } else {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.ClientPoseHandlers.onAnimStateSync(m.playerId(), m.animStateOrdinal()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ registration

    public static void register() {
        CoopNetwork.register(PoseSyncMsg.class, PoseSyncMsg::encode, PoseSyncMsg::decode, PoseSyncMsg::handle);
        CoopNetwork.register(ChargeSyncMsg.class, ChargeSyncMsg::encode, ChargeSyncMsg::decode, ChargeSyncMsg::handle);
        CoopNetwork.register(ThrowAnimMsg.class, ThrowAnimMsg::encode, ThrowAnimMsg::decode, ThrowAnimMsg::handle);
        CoopNetwork.register(AnimStateSyncMsg.class, AnimStateSyncMsg::encode, AnimStateSyncMsg::decode, AnimStateSyncMsg::handle);
    }

    // ------------------------------------------------------------------ send helpers

    public static void sendPoseToServer(UUID playerId, PoseState state) {
        CoopNetwork.sendToServer(new PoseSyncMsg(playerId, state.ordinal()));
    }

    public static void sendChargeProgress(UUID playerId, float progress) {
        CoopNetwork.sendToServer(new ChargeSyncMsg(playerId, progress));
    }

    public static void sendThrowAnimation(UUID playerId) {
        CoopNetwork.sendToServer(new ThrowAnimMsg(playerId));
    }

    public static void sendAnimState(UUID playerId, int animStateOrdinal) {
        CoopNetwork.sendToServer(new AnimStateSyncMsg(playerId, animStateOrdinal));
    }

    public static void broadcastPoseChange(MinecraftServer server, UUID playerId, PoseState state) {
        poseStates.put(playerId, state);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CoopNetwork.sendToPlayer(player, new PoseSyncMsg(playerId, state.ordinal()));
        }
    }

    public static void broadcastAnimState(ServerPlayer sourcePlayer, int animStateOrdinal) {
        MinecraftServer server = sourcePlayer.getServer();
        if (server == null) return;
        UUID playerId = sourcePlayer.getUUID();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CoopNetwork.sendToPlayer(player, new AnimStateSyncMsg(playerId, animStateOrdinal));
        }
    }
}
