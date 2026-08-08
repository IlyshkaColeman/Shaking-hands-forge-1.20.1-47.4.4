package com.cooptest.client;

import com.cooptest.CoopMoves;
import com.cooptest.CoopMovesConfig;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * Foundational KosmX animation primitive for the port.
 *
 * The Fabric mod used ZigyTheBird PlayerAnimationLib's controller/PlayState API.
 * KosmX classic (the library available on Forge 1.20.1) instead attaches a
 * per-player {@link ModifierLayer} and you play a keyframe animation by setting
 * that layer's animation. This class wraps that model so the rest of the port
 * (CoopAnimationHandler and the *ClientHandler classes) can call simple
 * play/stop helpers.
 *
 * Animation JSONs (bedrock/GeckoLib format) live under
 * assets/testcoop/player_animations/ and are auto-loaded by KosmX's
 * PlayerAnimationRegistry, keyed by their file name.
 *
 * NOTE: exact KosmX package/class names for the 1.20.1 Forge build (1.0.2-rc1)
 * are set here from the library docs; the CI build is the source of truth and
 * will flag any that differ so we correct them early — before the large
 * CoopAnimationHandler rewrite is built on top.
 */
public final class CoopAnim {

    private CoopAnim() {}

    /** Single animation layer id used by the mod (Fabric: testcoop:coop_animations). */
    public static final ResourceLocation LAYER_ID =
            new ResourceLocation(CoopMoves.NAMESPACE, "coop_animations");

    /** Registers the per-player modifier layer. Call once during client setup. */
    public static void registerFactory() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(
                LAYER_ID,
                1000,
                (player) -> new ModifierLayer<IAnimation>()
        );
        System.out.println("[CoopAnim] layer factory registered: " + LAYER_ID);
    }

    /** Returns the player's modifier layer, or null if not present yet. */
    @SuppressWarnings("unchecked")
    public static ModifierLayer<IAnimation> getLayer(AbstractClientPlayer player) {
        return (ModifierLayer<IAnimation>)
                PlayerAnimationAccess.getPlayerAssociatedData(player).get(LAYER_ID);
    }

    // ---- per-pose first-person configuration (which arms/items show in 1st person) ----
    // Only these interaction animations show the third-person arms in first person; every
    // other pose keeps vanilla first-person arms (FirstPersonMode.NONE). Mirrors the
    // Fabric FirstPersonAnimationTest interaction table.

    /** Right arm + right item (dap / high-five hit / perfect dap / fire dap family). */
    private static final Set<String> FP_RIGHT = Set.of(
            "dap_charge", "dap_charge_idle", "dap_hit", "dap_hit_weak", "dap_hit_bad", "dap_hit_face",
            "dap_high", "dapping", "dapping_end", "dap_down", "dap_loop", "dap_loop_end",
            "highfive_start", "highfive_wait", "highfive_hit", "highfive_dap",
            "perfect_dap_hit", "perfect_dap_hitp1", "perfect_dap_hitp2",
            "perfect_dap_extandp1", "perfect_dap_extandp2",
            "perfect_dap_extande_myboyp1", "perfect_dap_extande_myboyp2", "perfect_dap_extand_both",
            "perfect_dap_hitcombo", "perfect_dap_hitcombo_end",
            "fire_dap_charge", "fire_dap_charge_idle", "fire_dap_hit", "fire_dap_hit_perfect",
            "fire_dap_hitp1", "fire_dap_hitp2", "heaven_dap", "heave_dap");
    /** Right arm, no item (slap). */
    private static final Set<String> FP_RIGHT_NOITEM = Set.of("slap", "slap_front");
    /** Both arms, no item (kick / drop-kick). */
    private static final Set<String> FP_BOTH_NOITEM = Set.of("kick", "drop_kick");
    /** Both arms + items (grab/hold/throw, hug, push, clap, high-five combo). */
    private static final Set<String> FP_BOTH = Set.of(
            "grab_ready", "grab_ready_idle", "grab_holding", "grab_holding_charge", "grab_holding_charge_idle",
            "grab_throw", "hug_start", "hugging", "hugging2", "hugend", "highfive_hug", "highfive_hug2",
            "highfive_hitcombo", "push", "push_start", "push_idle", "clap", "clapspam", "clap_strong");

    private static final FirstPersonConfiguration CFG_RIGHT = new FirstPersonConfiguration(true, false, true, false);
    private static final FirstPersonConfiguration CFG_RIGHT_NOITEM = new FirstPersonConfiguration(true, false, false, false);
    private static final FirstPersonConfiguration CFG_BOTH_NOITEM = new FirstPersonConfiguration(true, true, false, false);
    private static final FirstPersonConfiguration CFG_BOTH = new FirstPersonConfiguration(true, true, true, true);

    private static boolean isFp(String path) {
        return FP_RIGHT.contains(path) || FP_RIGHT_NOITEM.contains(path)
                || FP_BOTH_NOITEM.contains(path) || FP_BOTH.contains(path);
    }

    private static FirstPersonConfiguration fpConfig(String path) {
        if (FP_RIGHT.contains(path)) return CFG_RIGHT;
        if (FP_RIGHT_NOITEM.contains(path)) return CFG_RIGHT_NOITEM;
        if (FP_BOTH_NOITEM.contains(path)) return CFG_BOTH_NOITEM;
        return CFG_BOTH;
    }

    /**
     * Plays the animation with the given id on the player (no-op if unavailable).
     * Interaction animations are wrapped so they render on the local player's arms in
     * first person with a per-pose arm/item configuration; other poses keep vanilla
     * first-person arms.
     */
    private static boolean loggedFirstPlay = false;

    public static void play(AbstractClientPlayer player, ResourceLocation animId) {
        ModifierLayer<IAnimation> layer = getLayer(player);
        if (layer == null) {
            System.out.println("[CoopAnim] NO LAYER for " + player.getGameProfile().getName()
                    + " (KosmX layer factory not attached / lib issue) when playing " + animId);
            return;
        }
        var anim = PlayerAnimationRegistry.getAnimation(animId);
        if (anim == null) {
            System.out.println("[CoopAnim] ANIMATION NOT FOUND in KosmX registry: " + animId
                    + " (check assets/testcoop/player_animation/" + animId.getPath() + ".json)");
            return;
        }
        String path = animId.getPath();
        FirstPersonMode mode = CoopMovesConfig.get().enableFirstPersonAnimations && isFp(path)
                ? FirstPersonMode.THIRD_PERSON_MODEL : FirstPersonMode.NONE;
        layer.setAnimation(new FpAnimationPlayer(anim, mode, fpConfig(path)));
        if (!loggedFirstPlay) {
            loggedFirstPlay = true;
            System.out.println("[CoopAnim] first animation applied OK: " + animId + " (mode=" + mode + ")");
        }
    }

    /** Stops any animation currently playing on the player's layer. */
    public static void stop(AbstractClientPlayer player) {
        ModifierLayer<IAnimation> layer = getLayer(player);
        if (layer != null) layer.setAnimation(null);
    }

    /** True while the KosmX layer still has an active animation player. */
    public static boolean isActive(AbstractClientPlayer player) {
        ModifierLayer<IAnimation> layer = getLayer(player);
        return layer != null && layer.isActive();
    }
}
