package com.cooptest.client;

import com.cooptest.CoopMoves;
import com.cooptest.PoseNetworking;
import com.cooptest.PoseState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Central animation state machine, ported from the Fabric version.
 *
 * API translation (ZigyTheBird PAL -> KosmX playerAnimator):
 *   controller.triggerAnimation(ID)  ->  CoopAnim.play(player, ID)
 *   controller.stop()                ->  CoopAnim.stop(player)
 *   getController(p) != null         ->  CoopAnim.getLayer(p) != null
 * Event translation:
 *   ClientTickEvents.END_CLIENT_TICK ->  TickEvent.ClientTickEvent (phase END)
 *
 * All animation ids, AnimState ordinals (they travel over the network!), tick
 * durations and state-transition logic are preserved exactly as in the original.
 */
@OnlyIn(Dist.CLIENT)
public class CoopAnimationHandler {

    private static final String MOD_ID = CoopMoves.NAMESPACE;

    private static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    public static final ResourceLocation ANIMATION_LAYER_ID = CoopAnim.LAYER_ID;

    public static final ResourceLocation GRAB_HOLDING_ANIM = id("grab_holding");
    public static final ResourceLocation GRAB_HOLDING_CHARGE_ANIM = id("grab_holding_charge");
    public static final ResourceLocation GRAB_HOLDING_CHARGE_IDLE_ANIM = id("grab_holding_charge_idle");
    public static final ResourceLocation GRAB_THROW_ANIM = id("grab_throw");
    public static final ResourceLocation GRAB_READY_ANIM = id("grab_ready");
    public static final ResourceLocation GRAB_READY_IDLE_ANIM = id("grab_ready_idle");
    public static final ResourceLocation DAP_CHARGE_ANIM = id("dap_charge");
    public static final ResourceLocation DAP_CHARGE_IDLE_ANIM = id("dap_charge_idle");
    public static final ResourceLocation DAP_HIT_ANIM = id("dap_hit");
    public static final ResourceLocation FIRE_DAP_CHARGE_ANIM = id("fire_dap_charge");
    public static final ResourceLocation FIRE_DAP_CHARGE_IDLE_ANIM = id("fire_dap_charge_idle");
    public static final ResourceLocation FIRE_DAP_HIT_ANIM = id("fire_dap_hit");
    public static final ResourceLocation PUSH_START_ANIM = id("push_start");
    public static final ResourceLocation PUSH_IDLE_ANIM = id("push_idle");
    public static final ResourceLocation PUSH_ANIM = id("push");
    public static final ResourceLocation CATCH_ANIM = id("catch");
    public static final ResourceLocation MAHITO_ANIM = id("mahito");
    public static final ResourceLocation HIGHFIVE_START_ANIM = id("highfive_start");
    public static final ResourceLocation HIGHFIVE_END_ANIM = id("highfive_end");
    public static final ResourceLocation HIGHFIVE_HIT_ANIM = id("highfive_hit");
    public static final ResourceLocation HIGHFIVE_HIT_COMBO_ANIM = id("highfive_hitcombo");
    public static final ResourceLocation DAP_CHARGE_FALL_START_ANIM = id("dap_charge_fall_start");
    public static final ResourceLocation DAP_CHARGE_FALLING_ANIM = id("dap_charge_falling");
    public static final ResourceLocation DAP_CHARGE_FALL_HIT_ANIM = id("dap_charge_fall_hit");
    public static final ResourceLocation SQUASHED_ANIM = id("squashed");
    public static final ResourceLocation PERFECT_DAP_HIT_ANIM = id("perfect_dap_hit");
    public static final ResourceLocation DAP_DOWN_ANIM = id("dap_down");
    public static final ResourceLocation DAP_HIT_WEAK_ANIM = id("dap_hit_weak");
    public static final ResourceLocation PERFECT_DAP_EXTEND1_P1_ANIM = id("perfect_dap_extandp1");
    public static final ResourceLocation PERFECT_DAP_EXTEND1_P2_ANIM = id("perfect_dap_extandp2");
    public static final ResourceLocation PERFECT_DAP_MYBOY_P1_ANIM = id("perfect_dap_extande_myboyp1");
    public static final ResourceLocation PERFECT_DAP_MYBOY_P2_ANIM = id("perfect_dap_extande_myboyp2");
    public static final ResourceLocation PERFECT_DAP_EXTEND_BOTH_ANIM = id("perfect_dap_extand_both");
    public static final ResourceLocation HEAVE_DAP_ANIM = id("heave_dap");
    public static final ResourceLocation HOLD_SHIELD_ANIM = id("hold_shield");
    public static final ResourceLocation SHIELD_ANIM = id("shield");
    public static final ResourceLocation MARIO_JUMP_ANIM = id("mariojump");
    public static final ResourceLocation POP_ANIM = id("pop");
    public static final ResourceLocation HUG_START_ANIM = id("hug_start");
    public static final ResourceLocation HUGGING_ANIM = id("hugging");
    public static final ResourceLocation HUGGING2_ANIM = id("hugging2");
    public static final ResourceLocation HUG_END_ANIM = id("hugend");
    public static final ResourceLocation FIRE_DAP_HIT_PERFECT_ANIM = id("fire_dap_hit_perfect");
    public static final ResourceLocation FIRE_DAP_COMBO_P1_ANIM = id("fire_dap_hitp1");
    public static final ResourceLocation FIRE_DAP_COMBO_P2_ANIM = id("fire_dap_hitp2");
    public static final ResourceLocation DAPHOLD_HIGHFIVE_ANIM = id("highfive_dap");
    public static final ResourceLocation DAPHOLD_DAP_ANIM = id("dap_high");
    public static final ResourceLocation DAPHOLD_DAPPING_ANIM = id("dapping");
    public static final ResourceLocation DAPHOLD_DAPPING_END_ANIM = id("dapping_end");
    public static final ResourceLocation CLAP_ANIM = id("clap");
    public static final ResourceLocation CLAP_SPAM_ANIM = id("clapspam");
    public static final ResourceLocation CLAP_STRONG_ANIM = id("clap_strong");
    public static final ResourceLocation FUSION_START_P1_ANIM = id("fusion_startp1");
    public static final ResourceLocation FUSION_START_P2_ANIM = id("fusion_startp2");
    public static final ResourceLocation FUSION_HIT_P1_ANIM = id("fusion_hitp1");
    public static final ResourceLocation FUSION_HIT_P2_ANIM = id("fusion_hitp2");
    public static final ResourceLocation FUSION_IDLE_P1_ANIM = id("fusion_idlep1");
    public static final ResourceLocation FUSION_IDLE_P2_ANIM = id("fusion_idlep2");
    public static final ResourceLocation AURA_WALK_ANIM = id("walk_aura");
    public static final ResourceLocation HIGHFIVE_HUG_ANIM = id("highfive_hug");
    public static final ResourceLocation HIGHFIVE_HUG2_ANIM = id("highfive_hug2");
    public static final ResourceLocation KICK_ANIM = id("kick");
    public static final ResourceLocation DROP_KICK_ANIM = id("drop_kick");
    public static final ResourceLocation HIGHFIVE_SIKE_ANIM = id("highfive_sike");
    public static final ResourceLocation SPIN_ANIM = id("spin");
    public static final ResourceLocation GROUND_POUND_DIVE_ANIM = id("ground_pound_dive");
    public static final ResourceLocation GROUND_POUND_LAND_ANIM = id("ground_pound_land");
    public static final ResourceLocation SLAP_ANIM = id("slap");
    public static final ResourceLocation END_GROUP_ANIM = id("end_group");
    public static final ResourceLocation PERFECT_DAP_HIT_COMBO_ANIM = id("perfect_dap_hitcombo");
    public static final ResourceLocation PERFECT_DAP_HIT_COMBO_END_ANIM = id("perfect_dap_hitcombo_end");
    public static final ResourceLocation FACING_DAP_P1_ANIM = id("perfect_dap_hitp1");
    public static final ResourceLocation FACING_DAP_P2_ANIM = id("perfect_dap_hitp2");
    public static final ResourceLocation HUDDLE_START_ANIM = id("huddle_start");
    public static final ResourceLocation HUDDLE_IDLE_ANIM = id("huddle_idle");
    public static final ResourceLocation HUDDLE_QTE1_ANIM = id("huddle_qte1");
    public static final ResourceLocation HUDDLE_QTE2_ANIM = id("huddle_qte2");
    public static final ResourceLocation HUDDLE_QTE3_ANIM = id("huddle_qte3");
    public static final ResourceLocation LAY_DOWN_ANIM = id("lay_down");
    public static final ResourceLocation BONK_ANIM = id("bonk");
    public static final ResourceLocation DAP_HIT_FACE_ANIM = id("dap_hit_face");
    public static final ResourceLocation SLAP_FRONT_ANIM = id("slap_front");
    public static final ResourceLocation DAP_HIT_BAD_ANIM = id("dap_hit_bad");
    public static final ResourceLocation DAP_LOOP_ANIM = id("dap_loop");
    public static final ResourceLocation DAP_LOOP_END_ANIM = id("dap_loop_end");
    public static final ResourceLocation HEAVEN_DAP_ANIM = id("heaven_dap");
    public static final ResourceLocation SITTING_ANIM = id("sitting");
    public static final ResourceLocation REACH_DOWN_ANIM = id("reach_down");
    public static final ResourceLocation REACH_PICKUP_ANIM = id("reach_pickup");
    public static final ResourceLocation STAND_UP_ANIM = id("stand_up");
    public static final ResourceLocation HUDDLE_END_ANIM = id("huddle_end");

