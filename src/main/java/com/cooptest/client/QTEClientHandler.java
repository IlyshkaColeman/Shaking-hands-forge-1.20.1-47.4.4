package com.cooptest.client;

import com.cooptest.QTEManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

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

    public static final IGuiOverlay HUD = (gui, graphics, partialTick, width, height) ->
            renderHud(graphics, width, height);

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

    private static void renderHud(GuiGraphics graphics, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.player == null || mc.options.hideGui || expectedButton == null) return;

        long now = System.currentTimeMillis();
        int barWidth = 90;
        int barHeight = 5;
        int x = (screenWidth - barWidth) / 2;
        int y = screenHeight / 2 + 28;
        graphics.fill(x - 1, y - 1, x + barWidth + 1, y + barHeight + 1, 0xCC000000);
        graphics.fill(x, y, x + barWidth, y + barHeight, 0xFF333333);

        int color;
        int fill;
        if (now < windowStart) {
            fill = barWidth;
            color = 0xFFFFAA00;
        } else if (now <= windowEnd) {
            float remaining = 1.0f - (float) (now - windowStart) / Math.max(1L, windowEnd - windowStart);
            fill = Math.max(0, Math.min(barWidth, (int) (barWidth * remaining)));
            color = pressedThisWindow ? 0xFF44FF44 : 0xFF22CC22;
        } else {
            fill = 0;
            color = 0xFFCC2222;
        }
        if (fill > 0) graphics.fill(x, y, x + fill, y + barHeight, color);

        String prompt = "[" + expectedButton + "]";
        HudTextRenderer.drawCenterImpact(graphics, prompt, screenWidth / 2, y - 11,
                pressedThisWindow ? 0xFF66FF66 : 0xFFFFFFFF,
                pressedThisWindow ? 0xFF2DFF82 : 0xFF5CEBFF);
        if (maxStages > 1) {
            String progress = stage + "/" + maxStages;
            HudTextRenderer.drawCenterCompact(graphics, progress, screenWidth / 2, y + 12,
                    0xFFDDDDDD, 0xFF777777);
        }
    }
}
