package com.cooptest;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Small S2C bridge for stylized mechanic HUD messages. Use this instead of
 * vanilla action-bar text for short gameplay feedback that should look punchy.
 */
public final class MechanicHudText {

    private MechanicHudText() {}

    public static final int DANGER = 0;
    public static final int WARNING = 1;
    public static final int SUCCESS = 2;
    public static final int INFO = 3;
    public static final int EPIC = 4;

    public static void send(ServerPlayer player, String title, String subtitle, int style, long durationMs) {
        if (player == null) return;
        CoopNetwork.sendToPlayer(player, new HudTextMsg(title, subtitle, style, durationMs));
    }

    public static void danger(ServerPlayer player, String title, String subtitle) {
        send(player, title, subtitle, DANGER, 1450L);
    }

    public static void warning(ServerPlayer player, String title, String subtitle) {
        send(player, title, subtitle, WARNING, 1400L);
    }

    public static void success(ServerPlayer player, String title, String subtitle) {
        send(player, title, subtitle, SUCCESS, 1550L);
    }

    public static void info(ServerPlayer player, String title, String subtitle) {
        send(player, title, subtitle, INFO, 1350L);
    }

    public record HudTextMsg(String title, String subtitle, int style, long durationMs) {
        public static void encode(HudTextMsg m, FriendlyByteBuf buf) {
            buf.writeUtf(m.title, 80);
            buf.writeUtf(m.subtitle, 120);
            buf.writeInt(m.style);
            buf.writeLong(m.durationMs);
        }

        public static HudTextMsg decode(FriendlyByteBuf buf) {
            return new HudTextMsg(buf.readUtf(80), buf.readUtf(120), buf.readInt(), buf.readLong());
        }

        public static void handle(HudTextMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.MechanicHudTextClient.show(
                                    m.title(), m.subtitle(), m.style(), m.durationMs()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(HudTextMsg.class, HudTextMsg::encode, HudTextMsg::decode, HudTextMsg::handle);
    }
}
