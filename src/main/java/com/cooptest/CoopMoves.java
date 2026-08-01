package com.cooptest;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Main mod entry point (Forge equivalent of the Fabric TestCoop ModInitializer).
 *
 * NOTE: This is the Stage 1 scaffold. Registrations for sounds/effects/items,
 * the network channel, server-side handlers and event subscriptions are added
 * in later stages. The original Fabric onInitialize() ordering is preserved in
 * commonSetup() as each subsystem is ported.
 */
@Mod(CoopMoves.MOD_ID)
public class CoopMoves {

    public static final String MOD_ID = "coopmoves";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CoopMoves() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // --- Config (loaded early, same as Fabric CoopMovesConfig.load()) ---
        CoopMovesConfig.load();

        // --- Deferred registries (Stage 2) ---
        // ModSounds.SOUND_EVENTS.register(modEventBus);
        // ModEffects.MOB_EFFECTS.register(modEventBus);
        // MahitoItems.ITEMS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);

        // Forge game event bus: tick, commands, interactions, damage, etc. (Stage 3+)
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("[CoopMoves] constructed");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // --- Networking (Stage 3): CoopNetwork.register(); ---
            // --- Server-side handler registration (Stage 4), gated by CoopMovesConfig ---
            LOGGER.info("[CoopMoves] common setup complete");
        });
    }
}
