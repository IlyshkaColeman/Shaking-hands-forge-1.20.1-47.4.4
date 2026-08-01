package com.cooptest;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Grab / throw / shield networking, ported from Fabric CustomPayloads to Forge
 * SimpleChannel messages. Wire format and handler logic unchanged.
 *
 * C2S: throw, drop, escape, elytra boost, air movement, shield toggle.
 * S2C: grab state broadcast.
 */
public class GrabNetworking {

    private static boolean isServerSide(NetworkEvent.Context c) {
        return c.getDirection().getReceptionSide().isServer();
    }

    /** Runs a server-side action for the sending player. */
    private static void onServer(NetworkEvent.Context c, java.util.function.Consumer<ServerPlayer> action) {
        ServerPlayer player = c.getSender();
        if (player != null) action.accept(player);
    }

    // ------------------------------------------------------------------ C2S messages

    public record ThrowRequestMsg(float power) {
        public static void encode(ThrowRequestMsg m, FriendlyByteBuf buf) { buf.writeFloat(m.power); }
        public static ThrowRequestMsg decode(FriendlyByteBuf buf) { return new ThrowRequestMsg(buf.readFloat()); }
        public static void handle(ThrowRequestMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> onServer(c, player -> {
                if (GrabMechanic.isHolding(player)) {
                    GrabMechanic.tryThrow(player, m.power());
                }
            }));
            c.setPacketHandled(true);
        }
    }

    public record DropRequestMsg() {
        public static void encode(DropRequestMsg m, FriendlyByteBuf buf) { }
        public static DropRequestMsg decode(FriendlyByteBuf buf) { return new DropRequestMsg(); }
        public static void handle(DropRequestMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> onServer(c, player -> {
                if (GrabMechanic.isHolding(player)) {
                    GrabMechanic.tryDrop(player);
                }
            }));
            c.setPacketHandled(true);
        }
    }

    public record EscapeRequestMsg() {
        public static void encode(EscapeRequestMsg m, FriendlyByteBuf buf) { }
        public static EscapeRequestMsg decode(FriendlyByteBuf buf) { return new EscapeRequestMsg(); }
        public static void handle(EscapeRequestMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> onServer(c, player -> {
                if (GrabMechanic.isBeingHeld(player)) {
                    GrabMechanic.tryEscape(player);
                }
            }));
            c.setPacketHandled(true);
        }
    }

    public record ElytraBoostRequestMsg() {
        public static void encode(ElytraBoostRequestMsg m, FriendlyByteBuf buf) { }
        public static ElytraBoostRequestMsg decode(FriendlyByteBuf buf) { return new ElytraBoostRequestMsg(); }
        public static void handle(ElytraBoostRequestMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> onServer(c, player ->
                    GrabMechanic.requestElytraBoost(player.getUUID())));
            c.setPacketHandled(true);
        }
    }

    public record AirMovementMsg(float forward, float strafe) {
        public static void encode(AirMovementMsg m, FriendlyByteBuf buf) {
            buf.writeFloat(m.forward);
            buf.writeFloat(m.strafe);
        }
        public static AirMovementMsg decode(FriendlyByteBuf buf) {
            return new AirMovementMsg(buf.readFloat(), buf.readFloat());
        }
        public static void handle(AirMovementMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> onServer(c, player ->
                    GrabMechanic.setAirMovementInput(player.getUUID(), m.forward(), m.strafe())));
            c.setPacketHandled(true);
        }
    }

    public record ShieldToggleMsg() {
        public static void encode(ShieldToggleMsg m, FriendlyByteBuf buf) { }
        public static ShieldToggleMsg decode(FriendlyByteBuf buf) { return new ShieldToggleMsg(); }
        public static void handle(ShieldToggleMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> onServer(c, GrabMechanic::toggleShieldMode));
            c.setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ S2C message

    public record GrabStateMsg(UUID holderUuid, UUID heldUuid, boolean isStart) {
        public static void encode(GrabStateMsg m, FriendlyByteBuf buf) {
            buf.writeUUID(m.holderUuid);
            buf.writeUUID(m.heldUuid);
            buf.writeBoolean(m.isStart);
        }
        public static GrabStateMsg decode(FriendlyByteBuf buf) {
            return new GrabStateMsg(buf.readUUID(), buf.readUUID(), buf.readBoolean());
        }
        public static void handle(GrabStateMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!isServerSide(c)) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.GrabClientNetworking.onGrabState(
                                    m.holderUuid(), m.heldUuid(), m.isStart()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ registration

    public static void register() {
        CoopNetwork.register(ThrowRequestMsg.class, ThrowRequestMsg::encode, ThrowRequestMsg::decode, ThrowRequestMsg::handle);
        CoopNetwork.register(DropRequestMsg.class, DropRequestMsg::encode, DropRequestMsg::decode, DropRequestMsg::handle);
        CoopNetwork.register(EscapeRequestMsg.class, EscapeRequestMsg::encode, EscapeRequestMsg::decode, EscapeRequestMsg::handle);
        CoopNetwork.register(ElytraBoostRequestMsg.class, ElytraBoostRequestMsg::encode, ElytraBoostRequestMsg::decode, ElytraBoostRequestMsg::handle);
        CoopNetwork.register(AirMovementMsg.class, AirMovementMsg::encode, AirMovementMsg::decode, AirMovementMsg::handle);
        CoopNetwork.register(ShieldToggleMsg.class, ShieldToggleMsg::encode, ShieldToggleMsg::decode, ShieldToggleMsg::handle);
        CoopNetwork.register(GrabStateMsg.class, GrabStateMsg::encode, GrabStateMsg::decode, GrabStateMsg::handle);
    }

    // ------------------------------------------------------------------ send helpers

    public static void sendThrowRequest(float power) { CoopNetwork.sendToServer(new ThrowRequestMsg(power)); }

    public static void sendDropRequest() { CoopNetwork.sendToServer(new DropRequestMsg()); }

    public static void sendEscapeRequest() { CoopNetwork.sendToServer(new EscapeRequestMsg()); }

    public static void sendElytraBoostRequest() { CoopNetwork.sendToServer(new ElytraBoostRequestMsg()); }

    public static void sendAirMovement(float forward, float strafe) {
        CoopNetwork.sendToServer(new AirMovementMsg(forward, strafe));
    }

    public static void sendShieldToggle() { CoopNetwork.sendToServer(new ShieldToggleMsg()); }

    public static void broadcastGrabState(MinecraftServer server, UUID holderUuid, UUID heldUuid, boolean isStart) {
        GrabStateMsg msg = new GrabStateMsg(holderUuid, heldUuid, isStart);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CoopNetwork.sendToPlayer(player, msg);
        }
    }
}
