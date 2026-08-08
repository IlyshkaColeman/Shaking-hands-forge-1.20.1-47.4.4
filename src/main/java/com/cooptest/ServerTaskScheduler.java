package com.cooptest;

import net.minecraft.server.MinecraftServer;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Runs delayed gameplay actions on the owning server thread. */
public final class ServerTaskScheduler {
    private ServerTaskScheduler() {}

    private record Task(long dueTick, long sequence, Runnable action) implements Comparable<Task> {
        @Override
        public int compareTo(Task other) {
            int byTick = Long.compare(dueTick, other.dueTick);
            return byTick != 0 ? byTick : Long.compare(sequence, other.sequence);
        }
    }

    private static final Map<MinecraftServer, PriorityQueue<Task>> TASKS = new IdentityHashMap<>();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    public static void scheduleTicks(MinecraftServer server, int delayTicks, Runnable action) {
        if (server == null || action == null) return;
        long dueTick = server.getTickCount() + Math.max(0, delayTicks);
        TASKS.computeIfAbsent(server, ignored -> new PriorityQueue<>())
                .add(new Task(dueTick, SEQUENCE.getAndIncrement(), action));
    }

    public static void scheduleMillis(MinecraftServer server, long delayMillis, Runnable action) {
        int ticks = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (delayMillis + 49L) / 50L));
        scheduleTicks(server, ticks, action);
    }

    public static void tick(MinecraftServer server) {
        PriorityQueue<Task> queue = TASKS.get(server);
        if (queue == null) return;
        long now = server.getTickCount();
        while (!queue.isEmpty() && queue.peek().dueTick() <= now) {
            Task task = queue.poll();
            try {
                task.action().run();
            } catch (RuntimeException ex) {
                CoopMoves.LOGGER.error("Delayed CoopMoves task failed", ex);
            }
        }
        if (queue.isEmpty()) TASKS.remove(server);
    }

    public static void clear(MinecraftServer server) {
        if (server != null) TASKS.remove(server);
    }
}
