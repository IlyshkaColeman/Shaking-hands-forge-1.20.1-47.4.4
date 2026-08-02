package com.cooptest;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Fire Slap: while fully fire-charged, meleeing a mob launches and ignites it.
 * Ported from Fabric to Forge 1.20.1 (AttackEntityCallback -> AttackEntityEvent).
 * Reads ChargedDapHandler.fireLevel (set by the fire-dap core, ported later).
 */
public final class FireSlapHandler {

    private FireSlapHandler() {}

    private static final float MIN_FIRE_LEVEL = 0.95f;
    private static final double SLAP_KNOCKBACK = 2.5;
    private static final double SLAP_VERTICAL = 0.5;

    public static void register() {
        MinecraftForge.EVENT_BUS.register(FireSlapHandler.class);
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) return;
        if (!(event.getTarget() instanceof LivingEntity target)) return;
        if (event.getTarget() instanceof Player) return;
        float fireLevel = ChargedDapHandler.fireLevel.getOrDefault(serverPlayer.getUUID(), 0f);
        if (fireLevel < MIN_FIRE_LEVEL) return;
        executeFireSlap(serverPlayer, target);
    }

    private static void executeFireSlap(ServerPlayer player, LivingEntity target) {
        ServerLevel world = player.serverLevel();
        Vec3 direction = target.position().subtract(player.position()).normalize();
        target.setDeltaMovement(direction.x * SLAP_KNOCKBACK, SLAP_VERTICAL, direction.z * SLAP_KNOCKBACK);
        target.hurtMarked = true;
        target.setSecondsOnFire(2);
        double x = target.getX();
        double y = target.getY() + target.getBbHeight() / 2;
        double z = target.getZ();
        world.sendParticles(ParticleTypes.FLAME, x, y, z, 8, 0.3, 0.3, 0.3, 0.05);
        world.sendParticles(ParticleTypes.SMOKE, x, y, z, 5, 0.2, 0.2, 0.2, 0.02);
        world.sendParticles(ParticleTypes.CRIT, x, y, z, 6, 0.3, 0.3, 0.3, 0.1);
        world.playSound(null, x, y, z, SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.0f, 0.8f);
        world.playSound(null, x, y, z, SoundEvents.BLAZE_HURT, SoundSource.PLAYERS, 0.5f, 1.2f);
        player.displayClientMessage(Component.literal("§c FIRE SLAP! "), true);
        ChargedDapHandler.fireLevel.put(player.getUUID(), 0.5f);
    }
}
