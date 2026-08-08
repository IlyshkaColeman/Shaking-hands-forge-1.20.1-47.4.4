package com.cooptest;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Quick-time-event engine shared by the Dap combo, high-five hug and huddle
 * mechanics. Ported from Fabric to Forge 1.20.1.
 *
 * Timing, stage logic and callback structure are unchanged. Fabric CustomPayloads
 * become CoopNetwork messages (button press C2S; window / clear S2C); the tick is
 * driven by CoopServerTick. sendMessage->displayClientMessage, getUuid->getUUID.
 */
public final class QTEManager {

    private QTEManager() {}

    private static final int STAGE_1_DELAY_TICKS = 8;
    private static final int STAGE_1_WINDOW_TICKS = 12;
    private static final int STAGE_2_DELAY_TICKS = 6;
    private static final int STAGE_2_WINDOW_TICKS = 9;
    private static final int STAGE_3_DELAY_TICKS = 4;
    private static final int STAGE_3_WINDOW_TICKS = 6;
    private static final int TIMEOUT_GRACE_TICKS = 4;
    private static final String[] BUTTONS = {"G", "H"};
    private static final Random RANDOM = new Random();

    private static final Map<UUID, QTESession> activeSessions = new HashMap<>();

    public static class QTESession {
        public final UUID player1Id;
        public final UUID player2Id;
        public final boolean isSolo;
        public int currentStage;
        public final int maxStages;
        public int ticksInStage;
        public int delayTicks;
        public int windowTicks;
        public QTEPhase phase;
        public String expectedButton;
        public boolean player1Pressed;
        public boolean player2Pressed;
        public QTECallback onAllStagesComplete;
        public QTECallback onFail;
        public StageCallback onStageComplete;
        public ServerPlayer player1Ref;
        public ServerPlayer player2Ref;

        public enum QTEPhase { WAIT, ACTIVE, GRACE, STAGE_TRANSITION, COMPLETE }

        QTESession(UUID p1, UUID p2, int maxStages, boolean solo) {
            this.player1Id = p1;
            this.player2Id = p2;
            this.maxStages = maxStages;
            this.isSolo = solo;
            this.currentStage = 1;
            this.ticksInStage = 0;
            this.phase = QTEPhase.WAIT;
            this.player1Pressed = false;
            this.player2Pressed = false;
            applyStageTimings(1);
            this.expectedButton = BUTTONS[RANDOM.nextInt(BUTTONS.length)];
        }

        private void applyStageTimings(int stage) {
            switch (stage) {
                case 1 -> { delayTicks = STAGE_1_DELAY_TICKS; windowTicks = STAGE_1_WINDOW_TICKS; }
                case 2 -> { delayTicks = STAGE_2_DELAY_TICKS; windowTicks = STAGE_2_WINDOW_TICKS; }
                case 3 -> { delayTicks = STAGE_3_DELAY_TICKS; windowTicks = STAGE_3_WINDOW_TICKS; }
                default -> { delayTicks = STAGE_3_DELAY_TICKS; windowTicks = STAGE_3_WINDOW_TICKS; }
            }
        }

        public float getPhaseProgress() {
            return switch (phase) {
                case WAIT -> (float) ticksInStage / delayTicks;
                case ACTIVE -> (float) ticksInStage / windowTicks;
                case GRACE -> (float) ticksInStage / TIMEOUT_GRACE_TICKS;
                default -> 1.0f;
            };
        }
    }

    @FunctionalInterface
    public interface QTECallback { void execute(ServerPlayer p1, ServerPlayer p2); }

    @FunctionalInterface
    public interface StageCallback { void execute(ServerPlayer p1, ServerPlayer p2, int completedStage); }

    // ------------------------------------------------------------------ triggers

    public static QTESession triggerQTE(ServerPlayer p1, ServerPlayer p2, int maxStages,
                                        QTECallback onSuccess, QTECallback onFail, StageCallback onStage) {
        UUID id1 = p1.getUUID();
        UUID id2 = p2.getUUID();
        if (activeSessions.containsKey(id1) || activeSessions.containsKey(id2)) return null;
        QTESession session = new QTESession(id1, id2, Math.min(maxStages, 3), false);
        session.player1Ref = p1;
        session.player2Ref = p2;
        session.onAllStagesComplete = onSuccess;
        session.onFail = onFail;
        session.onStageComplete = onStage;
        activeSessions.put(id1, session);
        activeSessions.put(id2, session);
        sendQTEWindowToClients(session);
        return session;
    }

    public static QTESession triggerQTESolo(ServerPlayer player, int maxStages,
                                            QTECallback onSuccess, QTECallback onFail, StageCallback onStage) {
        UUID id = player.getUUID();
        UUID fakeId = UUID.randomUUID();
        if (activeSessions.containsKey(id)) return null;
        QTESession session = new QTESession(id, fakeId, Math.min(maxStages, 3), true);
        session.player1Ref = player;
        session.player2Ref = player;
        session.onAllStagesComplete = onSuccess;
        session.onFail = onFail;
        session.onStageComplete = onStage;
        activeSessions.put(id, session);
        sendQTEWindowToClient(player, session);
        return session;
    }

