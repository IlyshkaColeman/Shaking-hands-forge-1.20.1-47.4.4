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
            // Stage 3: PoseNetworking client receiver + all *ClientHandler.register()
            // Stage 6: keybinds, HUD overlays, world renderers
            // Stage 7: KosmX animation handlers
            CoopMoves.LOGGER.info("[CoopMoves] client setup complete");
        });
    }
}
