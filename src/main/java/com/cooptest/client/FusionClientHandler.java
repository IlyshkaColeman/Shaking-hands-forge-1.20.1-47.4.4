package com.cooptest.client;

import com.cooptest.DapFusionHandler;
import com.cooptest.QTEManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.UUID;

/**
 * Dap Fusion / perfect-combo QTE client state. Ported from Fabric to Forge 1.20.1
 * (reduced). The server drives the QTE bar, phase, fused state and black-screen via
 * DapFusionHandler messages; the HUD timing bar, black-screen overlay and G/H/J
 * input routing land with the client-render / dap-input stage (Stage 6).
 * DapFusionHandler exposes sendGPress/sendUnfuse for that input handler.
 */
@OnlyIn(Dist.CLIENT)
public final class FusionClientHandler {

    private FusionClientHandler() {}

    private static boolean qteOpen = false;
    private static int phase = -1;
    private static boolean fused = false;
    private static boolean blackScreen = false;
    private static long blackScreenStart = 0L;
    private static String expectedButton = null;
    private static int qteStage = 0;
    private static int qteType = 0;
    private static long qteReceivedAt = 0L;
    private static long windowStart = 0L;
    private static long windowEnd = 0L;
    private static boolean pressed = false;
    private static long gWindowStart = 0L;
    private static long gWindowEnd = 0L;

    public static final IGuiOverlay HUD = (gui, graphics, partialTick, width, height) ->
            renderHud(graphics, width, height);

    public static void onFusionQTE(UUID playerId, String button, int stage,
                                   long windowStartMs, long windowEndMs, boolean open, int type) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.getUUID().equals(playerId)) return;
        qteOpen = open;
        if (!open) {
            expectedButton = null;
            pressed = false;
            return;
        }
        long now = System.currentTimeMillis();
        expectedButton = button;
        qteStage = stage;
        qteType = type;
        qteReceivedAt = now;
        windowStart = now + windowStartMs;
        windowEnd = now + windowEndMs;
        pressed = false;
    }

    public static void onFusionPhase(UUID p1, UUID p2, int ph) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        UUID localId = mc.player.getUUID();
        if (!localId.equals(p1) && !localId.equals(p2)) return;
        phase = ph;
        if (ph == 0) {
            long now = System.currentTimeMillis();
            gWindowStart = now + DapFusionHandler.FUSION_G_WINDOW_START;
            gWindowEnd = now + DapFusionHandler.FUSION_G_WINDOW_END;
        }
        if (ph == 99 || ph == 4) qteOpen = false;
    }

    public static void onFusionFused(boolean isFused) {
        fused = isFused;
    }

    public static void onBlackScreen(boolean active) {
        blackScreen = active;
        if (active) blackScreenStart = System.currentTimeMillis();
    }

    public static boolean isQTEOpen() { return qteOpen; }

    public static boolean isGWindowOpen() {
        return phase == 0 && System.currentTimeMillis() <= gWindowEnd;
    }

    public static boolean isFused() { return fused; }

    public static boolean isBlackScreen() { return blackScreen; }

    public static boolean handleGPress() {
        if (!isGWindowOpen()) return false;
        long now = System.currentTimeMillis();
        if (now < gWindowStart || now > gWindowEnd) return true;
        DapFusionHandler.sendGPress();
        phase = -1;
        return true;
    }

    public static boolean handleQTEPress(String button) {
        if (!qteOpen) return false;
        if (!button.equals(expectedButton)) return true;
        pressed = true;
        QTEManager.sendButtonPress(button);
        return true;
    }

    public static void handleQTEHPress() {
        handleQTEPress("H");
    }

    private static void renderHud(GuiGraphics graphics, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        long now = System.currentTimeMillis();

        if (blackScreen) {
            float progress = Math.min(1.0f, (now - blackScreenStart) / 500.0f);
            int alpha = (int) (255 * progress);
            graphics.fill(0, 0, screenWidth, screenHeight, (alpha << 24));
            return;
        }

        if (isGWindowOpen()) {
            String text = now < gWindowStart ? "FUSION READY" : "[G] FUSION";
            int color = now < gWindowStart ? 0xFFFFAA00 : 0xFFFFFF55;
            HudTextRenderer.drawCenterImpact(graphics, text, screenWidth / 2, screenHeight / 2 + 34,
                    color, now < gWindowStart ? 0xFFFF6A00 : 0xFF7CFFB2);
        }

        if (!qteOpen || expectedButton == null) return;
        int width = 100;
        int height = 5;
        int x = (screenWidth - width) / 2;
        int y = screenHeight / 2 + 48;
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xCC000000);
        graphics.fill(x, y, x + width, y + height, 0xFF333333);

        if (qteType == 1) {
            float greenStart = (float) (windowStart - qteReceivedAt) / DapFusionHandler.TIMING_BAR_TOTAL_MS;
            float greenEnd = (float) (windowEnd - qteReceivedAt) / DapFusionHandler.TIMING_BAR_TOTAL_MS;
            int gx1 = x + (int) (Math.max(0.0f, Math.min(1.0f, greenStart)) * width);
            int gx2 = x + (int) (Math.max(0.0f, Math.min(1.0f, greenEnd)) * width);
            graphics.fill(gx1, y, Math.max(gx1 + 1, gx2), y + height, 0xFF22BB22);
            float dot = (float) (now - qteReceivedAt) / DapFusionHandler.TIMING_BAR_TOTAL_MS;
            int dotX = x + (int) (Math.max(0.0f, Math.min(1.0f, dot)) * width);
            graphics.fill(dotX - 1, y - 2, dotX + 1, y + height + 2, 0xFFFFFFFF);
        } else {
            float remaining = 1.0f - (float) (now - qteReceivedAt) / Math.max(1L, windowEnd - qteReceivedAt);
            int fill = (int) (Math.max(0.0f, Math.min(1.0f, remaining)) * width);
            if (fill > 0) graphics.fill(x, y, x + fill, y + height, 0xFF22BB22);
        }

        String prompt = pressed ? "OK" : "[" + expectedButton + "]  " + qteStage + "/10";
        HudTextRenderer.drawCenterImpact(graphics, prompt, screenWidth / 2, y - 11,
                pressed ? 0xFF55FF55 : 0xFFFFFFFF,
                pressed ? 0xFF2DFF82 : 0xFFB66BFF);
    }
}
