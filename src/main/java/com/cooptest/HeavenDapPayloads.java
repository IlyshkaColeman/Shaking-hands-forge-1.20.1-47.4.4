package com.cooptest;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C signals for the Heaven Dap client sequence (screen overlay, sound ducking).
 * Ported from Fabric CustomPayloads to CoopNetwork messages. All are unit packets;
 * they are sent by ChargedDapHandler once the Heaven-dap core is ported. The actual
 * client overlay/volume handling lives in HeavenDapClientHandler (Stage 6).
 */
public final class HeavenDapPayloads {

    private HeavenDapPayloads() {}

    public record HeavenDapStartPayload() {
        public static void encode(HeavenDapStartPayload m, FriendlyByteBuf buf) { }
        public static HeavenDapStartPayload decode(FriendlyByteBuf buf) { return new HeavenDapStartPayload(); }
        public static void handle(HeavenDapStartPayload m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.HeavenDapClientHandler.onHeavenDapStart());
                }
            });
            c.setPacketHandled(true);
        }
    }

    public record HeavenDapEndPayload() {
        public static void encode(HeavenDapEndPayload m, FriendlyByteBuf buf) { }
        public static HeavenDapEndPayload decode(FriendlyByteBuf buf) { return new HeavenDapEndPayload(); }
        public static void handle(HeavenDapEndPayload m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.HeavenDapClientHandler.onHeavenDapEnd());
                }
            });
            c.setPacketHandled(true);
        }
    }

    public record RestoreVolumePayload() {
        public static void encode(RestoreVolumePayload m, FriendlyByteBuf buf) { }
        public static RestoreVolumePayload decode(FriendlyByteBuf buf) { return new RestoreVolumePayload(); }
        public static void handle(RestoreVolumePayload m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.HeavenDapClientHandler.onRestoreVolume());
                }
            });
            c.setPacketHandled(true);
        }
    }

    public record HeavenImpactPayload() {
        public static void encode(HeavenImpactPayload m, FriendlyByteBuf buf) { }
        public static HeavenImpactPayload decode(FriendlyByteBuf buf) { return new HeavenImpactPayload(); }
        public static void handle(HeavenImpactPayload m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.HeavenDapClientHandler.onHeavenImpact());
                }
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(HeavenDapStartPayload.class, HeavenDapStartPayload::encode, HeavenDapStartPayload::decode, HeavenDapStartPayload::handle);
        CoopNetwork.register(HeavenDapEndPayload.class, HeavenDapEndPayload::encode, HeavenDapEndPayload::decode, HeavenDapEndPayload::handle);
        CoopNetwork.register(RestoreVolumePayload.class, RestoreVolumePayload::encode, RestoreVolumePayload::decode, RestoreVolumePayload::handle);
        CoopNetwork.register(HeavenImpactPayload.class, HeavenImpactPayload::encode, HeavenImpactPayload::decode, HeavenImpactPayload::handle);
    }
}
