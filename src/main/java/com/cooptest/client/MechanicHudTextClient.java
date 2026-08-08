package com.cooptest.client;

import com.cooptest.MechanicHudText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@OnlyIn(Dist.CLIENT)
public final class MechanicHudTextClient {

    private MechanicHudTextClient() {}

    private static String title = "";
    private static String subtitle = "";
    private static int style = MechanicHudText.INFO;
    private static long startMs = 0L;
    private static long endMs = 0L;

    public static final IGuiOverlay HUD = (gui, g, partialTick, width, height) ->
            render(g, width, height);

    public static void show(String title, String subtitle, int style, long durationMs) {
        MechanicHudTextClient.title = title == null ? "" : title;
        MechanicHudTextClient.subtitle = subtitle == null ? "" : subtitle;
        MechanicHudTextClient.style = style;
        MechanicHudTextClient.startMs = System.currentTimeMillis();
        MechanicHudTextClient.endMs = startMs + Math.max(450L, durationMs);
    }

    public static void danger(String title, String subtitle) {
        show(title, subtitle, MechanicHudText.DANGER, 1450L);
    }

    public static void warning(String title, String subtitle) {
        show(title, subtitle, MechanicHudText.WARNING, 1400L);
    }

    public static void success(String title, String subtitle) {
        show(title, subtitle, MechanicHudText.SUCCESS, 1550L);
    }

    public static void info(String title, String subtitle) {
        show(title, subtitle, MechanicHudText.INFO, 1350L);
    }

    public static boolean legacy(Component component) {
        return legacy(component == null ? "" : component.getString());
    }

    public static boolean legacy(String raw) {
        LegacyText text = LegacyText.from(raw);
        if (text == null || text.title.isEmpty()) return false;
        show(text.title, text.subtitle, text.style, text.durationMs);
        return true;
    }

    private static void render(GuiGraphics g, int width, int height) {
        long now = System.currentTimeMillis();
        if (now >= endMs || title.isEmpty()) return;

        long duration = Math.max(1L, endMs - startMs);
        float age = (now - startMs) / (float) duration;
        float fadeIn = Math.min(1.0f, (now - startMs) / 110.0f);
        float fadeOut = Math.min(1.0f, (endMs - now) / 220.0f);
        int alpha = Math.max(0, Math.min(255, (int) (255 * Math.min(fadeIn, fadeOut))));
        int alphaMask = Math.max(1, alpha) << 24;

        int main;
        int accent;
        boolean impact;
        switch (style) {
            case MechanicHudText.DANGER -> {
                main = alphaMask | 0xFF3333;
                accent = alphaMask | 0xB00000;
                impact = true;
            }
            case MechanicHudText.WARNING -> {
                main = alphaMask | 0xFF9A22;
                accent = alphaMask | 0xFF3C00;
                impact = false;
            }
            case MechanicHudText.SUCCESS -> {
                main = alphaMask | 0x6DFF9A;
                accent = alphaMask | 0x00D66A;
                impact = false;
            }
            case MechanicHudText.EPIC -> {
                main = alphaMask | 0xFF5AFF;
                accent = alphaMask | 0x7D3CFF;
                impact = true;
            }
            default -> {
                main = alphaMask | 0xE8ECFF;
                accent = alphaMask | 0x5CEBFF;
                impact = false;
            }
        }

        int y = height / 2 + 54 + (int) (Math.sin(age * Math.PI) * 3.0f);
        if (impact) {
            HudTextRenderer.drawCenterImpact(g, title, width / 2, y, main, accent);
        } else {
            HudTextRenderer.drawCenterPrompt(g, title, width / 2, y, main, accent);
        }

        if (!subtitle.isEmpty()) {
            HudTextRenderer.drawCenterCompact(g, subtitle, width / 2, y + 18,
                    alphaMask | 0xD8D8E8, accent);
        }
    }

    private record LegacyText(String title, String subtitle, int style, long durationMs) {
        private static final Pattern SECTION_CODE = Pattern.compile("§.");
        private static final Pattern EXTRA_SPACES = Pattern.compile("\\s+");
        private static final Pattern COOLDOWN = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:s|sec|seconds?)", Pattern.CASE_INSENSITIVE);

