package com.cooptest.client;

import com.cooptest.CoopMoves;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

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
    }

    /** Returns the player's modifier layer, or null if not present yet. */
    @SuppressWarnings("unchecked")
    public static ModifierLayer<IAnimation> getLayer(AbstractClientPlayer player) {
        return (ModifierLayer<IAnimation>)
                PlayerAnimationAccess.getPlayerAssociatedData(player).get(LAYER_ID);
    }

    /** Plays the animation with the given id on the player (no-op if unavailable). */
    public static void play(AbstractClientPlayer player, ResourceLocation animId) {
        ModifierLayer<IAnimation> layer = getLayer(player);
        if (layer == null) return;
        var anim = PlayerAnimationRegistry.getAnimation(animId);
        if (anim == null) return;
        layer.setAnimation(new KeyframeAnimationPlayer(anim));
    }

    /** Stops any animation currently playing on the player's layer. */
    public static void stop(AbstractClientPlayer player) {
        ModifierLayer<IAnimation> layer = getLayer(player);
        if (layer != null) layer.setAnimation(null);
    }
}