    private static final Map<UUID, PoseState> currentPoses = new HashMap<>();
    private static final Map<UUID, AnimState> animStates = new HashMap<>();

    /** Ordinals are sent over the network — order must not change. */
    public enum AnimState {
        NONE,
        GRAB_READY,
        GRAB_READY_IDLE,
        GRAB_HOLDING,
        GRAB_CHARGING,
        GRAB_CHARGE_IDLE,
        GRAB_THROWING,
        DAP_CHARGING,
        DAP_CHARGE_IDLE,
        DAP_HIT,
        FIRE_DAP_CHARGING,
        FIRE_DAP_CHARGE_IDLE,
        FIRE_DAP_HIT,
        PUSH_START,
        PUSH_IDLE,
        PUSHING,
        CATCHING,
        MAHITO,
        HIGHFIVE_START,
        HIGHFIVE_END,
        HIGHFIVE_HIT,
        HIGHFIVE_HIT_COMBO,
        DAP_CHARGE_FALL_START,
        DAP_CHARGE_FALLING,
        DAP_CHARGE_FALL_HIT,
        SQUASHED,
        PERFECT_DAP_HIT,
        DAP_DOWN,
        HOLD_SHIELD,
        SHIELD,
        MARIO_JUMP,
        POP,
        HUG_START,
        HUGGING,
        HUGGING2,
        HUG_END,
        FIRE_DAP_COMBO_P1,
        FIRE_DAP_COMBO_P2,
        DAPHOLD_HIGHFIVE,
        DAPHOLD_DAP,
        DAPHOLD_DAPPING,
        DAPHOLD_DAPPING_END,
        DAP_HIT_WEAK,
        PERFECT_DAP_EXTEND1_P1,
        PERFECT_DAP_EXTEND1_P2,
        PERFECT_DAP_MYBOY_P1,
        PERFECT_DAP_MYBOY_P2,
        PERFECT_DAP_EXTEND_BOTH,
        HEAVE_DAP,
        CLAP,
        CLAP_SPAM,
        CLAP_STRONG,
        FUSION_START_P1,
        FUSION_START_P2,
        FUSION_HIT_P1,
        FUSION_HIT_P2,
        FUSION_IDLE_P1,
        FUSION_IDLE_P2,
        AURA_WALK,
        HIGHFIVE_HUG,
        HIGHFIVE_HUG2,
        KICK,
        DROP_KICK,
        HIGHFIVE_SIKE,
        SPIN,
        GROUND_POUND_DIVE,
        GROUND_POUND_LAND,
        SLAP,
        END_GROUP,
        PERFECT_DAP_HIT_COMBO,
        HUDDLE_START,
        HUDDLE_IDLE,
        HUDDLE_QTE1,
        HUDDLE_END,
        PERFECT_DAP_HIT_COMBO_END,
        FACING_DAP_P1,
        FACING_DAP_P2,
        HUDDLE_QTE2,
        HUDDLE_QTE3,
        LAY_DOWN,
        BONK,
        DAP_HIT_FACE,
        SLAP_FRONT,
        DAP_HIT_BAD,
        DAP_LOOP,
        DAP_LOOP_END,
        SITTING,
        REACH_DOWN,
        REACH_PICKUP,
        STAND_UP,
        HEAVEN_DAP
    }

    public static void syncAnimState(UUID playerId, AnimState state) {
        animStates.put(playerId, state);
        PoseNetworking.sendAnimState(playerId, state.ordinal());
    }

    private static final Map<UUID, Long> chargeStartTime = new HashMap<>();

