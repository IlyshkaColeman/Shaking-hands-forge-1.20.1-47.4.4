package com.cooptest.client;

import com.cooptest.ChargedDapHandler;
import com.cooptest.CoopMovesConfig;
import com.cooptest.CoopNetwork;
import com.cooptest.DapFusionHandler;
import com.cooptest.ModSounds;
import com.cooptest.QTEManager;
import com.cooptest.SyncDapHandler;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Charged-dap client input + HUD. Ported to Forge 1.20.1.
 *
 * Input: G (empty-hand hold) charges/releases the dap; J presses the fire-dap combo.
 * HUD (Forge {@link IGuiOverlay}, registered in CoopMovesClient): the center charge
 * bar with fire overcharge + heaven-ready shake/crack, partner mini-bars, the whiff
 * cooldown bar, the "PRESS J!" fire-combo prompt, and the tier result flash + tier
 * particles.
 *
 * The Fabric texture-based cinematic impact frames (perfect/facing/drop-kick full
 * screen flashes) are omitted pending their GUI textures; they are not part of the
 * gameplay HUD.
 */
@OnlyIn(Dist.CLIENT)
public final class ChargedDapClientHandler {

    private ChargedDapClientHandler() {}

    private static KeyMapping dapKey;
    private static KeyMapping fireComboKey;

    private static boolean wasDapKeyDown = false;
    private static boolean wasFireKeyDown = false;
    private static boolean localCharging = false;
    private static boolean syncEngaged = false;
    private static long localChargeStartTime = 0;

    private static boolean inFaceDapSession = false;
    private static boolean playerFrozen = false;

    private static long whiffCooldownEnd = 0;
    private static long dapBadBlockEnd = 0;

    private static boolean inFireComboWindow = false;
    private static long fireComboWindowStart = 0;
    private static final long FIRE_COMBO_WINDOW_MS = 2200;
    /** Server only accepts the J press from this point on (mirrors FIRE_J_WINDOW_START).
     *  Pressing earlier must NOT close the prompt, or the combo becomes impossible. */
    private static final long FIRE_COMBO_WINDOW_START_MS = 830;

    private static long flashStartTime = 0;
    private static int resultTier = 0;
    private static boolean resultPerfect = false;

    private static long heavenReadyStartTime = 0;
    private static long lastFireChargeSoundMs = 0;
    private static int fireChargeAudioStage = 0;

    // cinematic impact frames (textures under assets/testcoop/textures/gui/impact/)
    private static boolean perfectImpactActive = false;
    private static long perfectImpactStartTime = 0;
    private static int perfectDapImpactFrame = 0;
    private static long perfectDapImpactFrameStartTime = 0;
    private static boolean facingDapImpactActive = false;
    private static long facingDapImpactStartMs = 0;

    private static final ResourceLocation IMPACT1 = tex("impact1");
    private static final ResourceLocation IMPACT2 = tex("impact2");
    private static final ResourceLocation IMPACT3 = tex("impact3");
    private static final ResourceLocation FRAME0 = tex("frame0");
    private static final ResourceLocation FRAME1 = tex("frame1");
    private static final ResourceLocation FRAME2 = tex("frame2");
    private static final ResourceLocation FRAME3 = tex("frame3");
    private static final ResourceLocation IMPAC7 = tex("impac7");
    private static final ResourceLocation IMPAC8 = tex("impac8");
    private static final ResourceLocation IMPAC9 = tex("impac9");

    private static ResourceLocation tex(String name) {
        return new ResourceLocation("testcoop", "textures/gui/impact/" + name + ".png");
    }

    private static final long CHARGE_TIME_MS = 250;
    private static final long FLASH_DURATION = 500;

    /** Charge / fire progress broadcast from server, keyed by player (present == charging). */
    public static final Map<UUID, Float> chargeProgress = new HashMap<>();
    public static final Map<UUID, Float> fireProgress = new HashMap<>();
    public static final Set<UUID> heavenReady = new HashSet<>();

