package com.cooptest;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server tick loop — the Forge equivalent of the Fabric
 * ServerTickEvents.END_SERVER_TICK block in TestCoop.onInitialize().
 * Order and config gating are preserved.
 */
@Mod.EventBusSubscriber(modid = CoopMoves.MOD_ID)
public final class CoopServerTick {

    private CoopServerTick() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null) return;

        CoopMovesConfig cfg = CoopMovesConfig.get();

        if (cfg.enableGrab) {
            // Stage 4: if (cfg.enableGroundPound) GroundPoundHandler.tick(server);
            GrabMechanic.tick(server);
            if (cfg.enableSpin) SpinHandler.tick(server);
        }
        if (cfg.enableHighFive) {
            HighFiveHandler.tick(server);
        }
        // Stage 4 (Dap family):
        // if (cfg.enableDap) { ChargedDapHandler.checkTickSpeedRestore(server);
        //                      QTEManager.tick(server);
        //                      if (cfg.enableDapCombo) DapComboChain.tick(server); }

        if (cfg.enablePush && server.getTickCount() % 20 == 0) {
            PushInteractionHandler.cleanupExpiredImmunity();
        }
    }
}