    private static final int DAP_CHARGE_DURATION_TICKS = 5;
    private static final int GRAB_CHARGE_DURATION_TICKS = 32;
    private static final int GRAB_READY_DURATION_TICKS = 5;
    private static final int PUSH_START_DURATION_TICKS = 9;
    private static final int THROW_ANIM_DURATION_TICKS = 6;
    private static final int HIGHFIVE_START_DURATION_TICKS = 7;
    private static final int HIGHFIVE_HIT_DURATION_TICKS = 29;
    private static final int HIGHFIVE_END_DURATION_TICKS = 30;
    private static final int FALL_CHARGE_DURATION_TICKS = 15;
    private static final int PERFECT_DAP_HIT_DURATION_TICKS = 33;
    private static final int DAP_DOWN_DURATION_TICKS = 7;
    private static final int FIRE_DAP_HIT_DURATION_TICKS = 46;
    private static final int DAP_HIT_DURATION_TICKS = 34;
    private static final int MARIO_JUMP_DURATION_TICKS = 10;
    private static final int CLAP_DURATION_TICKS = 8;
    private static final int CLAP_SPAM_DURATION_TICKS = 5;
    private static final int CLAP_STRONG_DURATION_TICKS = 3;
    private static final int FUSION_HIT_DURATION_TICKS = 12;
    private static final int HIGHFIVE_HUG_DURATION_TICKS = 88;
    private static final int HIGHFIVE_HUG2_DURATION_TICKS = 51;
    private static final int POP_DURATION_TICKS = 8;
    private static final int KICK_DURATION_TICKS = 20;
    private static final int DROP_KICK_DURATION_TICKS = 35;
    private static final int HIGHFIVE_SIKE_DURATION_TICKS = 29;
    private static final int GROUND_POUND_LAND_DURATION_TICKS = 10;
    private static final int SLAP_DURATION_TICKS = 19;
    private static final int END_GROUP_DURATION_TICKS = 78;
    private static final int PERFECT_DAP_HIT_COMBO_TICKS = 23;
    private static final int PERFECT_DAP_HIT_COMBO_END_TICKS = 10;
    private static final int FACING_DAP_P1_TICKS = 80;
    private static final int FACING_DAP_P2_TICKS = 82;
    private static final int HUDDLE_START_DURATION_TICKS = 11;
    private static final int HUDDLE_QTE1_DURATION_TICKS = 20;
    private static final int HUDDLE_QTE2_DURATION_TICKS = 20;
    private static final int HUDDLE_QTE3_DURATION_TICKS = 20;
    private static final int HUDDLE_END_DURATION_TICKS = 28;

    private static boolean initialized = false;

    public static void register() {
        try {
            // The per-player KosmX layer is registered by CoopAnim.registerFactory()
            // during client setup; here we only hook the client tick.
            initialized = true;
            FirstPersonAnimationTest.init();
            MinecraftForge.EVENT_BUS.register(CoopAnimationHandler.class);
        } catch (Exception e) {
            System.err.println("[CoopMoves] Failed to register animations: " + e.getMessage());
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            tick();
        }
    }

    /** True when this player has a usable animation layer (replaces the old null-controller check). */
    private static boolean hasLayer(AbstractClientPlayer player) {
        return CoopAnim.getLayer(player) != null;
    }

