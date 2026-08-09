package com.cooptest.client;

import com.cooptest.SyncDapHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * Client side of the synchronized dap skill-check.
 *
 * The new version uses one shared horizontal timing rail:
 * gray fail zones on the edges, yellow normal zones, green power zones and one red
 * perfect zone in the center. The marker ping-pongs left/right until the local
 * player releases G. After release, the local marker stays frozen while the partner
 * finishes their timing.
 */
@OnlyIn(Dist.CLIENT)
public final class SyncDapClientHandler {

    private SyncDapClientHandler() {}

    /** Full left -> right -> left cycle, ms. Lower = harder. */
    private static final long PERIOD_MS = 1750L;

    private static boolean active = false;
    private static long startTime = 0L;
    private static boolean localLocked = false;
    private static int localLockedMarker = -1;
    private static int partnerLocked = -1; // -1 = partner still choosing

    public static final IGuiOverlay HUD = (gui, g, partial, w, h) -> render(g, w, h);

    public static void onActive(boolean isActive) {
        active = isActive;
        startTime = System.currentTimeMillis();
        localLocked = false;
        localLockedMarker = -1;
        partnerLocked = -1;
    }

    public static void onPartnerLock(int marker) {
        partnerLocked = Math.max(0, Math.min(100, marker));
    }

    public static boolean isActive() { return active; }

    public static int currentMarker() {
        long elapsed = System.currentTimeMillis() - startTime;
        double t = (elapsed % PERIOD_MS) / (double) PERIOD_MS;
        double tri = (t < 0.5) ? (t * 2.0) : (1.0 - (t - 0.5) * 2.0);
        return (int) Math.round(tri * 100.0);
    }

    public static int lockAndClose() {
        int m = currentMarker();
        localLocked = true;
        localLockedMarker = m;
        return m;
    }

    public static void cancel() {
        active = false;
        localLocked = false;
        localLockedMarker = -1;
        partnerLocked = -1;
    }

    private static void render(GuiGraphics g, int screenW, int screenH) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int barW = Math.min(260, screenW - 70);
        int barH = 18;
        int x = (screenW - barW) / 2;
        int y = screenH / 2 - 72;

        int marker = localLocked ? localLockedMarker : currentMarker();
        int zone = SyncDapHandler.zoneOf(marker);

        drawPanel(g, x, y, barW, barH);
        drawZones(g, x, y, barW, barH);
        drawMarker(g, x, y, barW, barH, marker, markerColor(zone), true);
        if (partnerLocked >= 0) {
            drawMarker(g, x, y, barW, barH, partnerLocked, 0xFF5CEBFF, false);
        }

        String title = localLocked ? "LOCKED — WAIT FOR HOMIE" : "RELEASE G ON THE SAME COLOR";
        int titleColor = localLocked ? 0xFFFFF0A0 : 0xFFFFFFFF;
        HudTextRenderer.drawCenterCompact(g, title, screenW / 2, y - 20, titleColor, 0xFF000000);

        String zoneText = switch (zone) {
            case 3 -> "RED PERFECT";
            case 2 -> "GREEN POWER";
            case 1 -> "YELLOW DAP";
            default -> "GRAY FAIL";
        };
        int labelY = y + barH + 12;
        HudTextRenderer.drawCenterCompact(g, zoneText, screenW / 2, labelY, markerColor(zone), 0xFF000000);

        if (localLocked) {
            HudTextRenderer.drawCenterCompact(g, "YOU", x + valueToX(marker, barW), y - 8, 0xFFFFF0A0, 0xFF000000);
        }
        if (partnerLocked >= 0) {
            HudTextRenderer.drawCenterCompact(g, "P2", x + valueToX(partnerLocked, barW), y + barH + 26, 0xFF5CEBFF, 0xFF000000);
        }
    }

    private static void drawPanel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 7, y - 7, x + w + 7, y + h + 7, 0xB0000000);
        g.fill(x - 5, y - 5, x + w + 5, y + h + 5, 0xFF090A0D);
        g.fill(x - 3, y - 3, x + w + 3, y + h + 3, 0xFF343947);
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF10131A);
    }

    private static void drawZones(GuiGraphics g, int x, int y, int w, int h) {
        segment(g, x, y, w, h, 0, SyncDapHandler.LEFT_GRAY_MAX, 0xFF3B3D45, 0xFF565A64);
        segment(g, x, y, w, h, SyncDapHandler.LEFT_GRAY_MAX, SyncDapHandler.LEFT_YELLOW_MAX, 0xFFE2A72A, 0xFFFFD85A);
        segment(g, x, y, w, h, SyncDapHandler.LEFT_YELLOW_MAX, SyncDapHandler.LEFT_GREEN_MAX, 0xFF1FB954, 0xFF61FF8A);
        segment(g, x, y, w, h, SyncDapHandler.RED_MIN, SyncDapHandler.RED_MAX, 0xFFFF2727, 0xFFFF7A3D);
        segment(g, x, y, w, h, SyncDapHandler.RED_MAX, SyncDapHandler.RIGHT_GREEN_MAX, 0xFF61FF8A, 0xFF1FB954);
        segment(g, x, y, w, h, SyncDapHandler.RIGHT_GREEN_MAX, SyncDapHandler.RIGHT_YELLOW_MAX, 0xFFFFD85A, 0xFFE2A72A);
        segment(g, x, y, w, h, SyncDapHandler.RIGHT_YELLOW_MAX, 100, 0xFF565A64, 0xFF3B3D45);

        // subtle shine and center cut lines
        g.fill(x, y, x + w, y + 1, 0x99FFFFFF);
        g.fill(x, y + h - 1, x + w, y + h, 0xAA000000);
        int redL = x + valueToX(SyncDapHandler.RED_MIN, w);
        int redR = x + valueToX(SyncDapHandler.RED_MAX, w);
        g.fill(redL - 1, y - 2, redL, y + h + 2, 0xEEFFFFFF);
        g.fill(redR, y - 2, redR + 1, y + h + 2, 0xEEFFFFFF);
    }

    private static void segment(GuiGraphics g, int x, int y, int w, int h, int from, int to, int leftColor, int rightColor) {
        int x1 = x + valueToX(from, w);
        int x2 = x + valueToX(to, w);
        if (x2 <= x1) x2 = x1 + 1;
        int mid = (x1 + x2) / 2;
        g.fill(x1, y, mid, y + h, leftColor);
        g.fill(mid, y, x2, y + h, rightColor);
    }

    private static void drawMarker(GuiGraphics g, int x, int y, int w, int h, int value, int color, boolean local) {
        int mx = x + valueToX(value, w);
        int top = y - (local ? 8 : 5);
        int bottom = y + h + (local ? 8 : 5);
        int half = local ? 3 : 2;
        g.fill(mx - half - 2, top, mx + half + 2, bottom, 0xFF000000);
        g.fill(mx - half, top + 1, mx + half, bottom - 1, color);
        g.fill(mx - 1, top - 2, mx + 1, bottom + 2, 0xFFFFFFFF);
    }

    private static int markerColor(int zone) {
        return switch (zone) {
            case 3 -> 0xFFFF3D2E;
            case 2 -> 0xFF65FF8F;
            case 1 -> 0xFFFFE36A;
            default -> 0xFFB8BBC4;
        };
    }

    private static int valueToX(int value, int w) {
        return (int) Math.round(Math.max(0, Math.min(100, value)) / 100.0 * w);
    }
}