        static LegacyText from(String raw) {
            String clean = clean(raw);
            if (clean.isEmpty()) return null;

            String lower = clean.toLowerCase(Locale.ROOT);
            String cooldown = cooldown(lower);

            if (lower.contains("kick cooldown")) {
                return new LegacyText("KICK LOCKED", cooldown.isEmpty() ? "cooldown active" : cooldown + " cooldown",
                        MechanicHudText.DANGER, 1450L);
            }
            if (lower.contains("catch missed")) {
                return new LegacyText("CATCH MISSED", cooldown.isEmpty() ? "reset your timing" : cooldown + " cooldown",
                        MechanicHudText.DANGER, 1450L);
            }
            if (lower.contains("left hanging")) {
                return new LegacyText("LEFT HANGING", "no homie answered", MechanicHudText.INFO, 1350L);
            }
            if (lower.contains("waiting for a partner") || lower.contains("waiting for homie")) {
                return new LegacyText("WAITING FOR HOMIE", "partner must hold G", MechanicHudText.INFO, 1350L);
            }
            if (lower.contains("whiff")) {
                return new LegacyText("WHIFF!", cooldown.isEmpty() ? "missed the hit" : cooldown + " cooldown",
                        MechanicHudText.DANGER, 1450L);
            }
            if (lower.contains("wrong button")) {
                return new LegacyText("WRONG BUTTON", "combo broke", MechanicHudText.DANGER, 1450L);
            }
            if (lower.contains("too early")) {
                return new LegacyText("TOO EARLY", "wait for the marker", MechanicHudText.DANGER, 1450L);
            }
            if (lower.contains("too late") || lower.contains("too slow")) {
                return new LegacyText("TOO LATE", "timing window closed", MechanicHudText.DANGER, 1450L);
            }
            if (lower.contains("too early/late")) {
                return new LegacyText("BAD TIMING", "hit the window", MechanicHudText.DANGER, 1450L);
            }
            if (lower.contains("not facing")) {
                return new LegacyText("FACE EACH OTHER", "keep eye contact", MechanicHudText.DANGER, 1450L);
            }
            if (lower.contains("look at partner")) {
                return new LegacyText("LOCK EYES", "look at partner", MechanicHudText.DANGER, 1450L);
            }
            if (lower.contains("empty hand")) {
                return new LegacyText("EMPTY HANDS", "needed for this move", MechanicHudText.DANGER, 1450L);
            }
            if (lower.contains("cooldown")) {
                return new LegacyText("COOLDOWN", cooldown.isEmpty() ? compact(clean) : cooldown + " remaining",
                        MechanicHudText.DANGER, 1450L);
            }
            if (lower.contains("missed") || lower.contains("failed") || lower.contains("release not synced")) {
                return new LegacyText("MISSED", compact(clean), MechanicHudText.DANGER, 1450L);
            }
            if (lower.contains("perfect") || lower.contains("legendary") || lower.contains("divine")
                    || lower.contains("triple") || lower.contains("combo") || lower.contains("shockwave")
                    || lower.contains("fire dap") || lower.contains("heaven") || lower.contains("my boy")) {
                return new LegacyText(impactTitle(clean), impactSubtitle(clean), MechanicHudText.EPIC, 1700L);
            }
            if (lower.contains("ready") || lower.contains("joined") || lower.contains("wahoo")
                    || lower.contains("saved") || lower.contains("caught")) {
                return new LegacyText(impactTitle(clean), impactSubtitle(clean), MechanicHudText.SUCCESS, 1550L);
            }
            if (lower.contains("wait") || lower.contains("too hungry") || lower.contains("dropped")
                    || lower.contains("died") || lower.contains("bonk") || lower.contains("squashed")
                    || lower.contains("curse")) {
                return new LegacyText(impactTitle(clean), impactSubtitle(clean), MechanicHudText.WARNING, 1450L);
            }
            return null;
        }

        private static String clean(String raw) {
            if (raw == null) return "";
            String s = SECTION_CODE.matcher(raw).replaceAll("");
            s = s.replace('✗', ' ').replace('✖', ' ').replace('❌', ' ');
            s = s.replace("❤", "").replace("⚡", "").replace("✨", "").replace("🔥", "")
                    .replace("☠", "").replace("☄", "").replace("🚀", "").replace("💥", "")
                    .replace("💀", "").replace("🛡", "").replace("✦", "").replace("★", "");
            s = s.replace("[", "").replace("]", "").replace("*", "");
            return EXTRA_SPACES.matcher(s).replaceAll(" ").trim();
        }

        private static String cooldown(String lower) {
            Matcher m = COOLDOWN.matcher(lower.replace(',', '.'));
            if (!m.find()) return "";
            String value = m.group(1).replace(',', '.');
            if (!value.contains(".")) value = value + ".0";
            return value + "s";
        }

        private static String impactTitle(String clean) {
            String s = clean;
            int bang = s.indexOf('!');
            if (bang > 0 && bang <= 26) s = s.substring(0, bang);
            int dash = s.indexOf(" - ");
            if (dash > 0 && dash <= 26) s = s.substring(0, dash);
            int paren = s.indexOf('(');
            if (paren > 0 && paren <= 26) s = s.substring(0, paren);
            s = compact(s).toUpperCase(Locale.ROOT);
            if (s.length() > 28) s = s.substring(0, 28).trim();
            return s;
        }

        private static String impactSubtitle(String clean) {
            String s = clean;
            int bang = s.indexOf('!');
            if (bang >= 0 && bang + 1 < s.length()) return compact(s.substring(bang + 1));
            int dash = s.indexOf(" - ");
            if (dash >= 0 && dash + 3 < s.length()) return compact(s.substring(dash + 3));
            int paren = s.indexOf('(');
            if (paren >= 0) return compact(s.substring(paren));
            return "";
        }

        private static String compact(String s) {
            s = EXTRA_SPACES.matcher(s == null ? "" : s).replaceAll(" ").trim();
            return s.length() > 46 ? s.substring(0, 46).trim() : s;
        }
    }
}