    /** HUD overlay instance (registered from CoopMovesClient). */
    public static final IGuiOverlay HUD = (gui, g, partial, w, h) -> renderHud(g, w, h);

    // -------------------------------------------------------------- registration
    public static void registerKeyBindings(RegisterKeyMappingsEvent event) {
        dapKey = new KeyMapping("key.coopmoves.dap", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.coopmoves");
        fireComboKey = new KeyMapping("key.coopmoves.fire_dap_combo", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.coopmoves");
        event.register(dapKey);
        event.register(fireComboKey);
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(ChargedDapClientHandler.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (dapKey == null) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!CoopMovesConfig.get().enableDap) return;

        boolean onCooldown = System.currentTimeMillis() < whiffCooldownEnd;

        // --- Dap charge (G): press to charge, release to dap ---
        boolean dapDown = dapKey.isDown();
        if (dapDown && !wasDapKeyDown) {
            // G is shared: meteor > QTE > fusion > charge.
            if (MeteorStrikeClientHandler.hasAbility()) {
                MeteorStrikeClientHandler.fire();
            } else if (QTEClientHandler.isActive()) {
                QTEClientHandler.handleKeyPress("G");
            } else if (FusionClientHandler.isQTEOpen()) {
                FusionClientHandler.handleQTEPress("G");
            } else if (FusionClientHandler.isGWindowOpen()) {
                FusionClientHandler.handleGPress();
            } else if (playerFrozen) {
                // scripted freeze (perfect dap / hug): swallow the press.
            } else if (onCooldown) {
                long remaining = (whiffCooldownEnd - System.currentTimeMillis()) / 100;
                player.displayClientMessage(Component.literal("§c§lCOOLDOWN §7Dap ready in §f" + (remaining / 10.0) + "s"), true);
            } else if (!player.getMainHandItem().isEmpty()) {
                player.displayClientMessage(Component.literal("§c§lEMPTY HAND §7needed for charged dap"), true);
            } else if (CoopMovesConfig.get().enableSyncDap && !player.isShiftKeyDown()) {
                // Start classic charge while looking for a synchronized partner. If the
                // server pairs us, it cancels this charge and switches both clients to
                // the timing bar; otherwise releasing G continues as a classic dap.
                syncEngaged = true;
                localCharging = true;
                localChargeStartTime = System.currentTimeMillis();
                CoopNetwork.sendToServer(new ChargedDapHandler.ChargeStartMsg());
                CoopNetwork.sendToServer(new SyncDapHandler.SyncHoldMsg());
                CoopAnimationHandler.startDapCharge(player);
            } else {
                // Shift+G deliberately keeps the original charge/tier path available
                // while synchronized G is enabled (Triple/Fire/Fusion/Heaven combos).
                localCharging = true;
                localChargeStartTime = System.currentTimeMillis();
                CoopNetwork.sendToServer(new ChargedDapHandler.ChargeStartMsg());
                // Play the dap-charge arm animation immediately (also syncs it to others).
                CoopAnimationHandler.startDapCharge(player);
            }
        } else if (!dapDown && wasDapKeyDown) {
            if (syncEngaged) {
                syncEngaged = false;
                boolean paired = SyncDapClientHandler.isActive();
                int marker = paired ? SyncDapClientHandler.lockAndClose() : -1;
                CoopNetwork.sendToServer(new SyncDapHandler.SyncLockMsg(marker));
                if (!paired && localCharging) {
                    CoopNetwork.sendToServer(new ChargedDapHandler.ChargeReleaseMsg());
                }
                localCharging = false;
                CoopAnimationHandler.stopDapChargeLocalOnly(player);
            } else if (localCharging) {
                localCharging = false;
                CoopNetwork.sendToServer(new ChargedDapHandler.ChargeReleaseMsg());
                // Stop the local charge pose; the server broadcasts the result animation
                // (dap hit / whiff-none) which then takes over.
                CoopAnimationHandler.stopDapChargeLocalOnly(player);
            }
        }
        if (localCharging && onCooldown) localCharging = false;
        wasDapKeyDown = dapDown;

        // --- Fire combo (J): only meaningful inside the window ---
        boolean fireDown = fireComboKey.isDown();
        if (fireDown && !wasFireKeyDown && inFireComboWindow) {
            long windowElapsed = System.currentTimeMillis() - fireComboWindowStart;
            if (windowElapsed >= FIRE_COMBO_WINDOW_START_MS) {
                // Valid window: send the press and close the prompt.
                CoopNetwork.sendToServer(new ChargedDapHandler.FireDapJPressMsg());
                inFireComboWindow = false;
            }
            // Too early: ignore the press but keep the window open so the player can retry.
        }
        wasFireKeyDown = fireDown;

        if (inFireComboWindow && System.currentTimeMillis() - fireComboWindowStart > FIRE_COMBO_WINDOW_MS)
            inFireComboWindow = false;

        if (localCharging && !onCooldown) {
            CoopAnimationHandler.keepDapChargeHeld(player, localFireCharge(Minecraft.getInstance()) > 0.05f);
            updateFireChargeAudio(player);
        } else resetFireChargeAudio();
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !localCharging || System.currentTimeMillis() < whiffCooldownEnd) return;

        float fire = localFireCharge(mc);
        if (fire <= 0.05f) return;

        float heat = Math.max(0.0f, Math.min(1.0f, (fire - 0.05f) / 0.95f));
        boolean heaven = heavenReady.contains(mc.player.getUUID());
        float strength = 0.12f + heat * (heaven ? 2.35f : 1.65f);
        double t = System.currentTimeMillis() + event.getPartialTick() * 50.0;
        float rumble = (float) (Math.sin(t * 0.038) + Math.sin(t * 0.071) * 0.55 + Math.sin(t * 0.113) * 0.25);

        event.setRoll(event.getRoll() + rumble * strength);
        event.setYaw(event.getYaw() + (float) Math.sin(t * 0.049 + 0.7) * strength * 0.22f);
        event.setPitch(event.getPitch() + (float) Math.sin(t * 0.057 + 1.9) * strength * 0.18f);
    }

    public static boolean isLocalPlayerCharging() { return localCharging; }

    public static boolean isDapKeyDown() {
        return dapKey != null && dapKey.isDown();
    }

    public static boolean isFireComboKeyDown() {
        return fireComboKey != null && fireComboKey.isDown();
    }

    private static float localFireCharge(Minecraft mc) {
        if (mc.player == null || !CoopMovesConfig.get().enableFireDap || !CoopMovesConfig.get().showFireChargeBar) return 0.0f;
        return fireProgress.getOrDefault(mc.player.getUUID(), 0.0f);
    }

    private static void updateFireChargeAudio(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        float fire = localFireCharge(mc);
        if (fire <= 0.05f) {
            resetFireChargeAudio();
            return;
        }

        boolean heaven = heavenReady.contains(player.getUUID());
        int stage = heaven ? 4 : (fire >= 0.99f ? 3 : (fire >= 0.65f ? 2 : 1));
        if (stage > fireChargeAudioStage) {
            playFireChargeStageCue(player, stage);
            fireChargeAudioStage = stage;
        }

        long now = System.currentTimeMillis();
        long interval = fire >= 0.99f ? 2400L : (fire >= 0.65f ? 3000L : 3900L);
        if (now - lastFireChargeSoundMs < interval) return;
        lastFireChargeSoundMs = now;

        float heat = Math.max(0.0f, Math.min(1.0f, fire));
        player.playSound(ModSounds.FIRE_CHARGE_LAVA.get(), 0.18f + heat * 0.26f, 0.82f + heat * 0.10f);
        if (fire >= 0.65f) {
            player.playSound(ModSounds.FIRE_CHARGE_RUMBLE.get(), 0.18f + heat * 0.24f, 0.58f + heat * 0.08f);
        }
        if (fire >= 0.99f) {
            player.playSound(ModSounds.FIRE_CHARGE_VOLCANO.get(), 0.20f + heat * 0.20f, 0.55f);
        }
    }

    private static void playFireChargeStageCue(LocalPlayer player, int stage) {
        switch (stage) {
            case 1 -> {
                player.playSound(ModSounds.FIRE_CHARGE_IGNITE.get(), 0.95f, 0.82f);
                player.playSound(ModSounds.FIRE_CHARGE_LAVA.get(), 0.42f, 0.88f);
                player.playSound(ModSounds.FIRE_CHARGE_RUMBLE.get(), 0.38f, 0.72f);
            }
            case 2 -> {
                player.playSound(ModSounds.FIRE_CHARGE_VOLCANO.get(), 0.72f, 0.62f);
                player.playSound(ModSounds.FIRE_CHARGE_RUMBLE.get(), 0.50f, 0.62f);
            }
            case 3 -> {
                player.playSound(ModSounds.FIRE_CHARGE_WHOOSH.get(), 1.00f, 0.82f);
                player.playSound(ModSounds.FIRE_CHARGE_RISER_BOOM.get(), 0.86f, 0.78f);
            }
            case 4 -> {
                player.playSound(ModSounds.FIRE_CHARGE_RISER_BOOM.get(), 1.05f, 0.62f);
                player.playSound(ModSounds.GALACTIC_DAP.get(), 0.70f, 0.50f);
            }
            default -> { }
        }
    }

    private static void resetFireChargeAudio() {
        lastFireChargeSoundMs = 0;
        fireChargeAudioStage = 0;
    }

    public static void cleanup(UUID playerId) {
        chargeProgress.remove(playerId);
        fireProgress.remove(playerId);
        heavenReady.remove(playerId);
    }

    // -------------------------------------------------------------- S2C targets
    public static void onChargeSync(UUID playerId, float charge, float fire, boolean charging) {
        if (charging) {
            chargeProgress.put(playerId, charge);
            fireProgress.put(playerId, fire);
        } else {
            chargeProgress.remove(playerId);
            fireProgress.remove(playerId);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.getUUID().equals(playerId)) localCharging = false;
        }
    }

