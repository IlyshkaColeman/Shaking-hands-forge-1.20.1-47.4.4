package com.cooptest.client;

import com.cooptest.CoopMoves;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client entry point (Forge equivalent of the Fabric TestCoopClient
 * ClientModInitializer). Stage 1 scaffold — client receivers, keybinds,
 * HUD overlays, renderers and animation handlers are wired in later stages.
 */
@Mod.EventBusSubscriber(modid = CoopMoves.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CoopMovesClient {

    private CoopMovesClient() {}

    @SubscribeEvent
    public static void onClientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // KosmX per-player animation layer (foundation for CoopAnimationHandler)
            CoopAnim.registerFactory();

            // Animation state machine (client tick hook)
            CoopAnimationHandler.register();

            // Stage 4: remaining *ClientHandler.register() calls
            // Stage 6: keybinds, HUD overlays, world renderers
            CoopMoves.LOGGER.info("[CoopMoves] client setup complete");
        });
    }
}
