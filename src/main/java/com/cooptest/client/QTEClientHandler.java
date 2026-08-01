package com.cooptest.client;

import com.cooptest.QTEManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * Client-side QTE state + input. Ported from Fabric to Forge 1.20.1.
 *
 * S2C window/clear are routed here from QTEManager messages via DistExecutor;
 * {@link #handleKeyPress} is called by the dap/high-five client handlers and sends
 * the button press to the server.
 *
 * STAGE 6: the QTE HUD bar (renderHUD) and resolveKeyName (which maps buttons to
 * dap/meteor keybinds) are deferred to the client-render stage; the timing/input
 * logic is complete here.
 */
@OnlyIn(Dist.CLIENT)
public final class QTEClientHandler {

    private QTEClientHandler() {}

    private static boolean active = false;
    private static String expectedButton = null;
    private static int stage = 0;
    private static int maxStages = 1;
    private static long windowStart = 0;
    private static long windowEnd = 0;
    private static long receiveTime = 0;
    private static boolean pressedThisWindow = false;

    public static void register() { }

    // ------------------------------------------------------------------ S2C receivers

    public static void onWindow(UUID playerId, String button, int stg, long winStart, long winEnd) {
        long now = System.currentTimeMillis();
        active = true;
        expectedButton = button;
        stage = stg;
        windowStart = now + winStart;
        windowEnd = now + winEnd;
        receiveTime = now;
        pressedThisWindow = false;
    }

    public static void onClear(UUID playerId) {
        active = false;
        expectedButton = null;
        pressedThisWindow = false;
    }

    // ------------------------------------------------------------------ input

    public static boolean handleKeyPress(String button) {
        if (!active) return false;
        if (button.equals(expectedButton)) {
            long now = System.currentTimeMillis();
            if (now >= windowStart && now <= windowEnd) {
                pressedThisWindow = true;
            }
            QTEManager.sendButtonPress(button);
        }
        return true;
    }

    // ------------------------------------------------------------------ queries

    public static boolean isActive() { return active; }
    public static String getExpectedButton() { return expectedButton; }
    public static long getWindowStart() { return windowStart; }
    public static long getWindowEnd() { return windowEnd; }
    public static int getStage() { return stage; }
    public static void setMaxStages(int max) { maxStages = max; }
    public static long getReceiveTime() { return receiveTime; }
    public static boolean isPressedThisWindow() { return pressedThisWindow; }
}