    public static void onHeavenReady(UUID playerId, boolean ready) {
        if (ready) {
            heavenReady.add(playerId);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.getUUID().equals(playerId)) heavenReadyStartTime = System.currentTimeMillis();
        } else {
            heavenReady.remove(playerId);
        }
    }

    public static void onWhiffCooldown(long durationMs) {
        whiffCooldownEnd = System.currentTimeMillis() + durationMs;
        localCharging = false;
    }

    public static void onFireDapWindow() {
        inFireComboWindow = true;
        fireComboWindowStart = System.currentTimeMillis();
    }

    public static void onDapResult(double x, double y, double z, UUID p1, UUID p2, int tier, boolean perfect) {
        Minecraft mc = Minecraft.getInstance();
        localCharging = false;
        chargeProgress.clear();
        fireProgress.clear();
        if (mc.player != null) {
            UUID myId = mc.player.getUUID();
            if (myId.equals(p1) || myId.equals(p2)) {
                flashStartTime = System.currentTimeMillis();
                resultTier = tier;
                resultPerfect = perfect;
            }
        }
        spawnTierParticles(mc, x, y, z, tier, perfect);
    }

    private static void spawnTierParticles(Minecraft mc, double x, double y, double z, int tier, boolean perfect) {
        if (mc.level == null) return;
        ParticleOptions particle;
        int count;
        switch (tier) {
            case 0 -> { particle = ParticleTypes.SMOKE; count = 5; }
            case 1 -> { particle = ParticleTypes.CRIT; count = 10; }
            case 2 -> { particle = ParticleTypes.HAPPY_VILLAGER; count = 15; }
            case 3 -> { particle = ParticleTypes.ENCHANT; count = 20; }
            case 4 -> { particle = ParticleTypes.TOTEM_OF_UNDYING; count = 25; }
            case 5 -> { particle = ParticleTypes.FLAME; count = 30; }
            default -> { particle = ParticleTypes.CRIT; count = 5; }
        }
        for (int i = 0; i < count; i++) {
            mc.level.addParticle(particle,
                    x + (Math.random() - 0.5) * 0.5, y + (Math.random() - 0.5) * 0.5, z + (Math.random() - 0.5) * 0.5,
                    (Math.random() - 0.5) * 0.3, Math.random() * 0.2, (Math.random() - 0.5) * 0.3);
        }
        if (perfect) {
            for (int i = 0; i < 8; i++) {
                double angle = (i / 8.0) * Math.PI * 2;
                mc.level.addParticle(ParticleTypes.ENCHANT, x + Math.cos(angle) * 0.3, y + 0.5, z + Math.sin(angle) * 0.3, 0, 0.1, 0);
            }
        }
    }

    private static void blitFull(GuiGraphics g, ResourceLocation tex, int w, int h, float alpha) {
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        g.blit(tex, 0, 0, w, h, 0f, 0f, 1920, 1080, 1920, 1080);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    // -------------------------------------------------------------- HUD
    private static void renderHud(GuiGraphics g, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        UUID myId = mc.player.getUUID();

        // result flash
        long sinceFlash = now - flashStartTime;
        if (sinceFlash < FLASH_DURATION) {
            float progress = (float) sinceFlash / FLASH_DURATION;
            int baseAlpha = switch (resultTier) {
                case 1 -> 40; case 2 -> 50; case 3 -> 60; case 4 -> 80; case 5 -> 100; default -> 30;
            };
            if (resultPerfect) baseAlpha = Math.min(baseAlpha + 40, 140);
            int alpha = (int) ((1.0f - progress) * baseAlpha);
            int rgb = switch (resultTier) {
                case 1 -> 0xFFFF00; case 2 -> 0x00FF00; case 3 -> 0xFFAA00;
                case 4 -> 0xFF00FF; case 5 -> 0xFF4400; default -> 0x888888;
            };
            g.fill(0, 0, screenWidth, screenHeight, (alpha << 24) | rgb);
        }

        // perfect-dap white flash + impact1-3
        if (perfectImpactActive) {
            long elapsed = now - perfectImpactStartTime;
            if (elapsed < 30) {
                int alpha = (int) (255 * ((float) elapsed / 30));
                g.fill(0, 0, screenWidth, screenHeight, (alpha << 24) | 0xFFFFFF);
            } else if (elapsed < 80) {
                blitFull(g, IMPACT1, screenWidth, screenHeight, 1.0f);
            } else if (elapsed < 130) {
                blitFull(g, IMPACT2, screenWidth, screenHeight, 1.0f);
            } else if (elapsed < 180) {
                blitFull(g, IMPACT3, screenWidth, screenHeight, 1.0f);
            } else {
                perfectImpactActive = false;
            }
        }

        // perfect-dap animated frame sequence
        if (perfectDapImpactFrame > 0) {
            long elapsed = now - perfectDapImpactFrameStartTime;
            ResourceLocation frame;
            if (elapsed < 33) frame = FRAME0;
            else if (elapsed < 66) frame = FRAME1;
            else if (elapsed < 100) frame = FRAME2;
            else if (elapsed < 133) frame = FRAME0;
            else if (elapsed < 166) frame = FRAME3;
            else if (elapsed < 200) frame = FRAME0;
            else { perfectDapImpactFrame = 0; frame = null; }
            if (frame != null) blitFull(g, frame, screenWidth, screenHeight, 1.0f);
        }

        // facing-dap impact frames
        if (facingDapImpactActive) {
            long elapsed = now - facingDapImpactStartMs;
            ResourceLocation frame;
            if (elapsed < 50) frame = IMPAC7;
            else if (elapsed < 100) frame = IMPAC8;
            else if (elapsed < 150) frame = IMPAC9;
            else if (elapsed < 200) frame = FRAME0;
            else { facingDapImpactActive = false; frame = null; }
            if (frame != null) blitFull(g, frame, screenWidth, screenHeight, 1.0f);
        }

        boolean onCooldown = now < whiffCooldownEnd;

        // whiff cooldown bar
        if (onCooldown && !localCharging) {
            float cooldownProgress = (whiffCooldownEnd - now) / 800f;
            int barWidth = 40, barHeight = 3;
            int barX = (screenWidth - barWidth) / 2, barY = screenHeight / 2 + 20;
            g.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + barHeight + 1, 0x44000000);
            g.fill(barX, barY, barX + (int) (barWidth * cooldownProgress), barY + barHeight, 0xBBFF0000);
        }

        // fire-dap "PRESS J!" prompt
        if (inFireComboWindow) {
            long elapsed = now - fireComboWindowStart;
            if (FIRE_COMBO_WINDOW_MS - elapsed > 0) {
                String text = "PRESS J!";
                int textY = screenHeight / 2 + 10;
                float pulse = (float) (Math.sin(now / 80.0) * 0.4 + 0.6);
                int alpha = (int) (pulse * 255);
                float timeProgress = (float) elapsed / FIRE_COMBO_WINDOW_MS;
                int color = timeProgress < 0.5f ? (alpha << 24) | 0xFF8800 : (alpha << 24) | 0xFF0000;
                HudTextRenderer.drawCenterImpact(g, text, screenWidth / 2, textY,
                        color, timeProgress < 0.5f ? 0xFFFFF05A : 0xFFFF2200);
                int barWidth = 100, barHeight = 3;
                int barX = (screenWidth - barWidth) / 2, barY = textY + 12;
                g.fill(barX, barY, barX + barWidth, barY + barHeight, 0x80000000);
                int barColor = timeProgress < 0.5f ? 0xFFFF8800 : 0xFFFF0000;
                g.fill(barX, barY, barX + (int) (barWidth * (1.0f - timeProgress)), barY + barHeight, barColor);
            }
        }

        // main charge bar
        if (localCharging && !onCooldown
                && (CoopMovesConfig.get().showDapChargeBar || CoopMovesConfig.get().showFireChargeBar)) {
            long elapsed = now - localChargeStartTime;
            float chargePercent = Math.min(1.0f, (float) elapsed / CHARGE_TIME_MS);
            float myFire = CoopMovesConfig.get().enableFireDap && CoopMovesConfig.get().showFireChargeBar
                    ? fireProgress.getOrDefault(myId, 0f) : 0f;
            boolean isHeavenReady = heavenReady.contains(myId);

            int barWidth = 40, barHeight = 3;
            int barX = (screenWidth - barWidth) / 2, barY = screenHeight / 2 + 20;
            g.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + barHeight + 1, 0x44000000);

            if (myFire > 0.05f) {
                renderFireChargePrompt(g, screenWidth, barY, myFire, isHeavenReady);
                g.fill(barX, barY, barX + (int) (barWidth * chargePercent), barY + barHeight, 0xBB00FF00);
                int redWidth = (int) (barWidth * myFire);
                if (isHeavenReady) {
                    long since = now - heavenReadyStartTime;
                    int sx = (int) ((Math.random() - 0.5) * 8), sy = (int) ((Math.random() - 0.5) * 6);
                    int col = (since % 500 < 250) ? 0xFFFF00FF : 0xDDFF2200;
                    g.fill(barX + sx, barY + sy, barX + redWidth + sx, barY + barHeight + sy, col);
                    g.fill(barX - 5 + sx, barY + 1 + sy, barX + barWidth + 5 + sx, barY + 2 + sy, 0xFFFFFFFF);
                } else if (myFire >= 0.99f) {
                    int sx = (int) ((Math.random() - 0.5) * 4), sy = (int) ((Math.random() - 0.5) * 2);
                    g.fill(barX + sx, barY + sy, barX + redWidth + sx, barY + barHeight + sy, 0xDDFF2200);
                } else {
                    g.fill(barX, barY, barX + redWidth, barY + barHeight, 0xDDFF2200);
                }
            } else {
                int fillColor = chargePercent >= 0.99f ? 0xBB00FF00 : 0xBBFFAA00;
                g.fill(barX, barY, barX + (int) (barWidth * chargePercent), barY + barHeight, fillColor);
            }

            // partner mini-bars
            int partnerY = barY + 8;
            for (Map.Entry<UUID, Float> entry : chargeProgress.entrySet()) {
                if (entry.getKey().equals(myId)) continue;
                boolean inRange = false;
                if (mc.level != null) {
                    var p = mc.level.getPlayerByUUID(entry.getKey());
                    if (p != null && mc.player.distanceTo(p) <= 20.0) inRange = true;
                }
                if (!inRange) continue;
                float partnerCharge = entry.getValue();
                float partnerFire = fireProgress.getOrDefault(entry.getKey(), 0f);
                int pW = 30, pH = 2, pX = (screenWidth - pW) / 2;
                g.fill(pX - 1, partnerY - 1, pX + pW + 1, partnerY + pH + 1, 0x33000000);
                int pColor = partnerFire > 0.1f ? 0xAAFF4400 : (partnerCharge >= 0.99f ? 0xAA00FF00 : 0xAAFFAA00);
                g.fill(pX, partnerY, pX + (int) (pW * partnerCharge), partnerY + pH, pColor);
                partnerY += 6;
            }
        }
    }

    private static void renderFireChargePrompt(GuiGraphics g, int screenWidth, int barY, float fire, boolean heaven) {
        float visible = Math.max(0.0f, Math.min(1.0f, (fire - 0.05f) / 0.25f));
        int alpha = (int) (120 + 135 * visible);
        int packedAlpha = alpha << 24;
        String text;
        int color;
        int accent;
        if (heaven) {
            text = "HEAVEN DAP READY!";
            color = packedAlpha | 0xFF6A3A;
            accent = packedAlpha | 0xE000FF;
        } else if (fire >= 0.99f) {
            text = "MEGA DAP READY!";
            color = packedAlpha | 0xFF2A00;
            accent = packedAlpha | 0xFF0000;
        } else if (fire >= 0.65f) {
            text = "VOLCANIC DAP RISING";
            color = packedAlpha | 0xFF3C12;
            accent = packedAlpha | 0xF00000;
        } else {
            text = "VOLCANIC DAP CHARGING";
            color = packedAlpha | 0xF05A1A;
            accent = packedAlpha | 0xC02000;
        }

        HudTextRenderer.drawCenterImpact(g, text, screenWidth / 2, barY - 54, color, accent);
        HudTextRenderer.drawCenterCompact(g, "FIRE " + Math.round(fire * 100.0f) + "%",
                screenWidth / 2, barY - 38, packedAlpha | 0xFF9A6A, packedAlpha | 0xE32B00);
    }

    // -------------------------------------------------------------- misc state
    /** Set by ChargedDapHandler.PerfectDapFreezePayload (sit / perfect-dap freeze). */
    public static void onPerfectDapFreeze(boolean frozen) { playerFrozen = frozen; }

    /** Read by MovementFreezeMixin (Stage 5) to lock local movement. */
    public static boolean isPlayerFrozen() { return playerFrozen; }

    /** Perfect-dap white-flash + impact1-3 frame sequence. */
    public static void onImpactFrame(int durationMs, boolean grayscale) {
        if (grayscale) { perfectImpactActive = true; perfectImpactStartTime = System.currentTimeMillis(); }
    }

    /** Perfect-dap animated frame0-3 sequence (gated on freeze). */
    public static void onPerfectDapImpactFrame(int frameIndex) {
        if (!playerFrozen) return;
        perfectDapImpactFrame = 1;
        perfectDapImpactFrameStartTime = System.currentTimeMillis();
        CoopImpactHandler.start(6, 33); // white/black player silhouette flash
    }

    /** Facing-dap impact frame flash (impac7-9). */
    public static void onFacingDapImpact() {
        facingDapImpactActive = true;
        facingDapImpactStartMs = System.currentTimeMillis();
    }

    public static boolean isDapBadBlocking() { return System.currentTimeMillis() < dapBadBlockEnd; }

    public static void triggerDapBadBlock() { dapBadBlockEnd = System.currentTimeMillis() + 1667L; }

    public static void setInFaceDapSession(boolean active) { inFaceDapSession = active; }
    public static boolean isInFaceDapSession() { return inFaceDapSession; }
}
