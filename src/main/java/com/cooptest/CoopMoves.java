package com.cooptest;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Main mod entry point (Forge equivalent of the Fabric TestCoop ModInitializer).
 *
 * The original Fabric onInitialize() ordering is reproduced here as each subsystem
 * is ported. Registrations use DeferredRegister; server-side handlers and the
 * network channel are wired in later stages inside commonSetup().
 */
@Mod(CoopMoves.MOD_ID)
public class CoopMoves {

    /** Forge mod id (matches mods.toml). */
    public static final String MOD_ID = "coopmoves";
    /**
     * Content namespace used for all registry ids and assets. Kept identical to
     * the Fabric mod (which used "testcoop" everywhere) so sound/effect/potion
     * ids and asset paths do not change.
     */
    public static final String NAMESPACE = "testcoop";

    public static final Logger LOGGER = LogUtils.getLogger();

    public CoopMoves() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // --- Config (loaded early, same as Fabric CoopMovesConfig.load()) ---
        CoopMovesConfig.load();

        // --- Deferred registries (Stage 2) ---
        ModSounds.SOUND_EVENTS.register(modEventBus);
        ModEffects.MOB_EFFECTS.register(modEventBus);
        MahitoItems.POTIONS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreativeTabItems);

        // Forge game event bus: tick, commands, interactions, damage, etc. (Stage 3+)
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("[CoopMoves] constructed");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // --- Networking channel + message registration (order-sensitive) ---
            CoopNetwork.registerAll();

            // --- Server-side handlers, gated by config exactly like Fabric onInitialize() ---
            CoopMovesConfig cfg = CoopMovesConfig.get();
            if (cfg.enableGrab) {
                GrabMechanic.registerShieldDamageEvent();
                GrabInteractionHandler.register();
                if (cfg.enableSpin) SpinHandler.register();
            }
            if (cfg.enableHighFive) {
                HighFiveHandler.register();
            }
            if (cfg.enableMarioJump) {
                MarioJumpHandler.register();
            }
            if (cfg.enableClap) {
                ClapHandler.register();
            }
            if (cfg.enablePush) {
                PushInteractionHandler.register();
            }

            LOGGER.info("[CoopMoves] common setup complete");
        });
    }

    /** Adds the Mahito potion to the Food & Drink creative tab (Fabric: ItemGroupEvents on FOOD_AND_DRINK). */
    private void addCreativeTabItems(final BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FOOD_AND_DRINKS) {
            event.accept(MahitoItems.createMahitoPotion());
        }
    }
}
