package com.cooptest.client;

import com.cooptest.SyncDapHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * Client side of the synchronized ping-pong dap: shows the vertical bar with the
 * medium band + perfect cube and a marker that bounces up/down while the pair is
 * engaged. The local player releases G to lock the marker (handled in
 * {@link ChargedDapClientHandler}); this class only tracks the active window,
 * computes the current marker and draws the HUD.
 */
@OnlyIn(Dist.CLIENT)
public final class SyncDapClientHandler {

    private SyncDapClientHandler() {}

    /** Full up+down cycle of the marker, ms. Lower = harder. */
    private static final long PERIOD_MS = 1600;

    private static boolean active = false;
    private static long startTime = 0;

    public static final IGuiOverlay HUD = (gui, g, partial, w, h) -> render(g, w, h);

    /** S2C from SyncDapHandler.SyncActiveMsg. */
    public static void onActive(boolean isActive) {
        active = isActive;
        startTime = System.currentTimeMillis();
    }

    public static boolean isActive() { return active; }

    /** Current marker value 0..100 (triangle / ping-pong wave). */
    public static int currentMarker() {
        long elapsed = System.currentTimeMillis() - startTime;
        double t = (elapsed % PERIOD_MS) / (double) PERIOD_MS; // 0..1
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

    private static void render(GuiGraphics g, int screenW, int screenH) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int barW = 12;
        int barH = 110;
        int barX = screenW / 2 + 50;
        int barTop = screenH / 2 - barH / 2;
        int barBottom = barTop + barH;

        // frame + background
        g.fill(barX - 2, barTop - 2, barX + barW + 2, barBottom + 2, 0xCC000000);
        g.fill(barX, barTop, barX + barW, barBottom, 0xFF202020);

        // helper to map value 0..100 -> y (0 = bottom, 100 = top)
        // medium band
        int medTopY = valueToY(SyncDapHandler.MEDIUM_MAX, barTop, barH);
        int medBotY = valueToY(SyncDapHandler.MEDIUM_MIN, barTop, barH);
        g.fill(barX, medTopY, barX + barW, medBotY, 0xFFB8A000); // amber "good" band

        // perfect cube
        int perfTopY = valueToY(SyncDapHandler.PERFECT_MAX, barTop, barH);
        int perfBotY = valueToY(SyncDapHandler.PERFECT_MIN, barTop, barH);
        g.fill(barX - 2, perfTopY, barX + barW + 2, perfBotY, 0xFF00E000); // green perfect cube
        g.fill(barX - 3, perfTopY - 1, barX + barW + 3, perfTopY, 0xFFFFFFFF);
        g.fill(barX - 3, perfBotY, barX + barW + 3, perfBotY + 1, 0xFFFFFFFF);

        // moving marker
        int marker = currentMarker();
        int my = valueToY(marker, barTop, barH);
        int col = switch (SyncDapHandler.zoneOf(marker)) {
            case 2 -> 0xFFFFFFFF;
            case 1 -> 0xFFFFF080;
            default -> 0xFFFF5050;
        };
        g.fill(barX - 4, my - 1, barX + barW + 4, my + 2, col);

        // hint
        String hint = "§7Release §eG§7 in the zone";
        int tw = mc.font.width(hint);
        g.drawString(mc.font, hint, barX + barW / 2 - tw / 2, barBottom + 6, 0xFFFFFFFF, true);
    }

    private static int valueToY(int value, int barTop, int barH) {
        return barTop + barH - (int) Math.round(value / 100.0 * barH);
    }
}
