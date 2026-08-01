package com.cooptest;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Perfect-dap combo chain — after a perfect dap, both players hit staged QTE
 * windows to extend into "MY BOY!". Ported from Fabric to Forge 1.20.1.
 *
 * Driven by ChargedDapHandler (core). Uses the shared QTE messages
 * (QTEManager.QTEWindowMsg / QTEClearMsg) and ChargedDapHandler.PerfectDapFreezePayload.
 * AnimState ordinals hardcoded (EXTEND1_P1=43, EXTEND1_P2=44, MYBOY_P1=45,
 * EXTEND_BOTH=47) since CoopAnimationHandler is client-only.
 */
public final class DapComboChain {

    private DapComboChain() {}

    private static final int STAGE0_EVALUATE_TICK = 18;
    private static final int STAGE0_ANIM_END = 20;
    private static final int STAGE1_SOUND_1 = 8;
    private static final int STAGE1_SOUND_2 = 15;
    private static final int STAGE1_SOUND_3 = 24;
    private static final int STAGE1_EVALUATE_TICK = 24;
    private static final int STAGE1_ANIM_END = 57;
    private static final int STAGE2_SOUND_1 = 6;
    private static final int STAGE2_SOUND_2 = 23;
    private static final int STAGE2_SOUND_3 = 28;
    private static final int STAGE2_SOUND_4 = 39;
    private static final int STAGE2_EVALUATE_TICK = 42;
    private static final int STAGE2_ANIM_END = 70;
    private static final int STAGE3_MYBOY_SOUND_1 = 4;
    private static final int STAGE3_MYBOY_SOUND_2 = 8;
    private static final int STAGE3_ANIM_END = 51;

    private static final int ANIM_EXTEND1_P1 = 43;
    private static final int ANIM_EXTEND1_P2 = 44;
    private static final int ANIM_MYBOY_P1 = 45;
    private static final int ANIM_EXTEND_BOTH = 47;
    private static final int ANIM_NONE = 0;

    private static final String[] BUTTONS = {"G", "H"};
    private static final Random RANDOM = new Random();
    private static final Map<UUID, ComboSession> activeCombos = new HashMap<>();

    public static class ComboSession {
        public final UUID p1Id, p2Id;
        public ServerPlayer p1Ref, p2Ref;
        public ServerLevel world;
        public Vec3 impactPos;
        public int stage;
        public int ticksInStage;
        public boolean evaluated;
        public String expectedButton;
        public boolean p1Pressed, p2Pressed;
        public boolean qteWindowOpen;
        public boolean qteSent;
        public int p1RapidCount, p2RapidCount;
        public static final int RAPID_REQUIRED = 3;

        ComboSession(ServerPlayer p1, ServerPlayer p2, Vec3 pos) {
            this.p1Id = p1.getUUID();
            this.p2Id = p2.getUUID();
            this.p1Ref = p1;
            this.p2Ref = p2;
            this.world = p1.serverLevel();
            this.impactPos = pos;
            this.stage = 0;
            this.ticksInStage = 0;
            this.evaluated = false;
            this.expectedButton = BUTTONS[RANDOM.nextInt(BUTTONS.length)];
            this.p1Pressed = false;
            this.p2Pressed = false;
            this.qteWindowOpen = false;
            this.qteSent = false;
        }

        void resetQTE() {
            p1Pressed = false; p2Pressed = false;
            qteWindowOpen = false; qteSent = false; evaluated = false;
            p1RapidCount = 0; p2RapidCount = 0;
            expectedButton = BUTTONS[RANDOM.nextInt(BUTTONS.length)];
        }

        void advanceStage() {
            stage++;
            ticksInStage = 0;
            resetQTE();
        }
    }

    public static void startCombo(ServerPlayer p1, ServerPlayer p2, Vec3 impactPos) {
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        if (activeCombos.containsKey(id1) || activeCombos.containsKey(id2)) return;
        ComboSession session = new ComboSession(p1, p2, impactPos);
        activeCombos.put(id1, session);
        activeCombos.put(id2, session);
        CoopNetwork.sendToPlayer(p1, new ChargedDapHandler.PerfectDapFreezePayload(true));
        CoopNetwork.sendToPlayer(p2, new ChargedDapHandler.PerfectDapFreezePayload(true));
    }

