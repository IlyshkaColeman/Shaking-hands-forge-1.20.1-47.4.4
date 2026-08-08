package com.cooptest;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Fall Dap: charge a dap mid-fall (20+ blocks) to enter a squash dive; landing on a
 * player squashes them for 25s. Ported from Fabric to Forge 1.20.1.
 *
 * Driven partly by ChargedDapHandler.isCharging/isFullyCharged (core). Translations
 * per project template (StatusEffect*->MobEffect*, getEquippedStack->getItemBySlot,
 * setStackInHand->setItemInHand, spawnEntity->addFreshEntity, CustomPayload->
 * CoopNetwork). DAP_CHARGE_IDLE ordinal hardcoded (8).
 */
public final class FallDapHandler {

    private FallDapHandler() {}

    private static final Map<UUID, FallDapState> fallDapPlayers = new HashMap<>();
    private static final Map<UUID, Long> fallChargeStartTime = new HashMap<>();
    private static final Map<UUID, Long> squashedPlayers = new HashMap<>();
    private static final Map<UUID, Double> fallStartY = new HashMap<>();
    private static final double REQUIRED_FALL_BLOCKS = 20.0;
    private static final long FALL_CHARGE_DURATION_MS = 750;

    private static final int ANIM_DAP_CHARGE_IDLE = 8;

    public enum FallDapState { NONE, CHARGING, FALLING }