    public static void onButtonPress(ServerPlayer player, String button) {
        if (player == null || button == null) return;
        UUID playerId = player.getUUID();
        QTESession session = activeSessions.get(playerId);
        if (session == null) return;
        if (!button.equals(session.expectedButton)) {
            player.displayClientMessage(Component.literal("§c§lWRONG BUTTON!"), true);
            return;
        }
        if (session.phase != QTESession.QTEPhase.ACTIVE) {
            if (session.phase == QTESession.QTEPhase.WAIT) {
                player.displayClientMessage(Component.literal("§c§lTOO EARLY!"), true);
            } else {
                player.displayClientMessage(Component.literal("§c§lTOO LATE!"), true);
            }
            return;
        }
        if (playerId.equals(session.player1Id)) session.player1Pressed = true;
        else if (playerId.equals(session.player2Id)) session.player2Pressed = true;
        if (!session.isSolo) checkBothPressed(session);
    }

    public static boolean isInQTE(UUID playerId) { return activeSessions.containsKey(playerId); }

    public static QTESession getSession(UUID playerId) { return activeSessions.get(playerId); }

    public static void cancelQTE(UUID playerId) {
        QTESession session = activeSessions.get(playerId);
        if (session != null) cleanupSession(session);
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftServer server) {
        List<QTESession> sessionsToTick = new ArrayList<>(activeSessions.values().stream().distinct().toList());
        for (QTESession session : sessionsToTick) tickSession(session, server);
    }

    private static void tickSession(QTESession session, MinecraftServer server) {
        session.ticksInStage++;
        switch (session.phase) {
            case WAIT -> {
                if (session.ticksInStage >= session.delayTicks) {
                    session.phase = QTESession.QTEPhase.ACTIVE;
                    session.ticksInStage = 0;
                    sendPrompt(session);
                }
            }
            case ACTIVE -> {
                if (session.isSolo && session.player1Pressed && !session.player2Pressed) {
                    if (session.ticksInStage >= 4) {
                        session.player2Pressed = true;
                        checkBothPressed(session);
                    }
                }
                if (session.ticksInStage >= session.windowTicks) {
                    session.phase = QTESession.QTEPhase.GRACE;
                    session.ticksInStage = 0;
                }
            }
            case GRACE -> {
                if (session.ticksInStage >= TIMEOUT_GRACE_TICKS) {
                    if (session.player1Ref != null) {
                        session.player1Ref.displayClientMessage(Component.literal("§c§l✖ MISSED!"), true);
                        CoopNetwork.sendToPlayer(session.player1Ref, new QTEClearMsg(session.player1Id));
                    }
                    if (session.player2Ref != null && !session.isSolo) {
                        session.player2Ref.displayClientMessage(Component.literal("§c§l✖ MISSED!"), true);
                        CoopNetwork.sendToPlayer(session.player2Ref, new QTEClearMsg(session.player2Id));
                    }
                    if (session.onFail != null) session.onFail.execute(session.player1Ref, session.player2Ref);
                    cleanupSession(session);
                }
            }
            case STAGE_TRANSITION -> {
                if (session.ticksInStage >= 10) {
                    session.currentStage++;
                    session.player1Pressed = false;
                    session.player2Pressed = false;
                    session.ticksInStage = 0;
                    session.phase = QTESession.QTEPhase.WAIT;
                    session.applyStageTimings(session.currentStage);
                    session.expectedButton = BUTTONS[RANDOM.nextInt(BUTTONS.length)];
                    sendQTEWindowToClients(session);
                }
            }
            case COMPLETE -> cleanupSession(session);
        }
    }

    private static void checkBothPressed(QTESession session) {
        if (!session.player1Pressed || !session.player2Pressed) return;
        if (session.onStageComplete != null) {
            session.onStageComplete.execute(session.player1Ref, session.player2Ref, session.currentStage);
        }
        if (session.currentStage < session.maxStages) {
            session.phase = QTESession.QTEPhase.STAGE_TRANSITION;
            session.ticksInStage = 0;
            if (session.player1Ref != null) {
                CoopNetwork.sendToPlayer(session.player1Ref, new QTEClearMsg(session.player1Id));
                session.player1Ref.displayClientMessage(
                        Component.literal("§a§l✓ STAGE " + session.currentStage + " CLEAR!"), true);
            }
            if (session.player2Ref != null && !session.isSolo) {
                CoopNetwork.sendToPlayer(session.player2Ref, new QTEClearMsg(session.player2Id));
                session.player2Ref.displayClientMessage(
                        Component.literal("§a§l✓ STAGE " + session.currentStage + " CLEAR!"), true);
            }
        } else {
            session.phase = QTESession.QTEPhase.COMPLETE;
            if (session.player1Ref != null) {
                CoopNetwork.sendToPlayer(session.player1Ref, new QTEClearMsg(session.player1Id));
            }
            if (session.player2Ref != null && !session.isSolo) {
                CoopNetwork.sendToPlayer(session.player2Ref, new QTEClearMsg(session.player2Id));
            }
            if (session.onAllStagesComplete != null) {
                session.onAllStagesComplete.execute(session.player1Ref, session.player2Ref);
            }
            cleanupSession(session);
        }
    }