    public static void startFromExtend1(ServerPlayer p1, ServerPlayer p2, Vec3 impactPos) {
        UUID id1 = p1.getUUID(), id2 = p2.getUUID();
        if (activeCombos.containsKey(id1) || activeCombos.containsKey(id2)) return;
        ComboSession session = new ComboSession(p1, p2, impactPos);
        session.stage = 1;
        session.ticksInStage = 0;
        session.resetQTE();
        activeCombos.put(id1, session);
        activeCombos.put(id2, session);
        CoopNetwork.sendToPlayer(p1, new ChargedDapHandler.PerfectDapFreezePayload(true));
        CoopNetwork.sendToPlayer(p2, new ChargedDapHandler.PerfectDapFreezePayload(true));
        PoseNetworking.broadcastAnimState(p1, ANIM_EXTEND1_P1);
        PoseNetworking.broadcastAnimState(p2, ANIM_EXTEND1_P2);
        openQTEWindow(session);
    }

    public static boolean onButtonPress(ServerPlayer player, String button) {
        UUID id = player.getUUID();
        ComboSession session = activeCombos.get(id);
        if (session == null) return false;
        if (!session.qteWindowOpen) return true;
        if (!button.equals(session.expectedButton)) {
            String who = player.getName().getString();
            Component msg = Component.literal("§c" + who + " pressed the wrong button!");
            session.p1Ref.displayClientMessage(msg, true);
            session.p2Ref.displayClientMessage(msg, true);
            closeQTEWindow(session);
            session.evaluated = true;
            session.stage = -(session.stage + 1);
            return true;
        }
        if (session.stage == 2) {
            int pressCount;
            if (id.equals(session.p1Id)) {
                session.p1RapidCount++;
                pressCount = session.p1RapidCount;
                if (session.p1RapidCount >= ComboSession.RAPID_REQUIRED) session.p1Pressed = true;
            } else if (id.equals(session.p2Id)) {
                session.p2RapidCount++;
                pressCount = session.p2RapidCount;
                if (session.p2RapidCount >= ComboSession.RAPID_REQUIRED) session.p2Pressed = true;
            } else {
                return true;
            }
            Vec3 armPos = getRightArmTip(player);
            session.world.playSound(null, armPos.x, armPos.y, armPos.z,
                    SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.PLAYERS, 0.6f, 1.2f);
            int remaining = ComboSession.RAPID_REQUIRED - pressCount;
            long windowRemaining = Math.max(100L, (long) (STAGE2_EVALUATE_TICK - session.ticksInStage) * 50);
            if (remaining > 0) {
                CoopNetwork.sendToPlayer(player, new QTEManager.QTEWindowMsg(
                        player.getUUID(), session.expectedButton, -remaining, 0L, windowRemaining));
            }
        } else {
            if (id.equals(session.p1Id)) session.p1Pressed = true;
            else if (id.equals(session.p2Id)) session.p2Pressed = true;
        }
        return true;
    }

    public static boolean isInCombo(UUID playerId) { return activeCombos.containsKey(playerId); }

    public static void cancelCombo(UUID playerId) {
        ComboSession session = activeCombos.get(playerId);
        if (session != null) cleanup(session, false);
    }

    public static void tick(MinecraftServer server) {
        List<ComboSession> toTick = activeCombos.values().stream().distinct().toList();
        for (ComboSession session : toTick) {
            ServerPlayer p1 = server.getPlayerList().getPlayer(session.p1Id);
            ServerPlayer p2 = server.getPlayerList().getPlayer(session.p2Id);
            if (p1 == null || p2 == null || !p1.isAlive() || !p2.isAlive()) {
                cleanup(session, false);
                continue;
            }
            session.p1Ref = p1;
            session.p2Ref = p2;
            tickSession(session);
        }
    }

    private static void tickSession(ComboSession s) {
        s.ticksInStage++;
        switch (s.stage) {
            case 0 -> tickStage0(s);
            case 1 -> tickStage1(s);
            case 2 -> tickStage2(s);
            case 3 -> tickStage3(s);
            case -1 -> { if (s.ticksInStage >= STAGE0_ANIM_END) cleanup(s, false); }
            case -2 -> { if (s.ticksInStage >= STAGE1_ANIM_END) cleanup(s, false); }
            case -3 -> { if (s.ticksInStage >= STAGE2_ANIM_END) cleanup(s, false); }
            default -> cleanup(s, false);
        }
    }

    private static void tickStage0(ComboSession s) {
        if (s.ticksInStage == 8 && !s.qteSent) {
            s.world.playSound(null, s.impactPos.x, s.impactPos.y, s.impactPos.z,
                    SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.0f, 1.8f);
            openQTEWindow(s);
        }
        if (s.ticksInStage >= STAGE0_EVALUATE_TICK && !s.evaluated) {
            s.evaluated = true;
            closeQTEWindow(s);
            if (s.p1Pressed && s.p2Pressed) startExtend1(s);
            else { sendFailMessage(s); s.stage = -1; }
        }
    }

