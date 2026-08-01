package com.cooptest;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraftforge.network.NetworkEvent;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Central Forge networking channel — the equivalent of Fabric's per-payload
 * PayloadTypeRegistry + ServerPlayNetworking/ClientPlayNetworking.
 *
 * On Fabric each mechanic registered its own CustomPayloads (1.21 API). Here every
 * mechanic registers its message classes onto this single SimpleChannel via
 * {@link #register(Class, BiConsumer, Function, BiConsumer)}. Message ids are
 * assigned from a shared counter in registration order; because every side runs
 * the same registration code path (CoopNetwork.registerAll in common setup), the
 * ids line up between client and server.
 */
public final class CoopNetwork {

    private CoopNetwork() {}

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CoopMoves.NAMESPACE, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static int nextId = 0;

    /** Registers one message type on the shared channel. Direction is inferred from usage (bidirectional-safe). */
    public static <MSG> void register(Class<MSG> type,
                                      BiConsumer<MSG, FriendlyByteBuf> encoder,
                                      Function<FriendlyByteBuf, MSG> decoder,
                                      BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler) {
        CHANNEL.registerMessage(nextId++, type, encoder, decoder, handler);
    }

    // --- Send helpers (mirror Fabric ClientPlayNetworking.send / ServerPlayNetworking.send) ---

    /** Client -> server. */
    public static void sendToServer(Object msg) {
        CHANNEL.sendToServer(msg);
    }

    /** Server -> one client. */
    public static void sendToPlayer(ServerPlayer player, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    /** Server -> all clients tracking (and including) the given player's dimension. */
    public static void sendToAll(Object msg) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), msg);
    }

    /**
     * Registration entry point, called from common setup. Each mechanic's
     * registerPayloads() is invoked here in a fixed order (mirroring the Fabric
     * TestCoop.onInitialize order) so message ids match on both sides.
     * Populated as mechanics are ported in Stage 4.
     */
    public static void registerAll() {
        // Order mirrors the Fabric TestCoop.onInitialize() registration order.
        PoseNetworking.register();
        // Stage 4: GrabNetworking.register(); HighFiveHandler.register(); ...
    }
}
