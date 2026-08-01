package com.cooptest.client;

import com.cooptest.CoopMoves;
import com.cooptest.GrabInputHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
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

            // Stage 4 (Grab group): client input + juice
            GrabInputHandler.register();
            GrabClientEffects.register();

            // Stage 4 (HighFive group): client input + feedback
            HighFiveClientHandler.register();

            // Stage 4 (self-contained mechanics)
            MarioJumpClientHandler.register();
            // Clap has no dedicated client handler — it is triggered from
            // GrabInputHandler (V) and animated via CoopAnimationHandler.

            // Stage 4: remaining *ClientHandler.register() calls
            // Stage 6: HUD overlays, world renderers
            CoopMoves.LOGGER.info("[CoopMoves] client setup complete");
        });
    }

    /** Keybind registration must happen on the mod event bus (client dist). */
    @SubscribeEvent
    public static void onRegisterKeyMappings(final RegisterKeyMappingsEvent event) {
        GrabInputHandler.registerKeyBindings(event);
        HighFiveClientHandler.registerKeyBindings(event);
    }
}