    private static void startExtend1(ComboSession s) {
        s.advanceStage();
        PoseNetworking.broadcastAnimState(s.p1Ref, ANIM_EXTEND1_P1);
        PoseNetworking.broadcastAnimState(s.p2Ref, ANIM_EXTEND1_P2);
        CoopNetwork.sendToPlayer(s.p1Ref, new ChargedDapHandler.PerfectDapFreezePayload(true));
        CoopNetwork.sendToPlayer(s.p2Ref, new ChargedDapHandler.PerfectDapFreezePayload(true));
        openQTEWindow(s);
    }

    private static void tickStage1(ComboSession s) {
        if (s.ticksInStage == STAGE1_SOUND_1 || s.ticksInStage == STAGE1_SOUND_2 || s.ticksInStage == STAGE1_SOUND_3) {
            playCritEffects(s);
        }
        if (s.ticksInStage >= STAGE1_EVALUATE_TICK && !s.evaluated) {
            s.evaluated = true;
            closeQTEWindow(s);
            if (s.p1Pressed && s.p2Pressed) startExtend2(s);
            else { sendFailMessage(s); s.stage = -2; }
        }
    }

    private static void startExtend2(ComboSession s) {
        s.advanceStage();
        PoseNetworking.broadcastAnimState(s.p1Ref, ANIM_EXTEND_BOTH);
        PoseNetworking.broadcastAnimState(s.p2Ref, ANIM_EXTEND_BOTH);
        openQTEWindow(s);
    }

    private static void tickStage2(ComboSession s) {
        if (s.ticksInStage == STAGE2_SOUND_1 || s.ticksInStage == STAGE2_SOUND_2 || s.ticksInStage == STAGE2_SOUND_3) {
            playCritEffects(s);
        }
        if (s.ticksInStage == STAGE2_SOUND_4) {
            playCritEffects(s);
            s.world.playSound(null, s.impactPos.x, s.impactPos.y, s.impactPos.z,
                    ModSounds.EPIC_DAP.get(), SoundSource.PLAYERS, 1.5f, 1.0f);
        }
        if (s.ticksInStage >= STAGE2_EVALUATE_TICK && !s.evaluated) {
            s.evaluated = true;
            closeQTEWindow(s);
            if (s.p1Pressed && s.p2Pressed) startFinal(s);
            else { sendFailMessage(s); s.stage = -3; }
        }
    }

    private static void startFinal(ComboSession s) {
        s.advanceStage();
        DapSessionManager.removeSessionForPlayer(s.p1Id);
        PoseNetworking.broadcastAnimState(s.p1Ref, ANIM_MYBOY_P1);
        PoseNetworking.broadcastAnimState(s.p2Ref, ANIM_MYBOY_P1);
        CoopNetwork.sendToPlayer(s.p1Ref, new ChargedDapHandler.PerfectDapFreezePayload(false));
        CoopNetwork.sendToPlayer(s.p2Ref, new ChargedDapHandler.PerfectDapFreezePayload(false));
        s.p1Ref.displayClientMessage(Component.literal("§d§l★ MY BOY! ★"), true);
        s.p2Ref.displayClientMessage(Component.literal("§d§l★ MY BOY! ★"), true);
        spawnFinishEffect(s);
    }

    private static void tickStage3(ComboSession s) {
        if (s.ticksInStage == STAGE3_MYBOY_SOUND_1 || s.ticksInStage == STAGE3_MYBOY_SOUND_2) {
            playSmallArmEffect(s);
        }
        if (s.ticksInStage >= STAGE3_ANIM_END) cleanup(s, true);
    }

    private static void openQTEWindow(ComboSession s) {
        if (s.qteSent) return;
        s.qteSent = true;
        s.qteWindowOpen = true;
        float pitch = switch (s.stage) {
            case 0 -> 1.8f;
            case 1 -> 1.5f;
            case 2 -> 1.3f;
            default -> 1.5f;
        };
        s.world.playSound(null, s.impactPos.x, s.impactPos.y, s.impactPos.z,
                SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.8f, pitch);
        int evaluateTick = switch (s.stage) {
            case 0 -> STAGE0_EVALUATE_TICK;
            case 1 -> STAGE1_EVALUATE_TICK;
            case 2 -> STAGE2_EVALUATE_TICK;
            default -> 20;
        };
        long windowDurationMs = (long) evaluateTick * 50;
        int displayStage = (s.stage == 2) ? -ComboSession.RAPID_REQUIRED : s.stage + 1;
        CoopNetwork.sendToPlayer(s.p1Ref, new QTEManager.QTEWindowMsg(s.p1Id, s.expectedButton, displayStage, 0L, windowDurationMs));
        CoopNetwork.sendToPlayer(s.p2Ref, new QTEManager.QTEWindowMsg(s.p2Id, s.expectedButton, displayStage, 0L, windowDurationMs));
    }