    private static void sendQTEWindowToClients(QTESession session) {
        long windowStartOffset = (long) session.delayTicks * 50;
        long windowDuration = (long) session.windowTicks * 50;
        if (session.player1Ref != null) {
            CoopNetwork.sendToPlayer(session.player1Ref, new QTEWindowMsg(
                    session.player1Id, session.expectedButton, session.currentStage,
                    windowStartOffset, windowStartOffset + windowDuration));
        }
        if (session.player2Ref != null && !session.isSolo) {
            CoopNetwork.sendToPlayer(session.player2Ref, new QTEWindowMsg(
                    session.player2Id, session.expectedButton, session.currentStage,
                    windowStartOffset, windowStartOffset + windowDuration));
        }
    }

    private static void sendQTEWindowToClient(ServerPlayer player, QTESession session) {
        long windowStartOffset = (long) session.delayTicks * 50;
        long windowDuration = (long) session.windowTicks * 50;
        CoopNetwork.sendToPlayer(player, new QTEWindowMsg(
                player.getUUID(), session.expectedButton, session.currentStage,
                windowStartOffset, windowStartOffset + windowDuration));
    }

    private static void sendPrompt(QTESession session) {
        String stageText = session.maxStages > 1
                ? " §7(Stage " + session.currentStage + "/" + session.maxStages + ")" : "";
        if (session.player1Ref != null) {
            session.player1Ref.displayClientMessage(
                    Component.literal("§e§lPRESS [" + session.expectedButton + "]!" + stageText), true);
        }
        if (session.player2Ref != null && !session.isSolo) {
            session.player2Ref.displayClientMessage(
                    Component.literal("§e§lPRESS [" + session.expectedButton + "]!" + stageText), true);
        }
    }

    private static void cleanupSession(QTESession session) {
        session.phase = QTESession.QTEPhase.COMPLETE;
        activeSessions.remove(session.player1Id);
        activeSessions.remove(session.player2Id);
    }

    // ------------------------------------------------------------------ networking

    /** C2S: the client pressed the expected QTE button. */
    public record QTEButtonPressMsg(String button) {
        public static void encode(QTEButtonPressMsg m, FriendlyByteBuf buf) { buf.writeUtf(m.button); }
        public static QTEButtonPressMsg decode(FriendlyByteBuf buf) { return new QTEButtonPressMsg(buf.readUtf(16)); }
        public static void handle(QTEButtonPressMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player == null) return;
                String button = m.button();
                if (!("G".equals(button) || "H".equals(button) || "J".equals(button))) return;
                // Central QTE dispatch chain (Fabric: ChargedDapHandler's QTE receiver).
                if (PerfectDapComboHandler.onButtonPress(player, button)) return;
                if (DapFusionHandler.onQTEButtonPress(player, button)) return;
                if (HighFiveQTEHugHandler.onButtonPress(player, button)) return;
                if (HuddleHandler.onButtonPress(player, button)) return;
                if (DapComboChain.onButtonPress(player, button)) return;
                onButtonPress(player, button);
            });
            c.setPacketHandled(true);
        }
    }

    /** S2C: open a QTE window on the client. */
    public record QTEWindowMsg(UUID playerId, String button, int stage, long windowStart, long windowEnd) {
        public static void encode(QTEWindowMsg m, FriendlyByteBuf buf) {
            buf.writeUUID(m.playerId); buf.writeUtf(m.button); buf.writeInt(m.stage);
            buf.writeLong(m.windowStart); buf.writeLong(m.windowEnd);
        }
        public static QTEWindowMsg decode(FriendlyByteBuf buf) {
            return new QTEWindowMsg(buf.readUUID(), buf.readUtf(16), buf.readInt(), buf.readLong(), buf.readLong());
        }
        public static void handle(QTEWindowMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.QTEClientHandler.onWindow(
                                    m.playerId(), m.button(), m.stage(), m.windowStart(), m.windowEnd()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    /** S2C: close the QTE window on the client. */
    public record QTEClearMsg(UUID playerId) {
        public static void encode(QTEClearMsg m, FriendlyByteBuf buf) { buf.writeUUID(m.playerId); }
        public static QTEClearMsg decode(FriendlyByteBuf buf) { return new QTEClearMsg(buf.readUUID()); }
        public static void handle(QTEClearMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.QTEClientHandler.onClear(m.playerId()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(QTEButtonPressMsg.class, QTEButtonPressMsg::encode, QTEButtonPressMsg::decode, QTEButtonPressMsg::handle);
        CoopNetwork.register(QTEWindowMsg.class, QTEWindowMsg::encode, QTEWindowMsg::decode, QTEWindowMsg::handle);
        CoopNetwork.register(QTEClearMsg.class, QTEClearMsg::encode, QTEClearMsg::decode, QTEClearMsg::handle);
    }

    public static void sendButtonPress(String button) {
        CoopNetwork.sendToServer(new QTEButtonPressMsg(button));
    }
}