    private static long worldTime() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null ? client.level.getGameTime() : 0L;
    }

    private static boolean isLocal(UUID playerId) {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.player.getUUID().equals(playerId);
    }

    public static void updatePlayerAnimation(Player player, PoseState newPose) {
        if (!initialized) return;
        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;
        UUID playerId = player.getUUID();
        PoseState currentPose = currentPoses.get(playerId);
        if (currentPose == newPose) return;
        currentPoses.put(playerId, newPose);
        boolean isLocalPlayer = isLocal(playerId);
        try {
            if (!hasLayer(clientPlayer)) return;
            switch (newPose) {
                case GRABBED -> animStates.put(playerId, AnimState.NONE);
                case GRAB_READY -> {
                    CoopAnim.play(clientPlayer, GRAB_READY_ANIM);
                    if (isLocalPlayer) {
                        FirstPersonAnimationTest.showBothHands();
                        syncAnimState(playerId, AnimState.GRAB_READY);
                    } else {
                        animStates.put(playerId, AnimState.GRAB_READY);
                    }
                    chargeStartTime.put(playerId, worldTime());
                }
                case GRAB_HOLDING -> {
                    CoopAnim.play(clientPlayer, GRAB_HOLDING_ANIM);
                    if (isLocalPlayer) {
                        FirstPersonAnimationTest.showBothHands();
                        syncAnimState(playerId, AnimState.GRAB_HOLDING);
                    } else {
                        animStates.put(playerId, AnimState.GRAB_HOLDING);
                    }
                }
                case PUSH_IDLE -> {
                    CoopAnim.play(clientPlayer, PUSH_START_ANIM);
                    if (isLocalPlayer) {
                        FirstPersonAnimationTest.showBothHands();
                        syncAnimState(playerId, AnimState.PUSH_START);
                    } else {
                        animStates.put(playerId, AnimState.PUSH_START);
                    }
                    chargeStartTime.put(playerId, worldTime());
                }
                case NONE -> {
                    CoopAnim.stop(clientPlayer);
                    if (isLocalPlayer) {
                        syncAnimState(playerId, AnimState.NONE);
                        FirstPersonAnimationTest.stop();
                    } else {
                        animStates.put(playerId, AnimState.NONE);
                    }
                    chargeStartTime.remove(playerId);
                }
                default -> { }
            }
        } catch (Exception e) {
            System.err.println("[CoopMoves] Animation error: " + e.getMessage());
        }
    }

    public static void startGrabCharge(Player player) {
        if (!initialized) return;
        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;
        UUID playerId = player.getUUID();
        AnimState currentState = animStates.get(playerId);
        if (currentState != AnimState.GRAB_HOLDING && currentState != AnimState.GRAB_CHARGE_IDLE) return;
        try {
            if (!hasLayer(clientPlayer)) return;
            CoopAnim.play(clientPlayer, GRAB_HOLDING_CHARGE_ANIM);
            syncAnimState(playerId, AnimState.GRAB_CHARGING);
            chargeStartTime.put(playerId, worldTime());
            if (isLocal(playerId)) FirstPersonAnimationTest.showBothHands();
        } catch (Exception ignored) { }
    }

    public static void playThrowAnimation(Player player) {
        if (!initialized) return;
        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;
        UUID playerId = player.getUUID();
        try {
            if (!hasLayer(clientPlayer)) return;
            CoopAnim.play(clientPlayer, GRAB_THROW_ANIM);
            syncAnimState(playerId, AnimState.GRAB_THROWING);
            chargeStartTime.put(playerId, worldTime());
            if (isLocal(playerId)) FirstPersonAnimationTest.playThrow();
        } catch (Exception ignored) { }
    }

    public static void startDapCharge(Player player) {
        if (!initialized) return;
        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;
        UUID playerId = player.getUUID();
        try {
            if (!hasLayer(clientPlayer)) return;
            CoopAnim.play(clientPlayer, DAP_CHARGE_ANIM);
            syncAnimState(playerId, AnimState.DAP_CHARGING);
            if (isLocal(playerId)) FirstPersonAnimationTest.playDapCharge();
            chargeStartTime.put(playerId, worldTime());
        } catch (Exception ignored) { }
    }

    public static void stopDapCharge(Player player) {
        if (!initialized) return;
        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;
        UUID playerId = player.getUUID();
        AnimState state = animStates.get(playerId);
        if (state == AnimState.DAP_CHARGING || state == AnimState.DAP_CHARGE_IDLE
                || state == AnimState.FIRE_DAP_CHARGING || state == AnimState.FIRE_DAP_CHARGE_IDLE) {
            try {
                if (!hasLayer(clientPlayer)) return;
                CoopAnim.stop(clientPlayer);
                syncAnimState(playerId, AnimState.NONE);
                chargeStartTime.remove(playerId);
                if (isLocal(playerId)) FirstPersonAnimationTest.stop();
            } catch (Exception ignored) { }
        }
    }

    public static void stopDapChargeLocalOnly(Player player) {
        if (!initialized) return;
        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;
        UUID playerId = player.getUUID();
        AnimState state = animStates.get(playerId);
        if (state == AnimState.DAP_CHARGING || state == AnimState.DAP_CHARGE_IDLE
                || state == AnimState.FIRE_DAP_CHARGING || state == AnimState.FIRE_DAP_CHARGE_IDLE) {
            try {
                if (!hasLayer(clientPlayer)) return;
                CoopAnim.stop(clientPlayer);
                animStates.put(playerId, AnimState.NONE);
                chargeStartTime.remove(playerId);
                if (isLocal(playerId)) FirstPersonAnimationTest.stop();
            } catch (Exception ignored) { }
        }
    }

    public static void cancelDapCharge(Player player) {
        if (!initialized) return;
        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;
        UUID playerId = player.getUUID();
        AnimState state = animStates.get(playerId);
        try {
            if (!hasLayer(clientPlayer)) return;
            if (state == AnimState.DAP_CHARGING || state == AnimState.DAP_CHARGE_IDLE
                    || state == AnimState.FIRE_DAP_CHARGING || state == AnimState.FIRE_DAP_CHARGE_IDLE
                    || state == AnimState.DAP_HIT || state == AnimState.FIRE_DAP_HIT
                    || state == null) {
                CoopAnim.play(clientPlayer, DAP_DOWN_ANIM);
                syncAnimState(playerId, AnimState.DAP_DOWN);
            }
            chargeStartTime.remove(playerId);
        } catch (Exception e) {
            System.err.println("[CoopMoves] cancelDapCharge error: " + e.getMessage());
        }
    }

    /** Helper for the many "expire after N ticks then stop" tick branches. */
    private static void expire(AbstractClientPlayer p, UUID pid, boolean local, Long start,
                               long now, int duration, boolean stopFirstPerson) {
        if (start != null && now - start >= duration) {
            CoopAnim.stop(p);
            if (local) {
                if (stopFirstPerson) FirstPersonAnimationTest.stop();
                syncAnimState(pid, AnimState.NONE);
            } else {
                animStates.put(pid, AnimState.NONE);
            }
            chargeStartTime.remove(pid);
        }
    }

    public static void tick() {
        if (!initialized) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        long currentTime = client.level.getGameTime();
        UUID localPlayerId = client.player != null ? client.player.getUUID() : null;

        for (Player player : client.level.players()) {
            if (!(player instanceof AbstractClientPlayer clientPlayer)) continue;
            UUID playerId = player.getUUID();
            AnimState state = animStates.get(playerId);
            if (state == null) continue;
            boolean isLocalPlayer = playerId.equals(localPlayerId);
            try {
                if (!hasLayer(clientPlayer)) continue;
                Long startTime = chargeStartTime.get(playerId);
                switch (state) {
                    case GRAB_CHARGING -> {
                        if (startTime != null && currentTime - startTime >= GRAB_CHARGE_DURATION_TICKS) {
                            CoopAnim.play(clientPlayer, GRAB_HOLDING_CHARGE_IDLE_ANIM);
                            if (isLocalPlayer) syncAnimState(playerId, AnimState.GRAB_CHARGE_IDLE);
                            else animStates.put(playerId, AnimState.GRAB_CHARGE_IDLE);
                        }
                    }
                    case GRAB_THROWING -> {
                        if (startTime != null && currentTime - startTime >= THROW_ANIM_DURATION_TICKS) {
                            PoseState pose = currentPoses.get(playerId);
                            if (pose == PoseState.GRAB_HOLDING) {
                                CoopAnim.play(clientPlayer, GRAB_HOLDING_ANIM);
                                if (isLocalPlayer) syncAnimState(playerId, AnimState.GRAB_HOLDING);
                                else animStates.put(playerId, AnimState.GRAB_HOLDING);
                            } else {
                                CoopAnim.stop(clientPlayer);
                                if (isLocalPlayer) syncAnimState(playerId, AnimState.NONE);
                                else animStates.put(playerId, AnimState.NONE);
                            }
                            chargeStartTime.remove(playerId);
                        }
                    }
                    case DAP_CHARGING -> {
                        if (startTime != null && currentTime - startTime >= DAP_CHARGE_DURATION_TICKS) {
                            CoopAnim.play(clientPlayer, DAP_CHARGE_IDLE_ANIM);
                            if (isLocalPlayer) syncAnimState(playerId, AnimState.DAP_CHARGE_IDLE);
                            else animStates.put(playerId, AnimState.DAP_CHARGE_IDLE);
                        }
                    }
                    case GRAB_READY -> {
                        if (startTime != null && currentTime - startTime >= GRAB_READY_DURATION_TICKS) {
                            CoopAnim.play(clientPlayer, GRAB_READY_IDLE_ANIM);
                            if (isLocalPlayer) {
                                FirstPersonAnimationTest.showBothHands();
                                syncAnimState(playerId, AnimState.GRAB_READY_IDLE);
                            } else {
                                animStates.put(playerId, AnimState.GRAB_READY_IDLE);
                            }
                        }
                    }
                    case PUSH_START -> {
                        if (startTime != null && currentTime - startTime >= PUSH_START_DURATION_TICKS) {
                            CoopAnim.play(clientPlayer, PUSH_IDLE_ANIM);
                            if (isLocalPlayer) {
                                FirstPersonAnimationTest.showBothHands();
                                syncAnimState(playerId, AnimState.PUSH_IDLE);
                            } else {
                                animStates.put(playerId, AnimState.PUSH_IDLE);
                            }
                        }
                    }
                    case PERFECT_DAP_HIT ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, PERFECT_DAP_HIT_DURATION_TICKS, false);
                    case DAP_DOWN ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, DAP_DOWN_DURATION_TICKS, false);
                    case DAP_HIT ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, DAP_HIT_DURATION_TICKS, false);
                    case FIRE_DAP_HIT ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, FIRE_DAP_HIT_DURATION_TICKS, false);
                    case HIGHFIVE_START ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, HIGHFIVE_START_DURATION_TICKS, false);
                    case HIGHFIVE_END ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, HIGHFIVE_END_DURATION_TICKS, false);
                    case HIGHFIVE_HIT ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, HIGHFIVE_HIT_DURATION_TICKS, false);
                    case MARIO_JUMP ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, MARIO_JUMP_DURATION_TICKS, false);
                    case POP ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, POP_DURATION_TICKS, false);
                    case KICK ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, KICK_DURATION_TICKS, false);
                    case DROP_KICK ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, DROP_KICK_DURATION_TICKS, false);
                    case HIGHFIVE_SIKE ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, HIGHFIVE_SIKE_DURATION_TICKS, true);
                    case GROUND_POUND_LAND ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, GROUND_POUND_LAND_DURATION_TICKS, false);
                    case SLAP ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, SLAP_DURATION_TICKS, true);
                    case END_GROUP ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, END_GROUP_DURATION_TICKS, true);
                    case PERFECT_DAP_HIT_COMBO_END ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, PERFECT_DAP_HIT_COMBO_END_TICKS, true);
                    case FACING_DAP_P1 ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, FACING_DAP_P1_TICKS, true);
                    case FACING_DAP_P2 ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, FACING_DAP_P2_TICKS, true);
                    case HUDDLE_END ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, HUDDLE_END_DURATION_TICKS, true);
                    case CLAP ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, CLAP_DURATION_TICKS, true);
                    case CLAP_SPAM ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, CLAP_SPAM_DURATION_TICKS, true);
                    case CLAP_STRONG ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, CLAP_STRONG_DURATION_TICKS, true);
                    case FUSION_HIT_P1, FUSION_HIT_P2 ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, FUSION_HIT_DURATION_TICKS, false);
                    case HIGHFIVE_HUG ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, HIGHFIVE_HUG_DURATION_TICKS, true);
                    case HIGHFIVE_HUG2 ->
                            expire(clientPlayer, playerId, isLocalPlayer, startTime, currentTime, HIGHFIVE_HUG2_DURATION_TICKS, true);
                    case HUDDLE_START -> {
                        if (startTime != null && currentTime - startTime >= HUDDLE_START_DURATION_TICKS) {
                            CoopAnim.stop(clientPlayer);
                            chargeStartTime.remove(playerId);
                        }
                    }
                    case SITTING -> {
                        if (isLocalPlayer) FirstPersonAnimationTest.showBothHands();
                    }
                    // States with no timed transition (driven externally):
                    case DAPHOLD_HIGHFIVE, DAPHOLD_DAP, DAPHOLD_DAPPING, DAPHOLD_DAPPING_END,
                         SPIN, GROUND_POUND_DIVE, PERFECT_DAP_HIT_COMBO,
                         HUDDLE_IDLE, HUDDLE_QTE1, HUDDLE_QTE2, HUDDLE_QTE3,
                         LAY_DOWN, BONK, DAP_HIT_FACE, SLAP_FRONT, DAP_HIT_BAD,
                         DAP_LOOP, DAP_LOOP_END, REACH_DOWN, REACH_PICKUP, STAND_UP, HEAVEN_DAP,
                         FUSION_START_P1, FUSION_START_P2, FUSION_IDLE_P1, FUSION_IDLE_P2, AURA_WALK -> { }
                    default -> { }
                }
            } catch (Exception ignored) { }
        }
    }

    public static void stopAnimation(Player player) {
        if (!initialized) return;
        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;
        try {
            CoopAnim.stop(clientPlayer);
        } catch (Exception ignored) { }
        UUID playerId = player.getUUID();
        currentPoses.remove(playerId);
        animStates.remove(playerId);
        chargeStartTime.remove(playerId);
    }

    public static void cleanup(UUID playerId) {
        currentPoses.remove(playerId);
        animStates.remove(playerId);
        chargeStartTime.remove(playerId);
    }

    public static boolean isInChargeIdle(UUID playerId) {
        AnimState state = animStates.get(playerId);
        return state == AnimState.GRAB_CHARGE_IDLE || state == AnimState.DAP_CHARGE_IDLE
                || state == AnimState.FIRE_DAP_CHARGE_IDLE;
    }

    public static boolean isInBlockingState(UUID playerId) {
        AnimState state = animStates.get(playerId);
        return state == AnimState.HIGHFIVE_END
                || state == AnimState.SQUASHED
                || state == AnimState.PERFECT_DAP_HIT
                || state == AnimState.DAP_DOWN;
    }

    public static boolean isInHuddleAnim(UUID playerId) {
        AnimState s = getAnimState(playerId);
        return s == AnimState.HUDDLE_START || s == AnimState.HUDDLE_IDLE
                || s == AnimState.HUDDLE_QTE1 || s == AnimState.HUDDLE_QTE2
                || s == AnimState.HUDDLE_QTE3 || s == AnimState.HUDDLE_END;
    }

    public static boolean isInHugAnim(UUID playerId) {
        AnimState s = getAnimState(playerId);
        return s == AnimState.HUG_START || s == AnimState.HUGGING
                || s == AnimState.HUGGING2 || s == AnimState.HUG_END
                || s == AnimState.HIGHFIVE_HUG || s == AnimState.HIGHFIVE_HUG2;
    }

    public static AnimState getAnimState(UUID playerId) {
        return animStates.getOrDefault(playerId, AnimState.NONE);
    }

    // ---- Simple one-shot play helpers (same semantics as the Fabric versions) ----

    private static void simplePlay(Player player, ResourceLocation anim, AnimState state,
                                   boolean stamp, Runnable firstPerson) {
        if (!initialized) return;
        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;
        UUID playerId = player.getUUID();
        try {
            if (!hasLayer(clientPlayer)) return;
            CoopAnim.play(clientPlayer, anim);
            syncAnimState(playerId, state);
            if (stamp) chargeStartTime.put(playerId, worldTime());
            if (firstPerson != null && isLocal(playerId)) firstPerson.run();
        } catch (Exception ignored) { }
    }

    public static void playDapHit(Player player) {
        if (DapHoldClientHandler.isAnimationLocked(player.getUUID())) return;
        simplePlay(player, DAP_HIT_ANIM, AnimState.DAP_HIT, true, FirstPersonAnimationTest::playDapHit);
    }

    public static void playFireDapHit(Player player) {
        simplePlay(player, FIRE_DAP_HIT_ANIM, AnimState.FIRE_DAP_HIT, true, null);
    }

    public static void startFireDapCharge(Player player) {
        simplePlay(player, FIRE_DAP_CHARGE_ANIM, AnimState.FIRE_DAP_CHARGING, true, null);
    }

    public static void playFireDapChargeIdle(Player player) {
        simplePlay(player, FIRE_DAP_CHARGE_IDLE_ANIM, AnimState.FIRE_DAP_CHARGE_IDLE, false, null);
    }

    public static void playDapChargeIdle(Player player) {
        simplePlay(player, DAP_CHARGE_IDLE_ANIM, AnimState.DAP_CHARGE_IDLE, false, null);
    }

    public static void playPushAnimation(Player player) {
        simplePlay(player, PUSH_ANIM, AnimState.PUSHING, false, FirstPersonAnimationTest::playPush);
    }

    public static void playCatchAnimation(Player player) {
        simplePlay(player, CATCH_ANIM, AnimState.CATCHING, false, FirstPersonAnimationTest::showBothHands);
    }

    public static void playMahitoAnimation(Player player) {
        simplePlay(player, MAHITO_ANIM, AnimState.MAHITO, false, null);
    }

    public static void playHighFiveStart(Player player) {
        simplePlay(player, HIGHFIVE_START_ANIM, AnimState.HIGHFIVE_START, false, FirstPersonAnimationTest::playHighFiveStart);
    }

    public static void playHighFiveEnd(Player player) {
        simplePlay(player, HIGHFIVE_END_ANIM, AnimState.HIGHFIVE_END, true, null);
    }

    public static void playHighFiveHit(Player player) {
        if (DapHoldClientHandler.isAnimationLocked(player.getUUID())) return;
        simplePlay(player, HIGHFIVE_HIT_ANIM, AnimState.HIGHFIVE_HIT, true, FirstPersonAnimationTest::playHighFiveHit);
    }

    public static void playFallDapChargeStart(Player player) {
        simplePlay(player, DAP_CHARGE_FALL_START_ANIM, AnimState.DAP_CHARGE_FALL_START, false, null);
    }

    public static void playFallDapFalling(Player player) {
        simplePlay(player, DAP_CHARGE_FALLING_ANIM, AnimState.DAP_CHARGE_FALLING, false, null);
    }

    public static void playFallDapHit(Player player) {
        simplePlay(player, DAP_CHARGE_FALL_HIT_ANIM, AnimState.DAP_CHARGE_FALL_HIT, false, null);
    }

    public static void playSquashed(Player player) {
        simplePlay(player, SQUASHED_ANIM, AnimState.SQUASHED, false, null);
    }

    public static void playPerfectDapHit(Player player) {
        simplePlay(player, PERFECT_DAP_HIT_ANIM, AnimState.PERFECT_DAP_HIT, true, FirstPersonAnimationTest::playPerfectDap);
    }

    public static void playDapDown(Player player) {
        simplePlay(player, DAP_DOWN_ANIM, AnimState.DAP_DOWN, true, null);
    }

    public static void playHoldShield(Player player) {
        simplePlay(player, HOLD_SHIELD_ANIM, AnimState.HOLD_SHIELD, false, FirstPersonAnimationTest::showBothHands);
    }

    public static void playShield(Player player) {
        simplePlay(player, SHIELD_ANIM, AnimState.SHIELD, false, FirstPersonAnimationTest::showBothHands);
    }

    public static void stopShieldAnimation(Player player) {
        if (!initialized) return;
        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;
        try {
            if (!hasLayer(clientPlayer)) return;
            CoopAnim.stop(clientPlayer);
            syncAnimState(player.getUUID(), AnimState.NONE);
        } catch (Exception ignored) { }
    }

    public static void playFireDapHitPerfect(Player player) {
        simplePlay(player, FIRE_DAP_HIT_PERFECT_ANIM, AnimState.FIRE_DAP_HIT, false, FirstPersonAnimationTest::showBothHands);
    }

    public static void playFireDapComboP1(Player player) {
        simplePlay(player, FIRE_DAP_COMBO_P1_ANIM, AnimState.FIRE_DAP_HIT, false, FirstPersonAnimationTest::showBothHands);
    }

    public static void playFireDapComboP2(Player player) {
        simplePlay(player, FIRE_DAP_COMBO_P2_ANIM, AnimState.FIRE_DAP_HIT, false, FirstPersonAnimationTest::showBothHands);
    }

    public static void playDapHoldStart(Player player, int role) {
        if (!initialized) return;
        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;
        UUID playerId = player.getUUID();
        try {
            if (!hasLayer(clientPlayer)) return;
            animStates.put(playerId, AnimState.NONE);
            boolean isLocalPlayer = isLocal(playerId);
            if (role == 0) {
                CoopAnim.play(clientPlayer, DAPHOLD_HIGHFIVE_ANIM);
                syncAnimState(playerId, AnimState.DAPHOLD_HIGHFIVE);
                if (isLocalPlayer) FirstPersonAnimationTest.playHighFiveStart();
            } else {
                CoopAnim.play(clientPlayer, DAPHOLD_DAP_ANIM);
                syncAnimState(playerId, AnimState.DAPHOLD_DAP);
                if (isLocalPlayer) FirstPersonAnimationTest.playDapHit();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void playDapHoldDapping(Player player) {
        simplePlay(player, DAPHOLD_DAPPING_ANIM, AnimState.DAPHOLD_DAPPING, false, FirstPersonAnimationTest::showBothHands);
    }

    public static void playDapHoldResume(Player player, int role) {
        if (!initialized) return;
        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;
        UUID playerId = player.getUUID();
        try {
            if (!hasLayer(clientPlayer)) return;
            if (role == 0) {
                CoopAnim.play(clientPlayer, DAPHOLD_HIGHFIVE_ANIM);
                syncAnimState(playerId, AnimState.DAPHOLD_HIGHFIVE);
            } else {
                CoopAnim.play(clientPlayer, DAPHOLD_DAP_ANIM);
                syncAnimState(playerId, AnimState.DAPHOLD_DAP);
            }
            if (isLocal(playerId)) FirstPersonAnimationTest.stop();
        } catch (Exception ignored) { }
    }

    public static void playDapHoldEnd(Player player) {
        simplePlay(player, DAPHOLD_DAPPING_END_ANIM, AnimState.DAPHOLD_DAPPING_END, false, FirstPersonAnimationTest::showBothHands);
    }

    public static void playKick(Player player) {
        simplePlay(player, KICK_ANIM, AnimState.KICK, true, FirstPersonAnimationTest::playKick);
    }

    public static void playDropKick(Player player) {
        simplePlay(player, DROP_KICK_ANIM, AnimState.DROP_KICK, true, FirstPersonAnimationTest::playDropKick);
    }

    public static void playHighFiveSike(Player player) {
        simplePlay(player, HIGHFIVE_SIKE_ANIM, AnimState.HIGHFIVE_SIKE, true, FirstPersonAnimationTest::showBothHands);
    }

    /** Note: original used animStates.put (no network sync) for slap/endGroup/spin. */
    public static void playSlap(Player player) {
        if (!initialized) return;
        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;
        UUID playerId = player.getUUID();
        try {
            if (!hasLayer(clientPlayer)) return;
            CoopAnim.play(clientPlayer, SLAP_ANIM);
            animStates.put(playerId, AnimState.SLAP);
            if (isLocal(playerId)) FirstPersonAnimationTest.playSlap();
            chargeStartTime.put(playerId, worldTime());
        } catch (Exception ignored) { }
    }

    public static void playEndGroup(Player player) {
        if (!initialized) return;
        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;
        UUID playerId = player.getUUID();
        try {
            if (!hasLayer(clientPlayer)) return;
            CoopAnim.play(clientPlayer, END_GROUP_ANIM);
            animStates.put(playerId, AnimState.END_GROUP);
            if (isLocal(playerId)) FirstPersonAnimationTest.showBothHands();
            chargeStartTime.put(playerId, worldTime());
        } catch (Exception ignored) { }
    }

    public static void playSpinAnimation(Player player) {
        if (!initialized) return;
        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;
        UUID playerId = player.getUUID();
        try {
            if (!hasLayer(clientPlayer)) return;
            CoopAnim.play(clientPlayer, SPIN_ANIM);
            animStates.put(playerId, AnimState.SPIN);
            if (isLocal(playerId)) FirstPersonAnimationTest.showBothHands();
        } catch (Exception ignored) { }
    }

    // ---- Network-driven state application ----

    public static void setAnimStateFromNetwork(Player player, int stateOrdinal) {
        if (!initialized) return;
        if (!(player instanceof AbstractClientPlayer clientPlayer)) return;
        UUID playerId = player.getUUID();
        boolean local = isLocal(playerId);

        if (stateOrdinal == 0) {
            try {
                CoopAnim.stop(clientPlayer);
            } catch (Exception ignored) { }
            animStates.remove(playerId);
            currentPoses.remove(playerId);
            return;
        }

        AnimState state = AnimState.values()[stateOrdinal];
        try {
            if (!hasLayer(clientPlayer)) return;
            switch (state) {
                case GRAB_READY -> { CoopAnim.play(clientPlayer, GRAB_READY_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case GRAB_READY_IDLE -> { CoopAnim.play(clientPlayer, GRAB_READY_IDLE_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case GRAB_HOLDING -> { CoopAnim.play(clientPlayer, GRAB_HOLDING_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case GRAB_CHARGING -> { CoopAnim.play(clientPlayer, GRAB_HOLDING_CHARGE_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case GRAB_CHARGE_IDLE -> CoopAnim.play(clientPlayer, GRAB_HOLDING_CHARGE_IDLE_ANIM);
                case GRAB_THROWING -> { CoopAnim.play(clientPlayer, GRAB_THROW_ANIM); if (local) FirstPersonAnimationTest.playThrow(); }
                case DAP_CHARGING -> { CoopAnim.play(clientPlayer, DAP_CHARGE_ANIM); if (local) FirstPersonAnimationTest.playDapCharge(); }
                case DAP_CHARGE_IDLE -> { CoopAnim.play(clientPlayer, DAP_CHARGE_IDLE_ANIM); if (local) FirstPersonAnimationTest.playDapCharge(); }
                case DAP_HIT -> { CoopAnim.play(clientPlayer, DAP_HIT_ANIM); if (local) FirstPersonAnimationTest.playDapHit(); }
                case FIRE_DAP_CHARGING -> { CoopAnim.play(clientPlayer, FIRE_DAP_CHARGE_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case FIRE_DAP_CHARGE_IDLE -> { CoopAnim.play(clientPlayer, FIRE_DAP_CHARGE_IDLE_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case FIRE_DAP_HIT -> { CoopAnim.play(clientPlayer, FIRE_DAP_HIT_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case PUSH_START -> { CoopAnim.play(clientPlayer, PUSH_START_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case PUSH_IDLE -> { CoopAnim.play(clientPlayer, PUSH_IDLE_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case PUSHING -> { CoopAnim.play(clientPlayer, PUSH_ANIM); if (local) FirstPersonAnimationTest.playPush(); }
                case CATCHING -> { CoopAnim.play(clientPlayer, CATCH_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case MAHITO -> CoopAnim.play(clientPlayer, MAHITO_ANIM);
                case HIGHFIVE_START -> { CoopAnim.play(clientPlayer, HIGHFIVE_START_ANIM); if (local) FirstPersonAnimationTest.playHighFiveStart(); }
                case HIGHFIVE_END -> CoopAnim.play(clientPlayer, HIGHFIVE_END_ANIM);
                case HIGHFIVE_HIT -> { CoopAnim.play(clientPlayer, HIGHFIVE_HIT_ANIM); if (local) FirstPersonAnimationTest.playHighFiveHit(); }
                case HIGHFIVE_HIT_COMBO -> { CoopAnim.play(clientPlayer, HIGHFIVE_HIT_COMBO_ANIM); if (local) FirstPersonAnimationTest.playHighFiveCombo(); }
                case DAP_CHARGE_FALL_START -> CoopAnim.play(clientPlayer, DAP_CHARGE_FALL_START_ANIM);
                case DAP_CHARGE_FALLING -> CoopAnim.play(clientPlayer, DAP_CHARGE_FALLING_ANIM);
                case DAP_CHARGE_FALL_HIT -> CoopAnim.play(clientPlayer, DAP_CHARGE_FALL_HIT_ANIM);
                case SQUASHED -> CoopAnim.play(clientPlayer, SQUASHED_ANIM);
                case PERFECT_DAP_HIT -> { CoopAnim.play(clientPlayer, PERFECT_DAP_HIT_ANIM); if (local) FirstPersonAnimationTest.playPerfectDap(); }
                case PERFECT_DAP_EXTEND1_P1 -> { CoopAnim.play(clientPlayer, PERFECT_DAP_EXTEND1_P1_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case PERFECT_DAP_EXTEND1_P2 -> { CoopAnim.play(clientPlayer, PERFECT_DAP_EXTEND1_P2_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case PERFECT_DAP_MYBOY_P1 -> { CoopAnim.play(clientPlayer, PERFECT_DAP_MYBOY_P1_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case PERFECT_DAP_MYBOY_P2 -> { CoopAnim.play(clientPlayer, PERFECT_DAP_MYBOY_P2_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case PERFECT_DAP_EXTEND_BOTH -> { CoopAnim.play(clientPlayer, PERFECT_DAP_EXTEND_BOTH_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case HEAVE_DAP -> { CoopAnim.play(clientPlayer, HEAVE_DAP_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case DAP_DOWN -> { CoopAnim.play(clientPlayer, DAP_DOWN_ANIM); if (local) FirstPersonAnimationTest.stop(); }
                case HOLD_SHIELD -> { CoopAnim.play(clientPlayer, HOLD_SHIELD_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case SHIELD -> { CoopAnim.play(clientPlayer, SHIELD_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case MARIO_JUMP -> CoopAnim.play(clientPlayer, MARIO_JUMP_ANIM);
                case POP -> CoopAnim.play(clientPlayer, POP_ANIM);
                case HUG_START -> { CoopAnim.play(clientPlayer, HUG_START_ANIM); if (local) FirstPersonAnimationTest.playHug(); }
                case HUGGING -> CoopAnim.play(clientPlayer, HUGGING_ANIM);
                case HUGGING2 -> CoopAnim.play(clientPlayer, HUGGING2_ANIM);
                case HUG_END -> { CoopAnim.play(clientPlayer, HUG_END_ANIM); if (local) FirstPersonAnimationTest.stop(); }
                case FIRE_DAP_COMBO_P1 -> { CoopAnim.play(clientPlayer, FIRE_DAP_COMBO_P1_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case FIRE_DAP_COMBO_P2 -> { CoopAnim.play(clientPlayer, FIRE_DAP_COMBO_P2_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case DAPHOLD_HIGHFIVE -> { CoopAnim.play(clientPlayer, DAPHOLD_HIGHFIVE_ANIM); if (local) FirstPersonAnimationTest.playHighFiveStart(); }
                case DAPHOLD_DAP -> { CoopAnim.play(clientPlayer, DAPHOLD_DAP_ANIM); if (local) FirstPersonAnimationTest.playDapHit(); }
                case DAPHOLD_DAPPING -> { CoopAnim.play(clientPlayer, DAPHOLD_DAPPING_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case DAPHOLD_DAPPING_END -> { CoopAnim.play(clientPlayer, DAPHOLD_DAPPING_END_ANIM); if (local) FirstPersonAnimationTest.stop(); }
                case NONE -> { CoopAnim.stop(clientPlayer); if (local) FirstPersonAnimationTest.stop(); }
                case CLAP -> { CoopAnim.play(clientPlayer, CLAP_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case CLAP_SPAM -> { CoopAnim.play(clientPlayer, CLAP_SPAM_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case CLAP_STRONG -> { CoopAnim.play(clientPlayer, CLAP_STRONG_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case FUSION_START_P1 -> CoopAnim.play(clientPlayer, FUSION_START_P1_ANIM);
                case FUSION_START_P2 -> CoopAnim.play(clientPlayer, FUSION_START_P2_ANIM);
                case FUSION_HIT_P1 -> CoopAnim.play(clientPlayer, FUSION_HIT_P1_ANIM);
                case FUSION_HIT_P2 -> CoopAnim.play(clientPlayer, FUSION_HIT_P2_ANIM);
                case FUSION_IDLE_P1 -> CoopAnim.play(clientPlayer, FUSION_IDLE_P1_ANIM);
                case FUSION_IDLE_P2 -> CoopAnim.play(clientPlayer, FUSION_IDLE_P2_ANIM);
                case AURA_WALK -> CoopAnim.play(clientPlayer, AURA_WALK_ANIM);
                case HIGHFIVE_HUG -> { CoopAnim.play(clientPlayer, HIGHFIVE_HUG_ANIM); if (local) FirstPersonAnimationTest.playHug(); }
                case HIGHFIVE_HUG2 -> { CoopAnim.play(clientPlayer, HIGHFIVE_HUG2_ANIM); if (local) FirstPersonAnimationTest.playHug(); }
                case KICK -> { CoopAnim.play(clientPlayer, KICK_ANIM); if (local) FirstPersonAnimationTest.playKick(); }
                case DROP_KICK -> { CoopAnim.play(clientPlayer, DROP_KICK_ANIM); if (local) FirstPersonAnimationTest.playDropKick(); }
                case HIGHFIVE_SIKE -> { CoopAnim.play(clientPlayer, HIGHFIVE_SIKE_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case SPIN -> { CoopAnim.play(clientPlayer, SPIN_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case GROUND_POUND_DIVE -> { CoopAnim.play(clientPlayer, GROUND_POUND_DIVE_ANIM); if (local) FirstPersonAnimationTest.stop(); }
                case GROUND_POUND_LAND -> { CoopAnim.play(clientPlayer, GROUND_POUND_LAND_ANIM); if (local) FirstPersonAnimationTest.stop(); }
                case SLAP -> { CoopAnim.play(clientPlayer, SLAP_ANIM); if (local) FirstPersonAnimationTest.playSlap(); }
                case END_GROUP -> { CoopAnim.play(clientPlayer, END_GROUP_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case PERFECT_DAP_HIT_COMBO -> { CoopAnim.play(clientPlayer, PERFECT_DAP_HIT_COMBO_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case PERFECT_DAP_HIT_COMBO_END -> { CoopAnim.play(clientPlayer, PERFECT_DAP_HIT_COMBO_END_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case FACING_DAP_P1 -> { CoopAnim.play(clientPlayer, FACING_DAP_P1_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case FACING_DAP_P2 -> { CoopAnim.play(clientPlayer, FACING_DAP_P2_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case HUDDLE_START -> { CoopAnim.play(clientPlayer, HUDDLE_START_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case HUDDLE_IDLE -> { CoopAnim.play(clientPlayer, HUDDLE_IDLE_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case HUDDLE_QTE1 -> { CoopAnim.play(clientPlayer, HUDDLE_QTE1_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case HUDDLE_QTE2 -> { CoopAnim.play(clientPlayer, HUDDLE_QTE2_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case HUDDLE_QTE3 -> { CoopAnim.play(clientPlayer, HUDDLE_QTE3_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case HUDDLE_END -> { CoopAnim.play(clientPlayer, HUDDLE_END_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case LAY_DOWN -> CoopAnim.play(clientPlayer, LAY_DOWN_ANIM);
                case BONK -> { CoopAnim.play(clientPlayer, BONK_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case DAP_HIT_FACE -> { CoopAnim.play(clientPlayer, DAP_HIT_FACE_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case SLAP_FRONT -> { CoopAnim.play(clientPlayer, SLAP_FRONT_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case DAP_LOOP -> { CoopAnim.play(clientPlayer, DAP_LOOP_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case DAP_LOOP_END -> { CoopAnim.play(clientPlayer, DAP_LOOP_END_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case SITTING -> { CoopAnim.play(clientPlayer, SITTING_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case REACH_DOWN -> { CoopAnim.play(clientPlayer, REACH_DOWN_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case REACH_PICKUP -> { CoopAnim.play(clientPlayer, REACH_PICKUP_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case STAND_UP -> CoopAnim.play(clientPlayer, STAND_UP_ANIM);
                case HEAVEN_DAP -> { CoopAnim.play(clientPlayer, HEAVEN_DAP_ANIM); if (local) FirstPersonAnimationTest.showBothHands(); }
                case DAP_HIT_BAD -> {
                    CoopAnim.play(clientPlayer, DAP_HIT_BAD_ANIM);
                    if (local) {
                        ChargedDapClientHandler.triggerDapBadBlock();
                        FirstPersonAnimationTest.showBothHands();
                    }
                }
                default -> { }
            }

            Minecraft client = Minecraft.getInstance();
            if (client.level != null) {
                long t = client.level.getGameTime();
                switch (state) {
                    case DAP_HIT, FIRE_DAP_HIT, PERFECT_DAP_HIT,
                         HIGHFIVE_END, HIGHFIVE_HIT, HIGHFIVE_HUG, HIGHFIVE_HUG2,
                         MARIO_JUMP, POP, DAP_DOWN,
                         CLAP, CLAP_SPAM, CLAP_STRONG,
                         FUSION_HIT_P1, FUSION_HIT_P2,
                         KICK, DROP_KICK, HIGHFIVE_SIKE, GROUND_POUND_LAND,
                         SLAP, END_GROUP, PERFECT_DAP_HIT_COMBO, HUDDLE_START, HUDDLE_END,
                         HUDDLE_QTE2, HUDDLE_QTE3,
                         PERFECT_DAP_HIT_COMBO_END, FACING_DAP_P1, FACING_DAP_P2 ->
                            chargeStartTime.put(playerId, t);
                    default -> { }
                }
            }
            animStates.put(playerId, state);
        } catch (Exception ignored) { }
    }
}
