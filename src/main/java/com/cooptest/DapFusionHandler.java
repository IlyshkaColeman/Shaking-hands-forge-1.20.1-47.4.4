package com.cooptest;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Dap Fusion core — Forge port IN PROGRESS (shell).
 *
 * The Fabric original is ~1200 lines. This shell currently hosts only the shared
 * {@link FusionQTEPayload} — the S2C "fusion QTE bar" packet that
 * PerfectDapComboHandler (and later the fusion core itself) sends to draw the
 * timing bar on the client. The full fusion state machine is ported in a later
 * batch; the client bar rendering lives in FusionClientHandler (Stage 6).
 */
public final class DapFusionHandler {

    private DapFusionHandler() {}

    /**
     * S2C: draw/update the fusion QTE bar.
     * mode 0 = simple window, 2 = moving-green timing bar; a negative period means
     * "oscillating" on the client.
     */
    public record FusionQTEPayload(java.util.UUID playerId, String button, int center,
                                   long halfWidth, long period, boolean active, int mode) {
        public static void encode(FusionQTEPayload m, FriendlyByteBuf buf) {
            buf.writeUUID(m.playerId);
            buf.writeUtf(m.button);
            buf.writeInt(m.center);
            buf.writeLong(m.halfWidth);
            buf.writeLong(m.period);
            buf.writeBoolean(m.active);
            buf.writeInt(m.mode);
        }
        public static FusionQTEPayload decode(FriendlyByteBuf buf) {
            return new FusionQTEPayload(buf.readUUID(), buf.readUtf(), buf.readInt(),
                    buf.readLong(), buf.readLong(), buf.readBoolean(), buf.readInt());
        }
        public static void handle(FusionQTEPayload m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.FusionClientHandler.onFusionQTE(
                                    m.playerId(), m.button(), m.center(), m.halfWidth(), m.period(), m.active(), m.mode()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(FusionQTEPayload.class,
                FusionQTEPayload::encode, FusionQTEPayload::decode, FusionQTEPayload::handle);
    }
}
