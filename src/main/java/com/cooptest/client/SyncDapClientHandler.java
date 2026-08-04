package com.cooptest.client;

import com.cooptest.SyncDapHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * Client side of the synchronized ping-pong dap. Shows a minimalist vertical bar for
 * the local player (with the medium band + perfect cube and a bouncing marker) and a
 * smaller mirror bar for the partner to its right. Releasing G locks the local marker
 * (handled in {@link ChargedDapClientHandler}).
 */
@OnlyIn(Dist.CLIENT)
public final class SyncDapClientHandler {

    private SyncDapClientHandler() {}

    /** Full up+down cycle of the marker, ms. Lower = harder. */
    private static final long PERIOD_MS = 1600;

    private static boolean active = false;
    private static long startTime = 0;
    private static int partnerLocked = -1; // -1 = partner still choosing

    public static final IGuiOverlay HUD = (gui, g, partial, w, h) -> render(g, w, h);

    /** S2C from SyncDapHandler.SyncActiveMsg. */
    public static void onActive(boolean isActive) {
        active = isActive;
        startTime = System.currentTimeMillis();
        partnerLocked = -1;
    }

    /** S2C from SyncDapHandler.SyncPartnerLockMsg. */
    public static void onPartnerLock(int marker) { partnerLocked = marker; }

    public static boolean isActive() { return active; }

    /** Current marker value 0..100 (triangle / ping-pong wave). */
    public static int currentMarker() {
        long elapsed = System.currentTimeMillis() - startTime;
        double t = (elapsed % PERIOD_MS) / (double) PERIOD_MS;      // 0..1
        double tri = (t < 0.5) ? (t * 2.0) : (1.0 - (t - 0.5) * 2.0); // 0..1..0
        return (int) Math.round(tri * 100.0);
    }

    /** Called on G release; returns the locked value and closes the bar. */
    public static int lockAndClose() {
        int m = currentMarker();
        active = false;
        return m;
    }

    public static void cancel() { active = false; }

    // ---- colors (minimalist, semi-transparent) ----
    private static final int BG      = 0x88101014;
    private static final int MEDIUM  = 0x99E0A020; // amber band
    private static final int PERFECT = 0xCC30D060; // green cube
    private static final int MARK_P  = 0xFFFFFFFF;
    private static final int MARK_M  = 0xFFFFE080;
    private static final int MARK_B  = 0xFFFF6060;

    private static void render(GuiGraphics g, int screenW, int screenH) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int cy = screenH / 2;
        // main bar (local player)
        int mainW = 8, mainH = 92;
        int mainX = screenW / 2 + 34;
        int mainTop = cy - mainH / 2;
        drawBar(g, mainX, mainTop, mainW, mainH, currentMarker(), true);

        // partner bar (smaller, to the right)
        int pW = 5, pH = 66;
        int pX = mainX + mainW + 7;
        int pTop = cy - pH / 2;
        int partnerMarker = (partnerLocked >= 0) ? partnerLocked : currentMarker();
        drawBar(g, pX, pTop, pW, pH, partnerMarker, false);

        // small hint under the main bar
        String hint = "§7release §fG§7 in the zone";
        int tw = mc.font.width(hint);
        g.drawString(mc.font, hint, mainX + mainW / 2 - tw / 2, mainTop + mainH + 5, 0xFFBFBFBF, true);
    }

    /** Draws one bar (background, medium band, perfect cube, marker). */
    private static void drawBar(GuiGraphics g, int x, int top, int w, int h, int marker, boolean main) {
        int bottom = top + h;
        // background
        g.fill(x - 1, top - 1, x + w + 1, bottom + 1, BG);

        int medTop = y(SyncDapHandler.MEDIUM_MAX, top, h);
        int medBot = y(SyncDapHandler.MEDIUM_MIN, top, h);
        g.fill(x, medTop, x + w, medBot, MEDIUM);

        int perfTop = y(SyncDapHandler.PERFECT_MAX, top, h);
        int perfBot = y(SyncDapHandler.PERFECT_MIN, top, h);
        g.fill(x, perfTop, x + w, perfBot, PERFECT);

        int my = y(marker, top, h);
        int col = switch (SyncDapHandler.zoneOf(marker)) {
            case 2 -> MARK_P;
            case 1 -> MARK_M;
            default -> MARK_B;
        };
        int over = main ? 3 : 2;
        g.fill(x - over, my - 1, x + w + over, my + (main ? 2 : 1), col);
    }

    private static int y(int value, int top, int h) {
        return top + h - (int) Math.round(value / 100.0 * h);
    }
}
