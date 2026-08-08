package com.cooptest.client;

import com.cooptest.CoopMoves;
import com.cooptest.GrabInputHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
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
            ClientMovementHandler.register();

            // Stage 4 (Grab group): client input + juice
            GrabInputHandler.register();
            GrabClientEffects.register();

            // Stage 4 (HighFive group): client input + feedback
            HighFiveClientHandler.register();

            // Stage 4 (self-contained mechanics)
            MarioJumpClientHandler.register();
            SitClientHandler.register();
            PushClientHandler.register();
            CatchClientHandler.register();
            MahitoClientHandler.register();
            FallDapClientHandler.register();
            SlapClientHandler.register();
            // Stage 4 (Dap family): charge (G) / fire-combo (J) input
            ChargedDapClientHandler.register();
            DapHoldClientHandler.register();
            DivineFlamComboClient.register();
            // Stage 4 (HighFive-hug group): hold-to-hug (F) input
            HugClientHandler.register();
            // Stage 4 (Huddle group): group F-hold input
            HuddleClientHandler.register();
            // Stage 4 (Grab group): ground-pound client state
            GroundPoundClientHandler.register();
            // Heaven-dap white overlay driver
            HeavenDapClientHandler.register();
            // Spin (helicopter) client state
            SpinClientHandler.register();
            // Throw trajectory preview (world render)
            TrajectoryRenderer.register();
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
        ChargedDapClientHandler.registerKeyBindings(event);
        HugClientHandler.registerKeyBindings(event);
    }

    /** HUD overlays (mod event bus, client dist). */
    @SubscribeEvent
    public static void onRegisterOverlays(final RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("dap_hud", ChargedDapClientHandler.HUD);
        event.registerAboveAll("high_five_hud", HighFiveClientHandler.HUD);
        event.registerAboveAll("qte_hud", QTEClientHandler.HUD);
        event.registerAboveAll("fusion_hud", FusionClientHandler.HUD);
        event.registerAboveAll("sync_dap_hud", SyncDapClientHandler.HUD);
        event.registerAboveAll("ground_pound_hud", GroundPoundClientHandler.HUD);
        event.registerAboveAll("heaven_white_overlay", HeavenWhiteOverlay.HUD);
        event.registerAboveAll("spin_hud", SpinClientHandler.HUD);
        event.registerAboveAll("meteor_hud", MeteorStrikeClientHandler.HUD);
        event.registerAboveAll("kick_hit_flash", KickClientHandler.HUD);
        event.registerAboveAll("mechanic_text", MechanicHudTextClient.HUD);
    }

    /** Impact-silhouette core shaders (mod event bus, client dist). */
    @SubscribeEvent
    public static void onRegisterShaders(final RegisterShadersEvent event) {
        CoopImpactRenderType.onRegisterShaders(event);
    }
}
