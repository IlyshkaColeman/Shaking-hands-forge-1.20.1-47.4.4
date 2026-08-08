package com.cooptest.client;

import com.cooptest.SyncDapHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * Client side of the synchronized ping-pong dap. Draws a polished gradient bar for the
 * local player (medium band + perfect cube + bouncing marker) and a smaller, clearly
 * separated bar for the partner to its right. Releasing G locks the local marker
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

    public static void onActive(boolean isActive) {
        active = isActive;
        startTime = System.currentTimeMillis();
        partnerLocked = -1;
    }

    public static void onPartnerLock(int marker) { partnerLocked = marker; }

    public static boolean isActive() { return active; }

    public static int currentMarker() {
        long elapsed = System.currentTimeMillis() - startTime;
        double t = (elapsed % PERIOD_MS) / (double) PERIOD_MS;
        double tri = (t < 0.5) ? (t * 2.0) : (1.0 - (t - 0.5) * 2.0);
        return (int) Math.round(tri * 100.0);
    }

    public static int lockAndClose() {
        int m = currentMarker();
        active = false;
        return m;
    }

    public static void cancel() { active = false; }

    private static void render(GuiGraphics g, int screenW, int screenH) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int cy = screenH / 2;

        // main bar (local player)
        int mainW = 11, mainH = 104;
        int mainX = screenW / 2 + 44;
        int mainTop = cy - mainH / 2;
        drawBar(g, mainX, mainTop, mainW, mainH, currentMarker(), true);
        label(g, mc, "YOU", mainX + mainW / 2, mainTop - 11);

        // partner bar (smaller, clearly separated to the right)
        int pW = 7, pH = 78;
        int pX = mainX + mainW + 18;
        int pTop = cy - pH / 2;
        int partnerMarker = (partnerLocked >= 0) ? partnerLocked : currentMarker();
        drawBar(g, pX, pTop, pW, pH, partnerMarker, false);
        label(g, mc, "P2", pX + pW / 2, pTop - 11);

        HudTextRenderer.drawCenterCompact(g, "RELEASE G IN THE ZONE",
                mainX + mainW / 2, mainTop + mainH + 8, 0xFFE8E8E8, 0xFF63FF8F);
    }

    private static void label(GuiGraphics g, Minecraft mc, String s, int centerX, int y) {
        HudTextRenderer.drawCenterCompact(g, s, centerX, y, 0xFFB0B0B8, 0xFF5CEBFF);
    }

    /** Draws one bar with a frame, gradient background, gradient zones and a marker. */
    private static void drawBar(GuiGraphics g, int x, int top, int w, int h, int marker, boolean main) {
        int bottom = top + h;
        // outer frame + inner background gradient
        g.fill(x - 2, top - 2, x + w + 2, bottom + 2, 0xFF0A0A0C);
        g.fill(x - 1, top - 1, x + w + 1, bottom + 1, 0xFF2A2A32);
        g.fillGradient(x, top, x + w, bottom, 0xFF3A3A44, 0xFF16161A);

        // medium (amber) band — gradient
        int medTop = y(SyncDapHandler.MEDIUM_MAX, top, h);
        int medBot = y(SyncDapHandler.MEDIUM_MIN, top, h);
        g.fillGradient(x, medTop, x + w, medBot, 0xEEF2C24E, 0xEEB9821A);

        // perfect (green) cube — gradient + bright edges
        int perfTop = y(SyncDapHandler.PERFECT_MAX, top, h);
        int perfBot = y(SyncDapHandler.PERFECT_MIN, top, h);
        g.fillGradient(x, perfTop, x + w, perfBot, 0xFF7BFFA6, 0xFF25C457);
        g.fill(x, perfTop - 1, x + w, perfTop, 0xFFFFFFFF);
        g.fill(x, perfBot, x + w, perfBot + 1, 0xFFFFFFFF);

        // marker: dark outline + bright core, extends past the bar edges
        int my = y(marker, top, h);
        int over = main ? 4 : 3;
        int th = main ? 2 : 1;
        int core = switch (SyncDapHandler.zoneOf(marker)) {
            case 2 -> 0xFFFFFFFF;
            case 1 -> 0xFFFFF0A0;
            default -> 0xFFFF7A7A;
        };
        g.fill(x - over, my - th - 1, x + w + over, my + th + 1, 0xFF000000); // outline
        g.fill(x - over, my - th, x + w + over, my + th, core);               // core
    }

    private static int y(int value, int top, int h) {
        return top + h - (int) Math.round(value / 100.0 * h);
    }
}
