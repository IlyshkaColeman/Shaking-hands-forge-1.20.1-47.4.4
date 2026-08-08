package com.cooptest.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Shared stylized HUD text for mechanic prompts.
 */
@OnlyIn(Dist.CLIENT)
public final class HudTextRenderer {

    private HudTextRenderer() {}

    public static void drawCenterPrompt(GuiGraphics g, String text, int centerX, int y, int color, int accentColor) {
        long now = System.currentTimeMillis();
        float scale = 1.08f + (float) Math.sin(now / 155.0) * 0.035f;
        drawCenter(g, text, centerX, y, color, accentColor, scale, 0.0f, true, true, 2);
    }

    public static void drawCenterImpact(GuiGraphics g, String text, int centerX, int y, int color, int accentColor) {
        long now = System.currentTimeMillis();
        float scale = 1.16f + (float) Math.sin(now / 80.0) * 0.07f;
        float shake = (float) Math.sin(now / 33.0) * 1.4f;
        drawCenter(g, text, centerX, y, color, accentColor, scale, shake, true, true, 2);
    }

    public static void drawCenterCompact(GuiGraphics g, String text, int centerX, int y, int color, int accentColor) {
        long now = System.currentTimeMillis();
        float scale = 1.0f + (float) Math.sin(now / 210.0) * 0.018f;
        drawCenter(g, text, centerX, y, color, accentColor, scale, 0.0f, false, false, 1);
    }

    private static void drawCenter(GuiGraphics g, String text, int centerX, int y,
                                   int color, int accentColor, float scale,
                                   float shake, boolean panel, boolean shine, int letterSpacing) {
        if (text == null || text.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int main = ensureAlpha(color);
        int accent = ensureAlpha(accentColor);
        int alpha = alpha(main);

        int spacing = Math.max(0, letterSpacing);
        int width = spacedWidth(font, text, spacing);
        int panelWidth = width + 20;
        int x = -width / 2;
        int textY = -4;
        int shakeX = Math.round(shake);
        int shakeY = Math.round((float) Math.cos(System.currentTimeMillis() / 41.0) * Math.abs(shake) * 0.45f);

        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(centerX + shakeX, y + shakeY, 0.0f);
        pose.scale(scale, scale, 1.0f);

        if (panel) {
            int bgAlpha = clamp(alpha / 2, 45, 150);
            g.fillGradient(-panelWidth / 2, -9, panelWidth / 2, 12,
                    withAlpha(0x050509, bgAlpha), withAlpha(0x111118, bgAlpha + 18));
            g.fill(-panelWidth / 2 + 2, -8, panelWidth / 2 - 2, -7, withAlpha(accent, clamp(alpha, 90, 210)));
            g.fill(-panelWidth / 2 + 1, 11, panelWidth / 2 - 1, 12, withAlpha(0x000000, clamp(alpha / 3, 35, 100)));
        }

        int glow = withAlpha(accent, clamp(alpha / 3, 45, 120));
        int outline = withAlpha(0x050505, clamp(alpha, 110, 235));
        drawSpaced(g, font, text, x - 2, textY, spacing, glow, 0, 0.0f);
        drawSpaced(g, font, text, x + 2, textY, spacing, glow, 0, 0.0f);
        drawSpaced(g, font, text, x, textY - 2, spacing, glow, 0, 0.0f);
        drawSpaced(g, font, text, x, textY + 2, spacing, glow, 0, 0.0f);
        drawSpaced(g, font, text, x - 1, textY, spacing, outline, 0, 0.0f);
        drawSpaced(g, font, text, x + 1, textY, spacing, outline, 0, 0.0f);
        drawSpaced(g, font, text, x, textY - 1, spacing, outline, 0, 0.0f);
        drawSpaced(g, font, text, x, textY + 1, spacing, outline, 0, 0.0f);
        drawSpaced(g, font, text, x, textY, spacing, main, shine ? width : 0, shineStrength(width));
        drawSpaced(g, font, text, x + 1, textY, spacing, main, shine ? width : 0, shineStrength(width));

        pose.popPose();
    }

    private static int spacedWidth(Font font, String text, int spacing) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += font.width(String.valueOf(text.charAt(i)));
            if (i < text.length() - 1) width += spacing;
        }
        return width;
    }

    private static void drawSpaced(GuiGraphics g, Font font, String text, int x, int y,
                                   int spacing, int color, int shineWidth, float globalShine) {
        int cursor = x;
        int shineCenter = shineWidth > 0 ? shineCenter(shineWidth) : Integer.MIN_VALUE;
        int shineRadius = Math.max(12, shineWidth / 7);
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            int charWidth = font.width(ch);
            int drawColor = color;
            if (shineWidth > 0 && charWidth > 0) {
                int charCenter = cursor - x + charWidth / 2;
                int distance = Math.abs(charCenter - shineCenter);
                if (distance < shineRadius) {
                    float local = 1.0f - distance / (float) shineRadius;
                    int shineAlpha = (int) (alpha(color) * local * globalShine);
                    drawColor = mix(drawColor, withAlpha(0xFFFFFF, shineAlpha), local);
                    g.drawString(font, ch, cursor, y - 1, withAlpha(0xFFFFFF, shineAlpha / 2), false);
                }
            }
            g.drawString(font, ch, cursor, y, drawColor, false);
            cursor += charWidth + spacing;
        }
    }

    private static int shineCenter(int width) {
        long now = System.currentTimeMillis();
        int sweep = width + 46;
        return (int) ((now % 950L) / 950.0f * sweep) - 23;
    }

    private static float shineStrength(int width) {
        if (width <= 0) return 0.0f;
        long now = System.currentTimeMillis();
        return 0.65f + (float) Math.sin(now / 120.0) * 0.18f;
    }

    private static int mix(int base, int overlay, float amount) {
        float t = Math.max(0.0f, Math.min(1.0f, amount));
        int ba = (base >>> 24) & 0xFF;
        int br = (base >>> 16) & 0xFF;
        int bg = (base >>> 8) & 0xFF;
        int bb = base & 0xFF;
        int oa = (overlay >>> 24) & 0xFF;
        int or = (overlay >>> 16) & 0xFF;
        int og = (overlay >>> 8) & 0xFF;
        int ob = overlay & 0xFF;
        int a = (int) (ba + (oa - ba) * t);
        int r = (int) (br + (or - br) * t);
        int g = (int) (bg + (og - bg) * t);
        int b = (int) (bb + (ob - bb) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int ensureAlpha(int color) {
        return (color & 0xFF000000) == 0 ? (0xFF000000 | color) : color;
    }

    private static int withAlpha(int color, int alpha) {
        return (clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private static int alpha(int color) {
        return (color >>> 24) & 0xFF;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
