package com.cooptest;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Charged-Dap core — Forge port IN PROGRESS.
 *
 * The Fabric original is a ~2400-line monolith and the hub of the whole Dap family
 * (Fusion / Meteor / Combo / Facing / Heaven / Hold), which are ported
 * incrementally. This class is being grown in chunks; for now it hosts the shared
 * primitives other, already-ported mechanics need:
 *
 *   - {@link PerfectDapFreezePayload}: the S2C "freeze this client" toggle used by
 *     SitHandler (and later by the perfect-dap / combo sequences). The actual
 *     movement lock lands with MovementFreezeMixin in Stage 5; until then the
 *     client just records the flag and server-side tickers pin position.
 *
 * The charge/tier/QTE/fire/heaven logic will be ported into this class in
 * subsequent batches, replacing the query stubs below as each sub-system lands.
 */
public final class ChargedDapHandler {

    private ChargedDapHandler() {}

    // ------------------------------------------------------------------ query stubs
    // Consulted by other mechanics' guards. Return neutral values until the full
    // charged-dap state machine is ported (STAGE 4 — Dap core).

    public static boolean isCharging(UUID playerId) { return false; }
    public static boolean isFullyCharged(UUID playerId) { return false; }
    public static boolean isInComboCooldown(UUID playerId) { return false; }
    public static boolean isInBlockingAnimation(UUID playerId) { return false; }

    // ------------------------------------------------------------------ shared state
    // Real charge/tier state lives here once the core lands; exposed early because
    // sibling handlers (Facing / NormalFacing) already write cooldowns.

    /** playerId -> dap cooldown expiry (ms). */
    public static final Map<UUID, Long> cooldowns = new HashMap<>();

    /** playerId -> fire-dap charge level (0..1); consulted by FireSlapHandler. */
    public static final Map<UUID, Float> fireLevel = new HashMap<>();

    public static long cooldownMs() { return CoopMovesConfig.get().dapCooldownMs; }

    // ------------------------------------------------------------------ freeze primitive

    /** S2C: lock/unlock a client for a scripted sequence (sit, perfect dap, ...). */
    public record PerfectDapFreezePayload(boolean frozen) {
        public static void encode(PerfectDapFreezePayload m, FriendlyByteBuf buf) { buf.writeBoolean(m.frozen); }
        public static PerfectDapFreezePayload decode(FriendlyByteBuf buf) { return new PerfectDapFreezePayload(buf.readBoolean()); }
        public static void handle(PerfectDapFreezePayload m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.ChargedDapClientHandler.onPerfectDapFreeze(m.frozen()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    /** S2C: facing-dap impact frame cue (client visual). */
    public record FacingDapImpactPayload() {
        public static void encode(FacingDapImpactPayload m, FriendlyByteBuf buf) { }
        public static FacingDapImpactPayload decode(FriendlyByteBuf buf) { return new FacingDapImpactPayload(); }
        public static void handle(FacingDapImpactPayload m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.ChargedDapClientHandler.onFacingDapImpact());
                }
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(PerfectDapFreezePayload.class,
                PerfectDapFreezePayload::encode, PerfectDapFreezePayload::decode, PerfectDapFreezePayload::handle);
        CoopNetwork.register(FacingDapImpactPayload.class,
                FacingDapImpactPayload::encode, FacingDapImpactPayload::decode, FacingDapImpactPayload::handle);
    }
}
