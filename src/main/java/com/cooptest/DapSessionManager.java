package com.cooptest;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks active {@link DapSession}s. Ported from Fabric to Forge 1.20.1 — the
 * END_SERVER_TICK registration becomes a {@link #tick(MinecraftServer)} call driven
 * by CoopServerTick.
 */
public final class DapSessionManager {

    private DapSessionManager() {}

    private static final Map<UUID, DapSession> activeSessions = new HashMap<>();
    private static final Set<UUID> playersInSession = new HashSet<>();

    public static void register() { }

    public static DapSession createSession(UUID playerA, UUID playerB, double targetDistance, DapSession.DapType type) {
        if (isInSession(playerA)) return null;
        if (isInSession(playerB)) return null;
        DapSession session = new DapSession(playerA, playerB, targetDistance, type);
        activeSessions.put(playerA, session);
        playersInSession.add(playerA);
        playersInSession.add(playerB);
        return session;
    }

    public static DapSession getSession(UUID playerId) {
        DapSession session = activeSessions.get(playerId);
        if (session != null) return session;
        for (DapSession s : activeSessions.values()) {
            if (s.getPlayerBId().equals(playerId)) return s;
        }
        return null;
    }

    public static boolean isInSession(UUID playerId) {
        return playersInSession.contains(playerId);
    }

    public static void removeSession(UUID playerA) {
        DapSession session = activeSessions.remove(playerA);
        if (session != null) {
            session.cancel();
            playersInSession.remove(session.getPlayerAId());
            playersInSession.remove(session.getPlayerBId());
        }
    }

    public static void removeSessionForPlayer(UUID playerId) {
        DapSession session = getSession(playerId);
        if (session != null) removeSession(session.getPlayerAId());
    }

    public static void tick(MinecraftServer server) {
        List<DapSession> sessionsToTick = new ArrayList<>(activeSessions.values());
        for (DapSession session : sessionsToTick) {
            session.tick(server);
            if (session.isPositioningComplete() || session.getTickCount() > 100) {
                UUID playerA = session.getPlayerAId();
                UUID playerB = session.getPlayerBId();
                activeSessions.remove(playerA);
                playersInSession.remove(playerA);
                playersInSession.remove(playerB);
            }
        }
    }

    public static Collection<DapSession> getAllSessions() { return activeSessions.values(); }

    public static void clearAll() {
        activeSessions.clear();
        playersInSession.clear();
    }
}