    private static void closeQTEWindow(ComboSession s) {
        s.qteWindowOpen = false;
        CoopNetwork.sendToPlayer(s.p1Ref, new QTEManager.QTEClearMsg(s.p1Id));
        CoopNetwork.sendToPlayer(s.p2Ref, new QTEManager.QTEClearMsg(s.p2Id));
    }

    private static void playCritEffects(ComboSession s) {
        for (ServerPlayer player : List.of(s.p1Ref, s.p2Ref)) {
            Vec3 armPos = getRightArmTip(player);
            s.world.playSound(null, armPos.x, armPos.y, armPos.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.2f, 1.0f);
            s.world.sendParticles(ParticleTypes.CRIT, armPos.x, armPos.y, armPos.z, 8, 0.12, 0.12, 0.12, 0.1);
            s.world.sendParticles(ParticleTypes.ENCHANTED_HIT, armPos.x, armPos.y, armPos.z, 5, 0.08, 0.08, 0.08, 0.05);
        }
    }

    private static void playSmallArmEffect(ComboSession s) {
        for (ServerPlayer player : List.of(s.p1Ref, s.p2Ref)) {
            Vec3 armPos = getRightArmTip(player);
            s.world.playSound(null, armPos.x, armPos.y, armPos.z, ModSounds.DAP_WEAK.get(), SoundSource.PLAYERS, 0.3f, 0.6f);
            s.world.sendParticles(ParticleTypes.CRIT, armPos.x, armPos.y, armPos.z, 4, 0.08, 0.08, 0.08, 0.05);
        }
    }

    private static void spawnFinishEffect(ComboSession s) {
        Vec3 mid = s.p1Ref.position().add(s.p2Ref.position()).scale(0.5).add(0, 1.0, 0);
        s.world.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, mid.x, mid.y, mid.z, 40, 0.5, 0.5, 0.5, 0.2);
        s.world.sendParticles(ParticleTypes.END_ROD, mid.x, mid.y, mid.z, 20, 0.3, 0.8, 0.3, 0.1);
        s.world.sendParticles(ParticleTypes.FLASH, mid.x, mid.y, mid.z, 2, 0, 0, 0, 0);
        s.world.playSound(null, mid.x, mid.y, mid.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 2.0f, 0.8f);
        for (ServerPlayer player : List.of(s.p1Ref, s.p2Ref)) {
            Vec3 armPos = getRightArmTip(player);
            s.world.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, armPos.x, armPos.y, armPos.z, 15, 0.15, 0.15, 0.15, 0.15);
        }
    }

    private static Vec3 getRightArmTip(ServerPlayer player) {
        double yawRad = Math.toRadians(player.yBodyRot);
        double rightX = -Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        return new Vec3(
                player.getX() + rightX * 0.3 + forwardX * 0.4,
                player.getY() + 1.0,
                player.getZ() + rightZ * 0.3 + forwardZ * 0.4);
    }

    private static void sendFailMessage(ComboSession s) {
        if (!s.p1Pressed && !s.p2Pressed) {
            Component msg = Component.literal("§cBoth players missed!");
            s.p1Ref.displayClientMessage(msg, true);
            s.p2Ref.displayClientMessage(msg, true);
            return;
        }
        String who = !s.p1Pressed ? s.p1Ref.getName().getString() : s.p2Ref.getName().getString();
        Component msg = Component.literal("§c" + who + " missed the extend!");
        s.p1Ref.displayClientMessage(msg, true);
        s.p2Ref.displayClientMessage(msg, true);
    }

    private static void cleanup(ComboSession s, boolean success) {
        if (s.qteWindowOpen) closeQTEWindow(s);
        if (s.p1Ref != null) CoopNetwork.sendToPlayer(s.p1Ref, new ChargedDapHandler.PerfectDapFreezePayload(false));
        if (s.p2Ref != null) CoopNetwork.sendToPlayer(s.p2Ref, new ChargedDapHandler.PerfectDapFreezePayload(false));
        if (s.p1Ref != null) PoseNetworking.broadcastAnimState(s.p1Ref, ANIM_NONE);
        if (s.p2Ref != null) PoseNetworking.broadcastAnimState(s.p2Ref, ANIM_NONE);
        DapSessionManager.removeSessionForPlayer(s.p1Id);
        HighFiveHandler.cleanup(s.p1Id);
        HighFiveHandler.cleanup(s.p2Id);
        activeCombos.remove(s.p1Id);
        activeCombos.remove(s.p2Id);
    }
}