    public static void register() { }

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> squashIt = squashedPlayers.entrySet().iterator();
        while (squashIt.hasNext()) {
            Map.Entry<UUID, Long> entry = squashIt.next();
            UUID playerId = entry.getKey();
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (now >= entry.getValue()) {
                squashIt.remove();
                if (player != null) {
                    PoseNetworking.broadcastAnimState(player, 0);
                    player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    player.removeEffect(MobEffects.JUMP);
                }
            } else if (player != null) {
                if (!player.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)
                        || player.getEffect(MobEffects.MOVEMENT_SLOWDOWN).getDuration() < 40) {
                    player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 2, false, false));
                }
                if (!player.hasEffect(MobEffects.JUMP)
                        || player.getEffect(MobEffects.JUMP).getDuration() < 40) {
                    player.addEffect(new MobEffectInstance(MobEffects.JUMP, 60, 250, false, false));
                }
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID playerId = player.getUUID();
            if (isSquashed(playerId)) continue;
            if (player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
                cleanup(playerId);
                continue;
            }
            boolean isChargingDap = ChargedDapHandler.isCharging(playerId);
            boolean isOnGround = player.onGround();
            boolean isFalling = player.getDeltaMovement().y < -0.1;
            FallDapState currentState = fallDapPlayers.getOrDefault(playerId, FallDapState.NONE);
            if (currentState == FallDapState.NONE) {
                if (isChargingDap && isFalling && !isOnGround) {
                    if (!fallStartY.containsKey(playerId)) fallStartY.put(playerId, player.getY());
                    double startY = fallStartY.get(playerId);
                    double fallen = startY - player.getY();
                    if (fallen >= REQUIRED_FALL_BLOCKS && ChargedDapHandler.isFullyCharged(playerId)) {
                        startFallDapCharge(player);
                    }
                } else if (isOnGround) {
                    fallStartY.remove(playerId);
                }
            } else if (currentState == FallDapState.CHARGING) {
                Long chargeStart = fallChargeStartTime.get(playerId);
                if (chargeStart != null && now - chargeStart >= FALL_CHARGE_DURATION_MS) {
                    fallDapPlayers.put(playerId, FallDapState.FALLING);
                    broadcastFallDapAnim(player, 2);
                    player.displayClientMessage(Component.literal("§c§l FALL DAP READY! "), true);
                }
                if (isOnGround) {
                    if (isChargingDap) resetToNormalCharge(player);
                    else { cleanup(playerId); PoseNetworking.broadcastAnimState(player, 0); }
                }
            } else if (currentState == FallDapState.FALLING) {
                ServerPlayer victim = CoopMovesConfig.get().enableSquash ? findSquashTarget(player, 3.0) : null;
                if (victim != null) {
                    squashPlayer(player, victim);
                    cleanup(playerId);
                    continue;
                }
                if (isOnGround) {
                    if (isChargingDap) resetToNormalCharge(player);
                    else { cleanup(playerId); PoseNetworking.broadcastAnimState(player, 0); }
                }
            }
        }
    }

    private static void startFallDapCharge(ServerPlayer player) {
        UUID playerId = player.getUUID();
        fallDapPlayers.put(playerId, FallDapState.CHARGING);
        fallChargeStartTime.put(playerId, System.currentTimeMillis());
        broadcastFallDapAnim(player, 1);
        player.displayClientMessage(Component.literal("§e§l⚡ FALL DAP CHARGING! ⚡"), true);
    }

    private static void resetToNormalCharge(ServerPlayer player) {
        UUID playerId = player.getUUID();
        fallDapPlayers.remove(playerId);
        fallStartY.remove(playerId);
        fallChargeStartTime.remove(playerId);
        broadcastFallDapAnim(player, 0);
        PoseNetworking.broadcastAnimState(player, ANIM_DAP_CHARGE_IDLE);
        player.displayClientMessage(Component.literal("§7Fall dap reset - touched ground"), true);
    }

    public static boolean isInFallDapState(UUID playerId) {
        FallDapState state = fallDapPlayers.get(playerId);
        return state == FallDapState.CHARGING || state == FallDapState.FALLING;
    }

    public static boolean isReadyToFallDap(UUID playerId) {
        return fallDapPlayers.get(playerId) == FallDapState.FALLING;
    }

    public static void executeFallDapHit(ServerLevel world, Vec3 pos, ServerPlayer attacker, ServerPlayer victim) {
        broadcastFallDapAnim(attacker, 3);
        cleanup(attacker.getUUID());
    }

    private static void squashPlayer(ServerPlayer attacker, ServerPlayer victim) {
        CoopMovesConfig cfg = CoopMovesConfig.get();
        ServerLevel world = attacker.serverLevel();
        Vec3 pos = victim.position();
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 2.0f, 0.5f);
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0f, 0.8f);
        world.sendParticles(ParticleTypes.CRIT, pos.x, pos.y + 1, pos.z, 30, 0.5, 0.3, 0.5, 0.2);
        world.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y, pos.z, 20, 0.5, 0.2, 0.5, 0.05);
        if (cfg.squashDropsItems) dropHandItems(victim, world, pos);
        victim.hurt(world.damageSources().playerAttack(attacker), Math.max(0.0f, cfg.squashDamage));
        int squashTicks = Math.max(1, cfg.squashDurationSec) * 20;
        int nauseaTicks = Math.max(0, cfg.squashNauseaSec) * 20;
        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, squashTicks, 2, false, false));
        victim.addEffect(new MobEffectInstance(MobEffects.JUMP, squashTicks, 250, false, false));
        if (nauseaTicks > 0)
            victim.addEffect(new MobEffectInstance(MobEffects.CONFUSION, nauseaTicks, 0, false, false));
        victim.setDeltaMovement(0, 0, 0);
        victim.hurtMarked = true;
        squashedPlayers.put(victim.getUUID(), System.currentTimeMillis() + squashTicks * 50L);
        MinecraftServer server = world.getServer();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            CoopNetwork.sendToPlayer(p, new SquashAnimMsg(victim.getUUID()));
        }
        attacker.displayClientMessage(Component.literal("§c§l💀 SQUASHED! 💀"), true);
        victim.displayClientMessage(Component.literal("§c§lYOU GOT SQUASHED FOR "
                + Math.max(1, cfg.squashDurationSec) + " SECONDS!"), true);
    }

    private static void dropHandItems(ServerPlayer player, ServerLevel world, Vec3 pos) {
        ItemStack mainStack = player.getMainHandItem();
        if (!mainStack.isEmpty()) {
            ItemEntity mainItem = new ItemEntity(world, pos.x, pos.y + 0.5, pos.z, mainStack.copy());
            mainItem.setDeltaMovement((world.getRandom().nextDouble() - 0.5) * 0.3,
                    world.getRandom().nextDouble() * 0.2 + 0.1, (world.getRandom().nextDouble() - 0.5) * 0.3);
            world.addFreshEntity(mainItem);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        ItemStack offStack = player.getOffhandItem();
        if (!offStack.isEmpty()) {
            ItemEntity offItem = new ItemEntity(world, pos.x, pos.y + 0.5, pos.z, offStack.copy());
            offItem.setDeltaMovement((world.getRandom().nextDouble() - 0.5) * 0.3,
                    world.getRandom().nextDouble() * 0.2 + 0.1, (world.getRandom().nextDouble() - 0.5) * 0.3);
            world.addFreshEntity(offItem);
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
        ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        if (!helmet.isEmpty()) {
            ItemEntity helmetItem = new ItemEntity(world, pos.x, pos.y + 1.0, pos.z, helmet.copy());
            helmetItem.setDeltaMovement((world.getRandom().nextDouble() - 0.5) * 0.4,
                    world.getRandom().nextDouble() * 0.4 + 0.3, (world.getRandom().nextDouble() - 0.5) * 0.4);
            world.addFreshEntity(helmetItem);
            player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        }
    }

    private static ServerPlayer findSquashTarget(ServerPlayer attacker, double horizontalRange) {
        double attackerY = attacker.getY();
        for (ServerPlayer other : attacker.serverLevel().players()) {
            if (other == attacker) continue;
            if (isSquashed(other.getUUID())) continue;
            double heightDiff = attackerY - other.getY();
            if (heightDiff < 0.5 || heightDiff > 3.0) continue;
            double dx = attacker.getX() - other.getX();
            double dz = attacker.getZ() - other.getZ();
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);
            if (horizontalDist <= horizontalRange) return other;
        }
        return null;
    }

    public static boolean isSquashed(UUID playerId) {
        Long endTime = squashedPlayers.get(playerId);
        if (endTime == null) return false;
        if (System.currentTimeMillis() >= endTime) {
            squashedPlayers.remove(playerId);
            return false;
        }
        return true;
    }

    private static void broadcastFallDapAnim(ServerPlayer player, int state) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        FallDapAnimMsg msg = new FallDapAnimMsg(player.getUUID(), state);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            CoopNetwork.sendToPlayer(p, msg);
        }
    }

    public static void cleanup(UUID playerId) {
        fallDapPlayers.remove(playerId);
        fallStartY.remove(playerId);
        fallChargeStartTime.remove(playerId);
        squashedPlayers.remove(playerId);
    }

    // ------------------------------------------------------------------ networking

    public record FallDapAnimMsg(UUID playerId, int state) {
        public static void encode(FallDapAnimMsg m, FriendlyByteBuf buf) { buf.writeUUID(m.playerId); buf.writeInt(m.state); }
        public static FallDapAnimMsg decode(FriendlyByteBuf buf) { return new FallDapAnimMsg(buf.readUUID(), buf.readInt()); }
        public static void handle(FallDapAnimMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.FallDapClientHandler.onFallDapAnim(m.playerId(), m.state()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    public record SquashAnimMsg(UUID playerId) {
        public static void encode(SquashAnimMsg m, FriendlyByteBuf buf) { buf.writeUUID(m.playerId); }
        public static SquashAnimMsg decode(FriendlyByteBuf buf) { return new SquashAnimMsg(buf.readUUID()); }
        public static void handle(SquashAnimMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.FallDapClientHandler.onSquashAnim(m.playerId()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(FallDapAnimMsg.class, FallDapAnimMsg::encode, FallDapAnimMsg::decode, FallDapAnimMsg::handle);
        CoopNetwork.register(SquashAnimMsg.class, SquashAnimMsg::encode, SquashAnimMsg::decode, SquashAnimMsg::handle);
    }
}
