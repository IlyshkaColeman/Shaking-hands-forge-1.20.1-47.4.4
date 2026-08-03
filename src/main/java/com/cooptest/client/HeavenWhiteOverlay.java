package com.cooptest.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * Full-screen white overlay for the Heaven Dap cinematic. Ported from Fabric to Forge
 * 1.20.1. Phases: full white -> fade to 30% (heaven) -> fade back to full -> fade out,
 * driven by {@link #tick()} from HeavenDapClientHandler. The Fabric version also muted
 * master/music volume; that is omitted here (Mojmap volume options are awkward to set)
 * and can be added later.
 */
@OnlyIn(Dist.CLIENT)
public final class HeavenWhiteOverlay {

    private HeavenWhiteOverlay() {}

    private static boolean active = false;
    private static float opacity = 0.0f;
    private static long phaseStartTime = 0;
    private static HeavenPhase currentPhase = HeavenPhase.NONE;

    public enum HeavenPhase { NONE, FULL_WHITE, FADE_TO_THIRTY, HEAVEN, FADE_OUT, FADE_TO_NORMAL, DONE }

    /** HUD overlay (registered from CoopMovesClient). */
    public static final IGuiOverlay HUD = (gui, g, partial, w, h) -> render(g, w, h);

    public static void start() {
        active = true;
        opacity = 1.0f;
        currentPhase = HeavenPhase.FULL_WHITE;
        phaseStartTime = System.currentTimeMillis();
    }

    public static void stop() {
        active = false;
        opacity = 0.0f;
        currentPhase = HeavenPhase.NONE;
    }

    public static void render(GuiGraphics g, int screenWidth, int screenHeight) {
        if (!active || opacity <= 0.0f) return;
        int alpha = (int) (opacity * 255);
        g.fill(0, 0, screenWidth, screenHeight, (alpha << 24) | 0xFFFFFF);
    }

    public static void tick() {
        if (!active) return;
        long elapsed = System.currentTimeMillis() - phaseStartTime;
        switch (currentPhase) {
            case FULL_WHITE -> {
                opacity = 1.0f;
                if (elapsed >= 3000) { currentPhase = HeavenPhase.FADE_TO_THIRTY; phaseStartTime = System.currentTimeMillis(); }
            }
            case FADE_TO_THIRTY -> {
                float progress = Math.min(elapsed / 500.0f, 1.0f);
                opacity = 1.0f - (progress * 0.7f);
                if (progress >= 1.0f) { opacity = 0.3f; currentPhase = HeavenPhase.HEAVEN; phaseStartTime = System.currentTimeMillis(); }
            }
            case HEAVEN -> {
                opacity = 0.3f;
                if (elapsed >= 6000) { currentPhase = HeavenPhase.FADE_OUT; phaseStartTime = System.currentTimeMillis(); }
            }
            case FADE_OUT -> {
                float progress = Math.min(elapsed / 2000.0f, 1.0f);
                opacity = 0.3f + (progress * 0.7f);
                if (progress >= 1.0f) { opacity = 1.0f; currentPhase = HeavenPhase.FADE_TO_NORMAL; phaseStartTime = System.currentTimeMillis(); }
            }
            case FADE_TO_NORMAL -> {
                float progress = Math.min(elapsed / 5000.0f, 1.0f);
                opacity = 1.0f - progress;
                if (progress >= 1.0f) { opacity = 0.0f; currentPhase = HeavenPhase.DONE; active = false; }
            }
            case DONE -> { opacity = 0.0f; active = false; }
            default -> { }
        }
    }

    public static boolean isActive() { return active; }
    public static float getOpacity() { return opacity; }
}
