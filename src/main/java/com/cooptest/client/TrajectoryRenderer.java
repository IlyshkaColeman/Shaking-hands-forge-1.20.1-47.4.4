package com.cooptest.client;

import com.cooptest.GrabInputHandler;
import com.cooptest.PoseNetworking;
import com.cooptest.PoseState;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

/**
 * Throw-trajectory preview — dotted arc shown while holding a player and charging a
 * throw. Ported from Fabric (WorldRenderEvents + 1.21 buffer API) to Forge 1.20.1
 * (RenderLevelStageEvent + Tesselator/BufferBuilder immediate mode).
 */
@OnlyIn(Dist.CLIENT)
public final class TrajectoryRenderer {

    private TrajectoryRenderer() {}

    private static final int TRAJECTORY_POINTS = 30;
    private static final float GRAVITY = 0.08f;
    private static final float DRAG = 0.02f;
    private static final float DOT_SIZE = 0.08f;
    private static final float MIN_POWER_MULT = 1.5f;
    private static final float MAX_POWER_MULT = 3.5f;

    public static void register() {
        MinecraftForge.EVENT_BUS.register(TrajectoryRenderer.class);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        PoseState pose = PoseNetworking.poseStates.getOrDefault(client.player.getUUID(), PoseState.NONE);
        if (pose != PoseState.GRAB_HOLDING) return;
        float chargeProgress = GrabInputHandler.getThrowChargeProgress();
        if (chargeProgress <= 0) return;

        float power = MIN_POWER_MULT + (MAX_POWER_MULT - MIN_POWER_MULT) * chargeProgress;
        Vec3 lookVec = client.player.getViewVector(event.getPartialTick());
        Vec3 pos = client.player.getEyePosition(event.getPartialTick()).add(0, 0.5, 0);
        Vec3 vel = lookVec.scale(power);

        Vec3[] points = new Vec3[TRAJECTORY_POINTS];
        double floorY = client.player.getY() - 10;
        for (int i = 0; i < TRAJECTORY_POINTS; i++) {
            points[i] = pos;
            vel = vel.add(0, -GRAVITY, 0).scale(1.0 - DRAG);
            pos = pos.add(vel);
            if (pos.y < floorY) break;
        }
        renderDots(event.getPoseStack(), points, chargeProgress);
    }

    private static void renderDots(PoseStack matrices, Vec3[] points, float charge) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();

        matrices.pushPose();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f matrix = matrices.last().pose();

        int r = (int) (charge * 255);
        int gcol = (int) ((1 - charge) * 255);
        int b = 50;
        int alpha = 200;

        for (int i = 0; i < points.length && points[i] != null; i++) {
            Vec3 point = points[i];
            int fadeAlpha = (int) (alpha * (1.0f - (float) i / points.length));
            float size = DOT_SIZE * (1.0f - (float) i / points.length * 0.5f);
            float x = (float) point.x, y = (float) point.y, z = (float) point.z;
            // +Z / -Z faces (billboard-ish cube; cheap and readable)
            buffer.vertex(matrix, x - size, y - size, z + size).color(r, gcol, b, fadeAlpha).endVertex();
            buffer.vertex(matrix, x + size, y - size, z + size).color(r, gcol, b, fadeAlpha).endVertex();
            buffer.vertex(matrix, x + size, y + size, z + size).color(r, gcol, b, fadeAlpha).endVertex();
            buffer.vertex(matrix, x - size, y + size, z + size).color(r, gcol, b, fadeAlpha).endVertex();
            buffer.vertex(matrix, x + size, y - size, z - size).color(r, gcol, b, fadeAlpha).endVertex();
            buffer.vertex(matrix, x - size, y - size, z - size).color(r, gcol, b, fadeAlpha).endVertex();
            buffer.vertex(matrix, x - size, y + size, z - size).color(r, gcol, b, fadeAlpha).endVertex();
            buffer.vertex(matrix, x + size, y + size, z - size).color(r, gcol, b, fadeAlpha).endVertex();
        }

        tesselator.end();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        matrices.popPose();
    }
}
