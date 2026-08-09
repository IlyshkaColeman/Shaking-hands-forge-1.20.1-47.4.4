package com.cooptest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Grab / carry / throw / human-shield mechanic. Ported from Fabric to Forge 1.20.1.
 *
 * Behaviour, tuning constants, particle counts, sounds and messages are preserved.
 * Notable API translations:
 *   ServerWorld->ServerLevel, Vec3d->Vec3, SoundCategory->SoundSource
 *   setVelocity/velocityModified -> setDeltaMovement/hurtMarked
 *   EntityPassengersSetS2CPacket -> ClientboundSetPassengersPacket
 *   EntityVelocityUpdateS2CPacket -> ClientboundSetEntityMotionPacket
 *   EntityPositionS2CPacket -> ClientboundTeleportEntityPacket
 *   StatusEffects.SLOWNESS -> MobEffects.MOVEMENT_SLOWDOWN
 *   Fabric ServerLivingEntityEvents.ALLOW_DAMAGE -> Forge LivingAttackEvent
 */
public class GrabMechanic {

    public static final HashMap<UUID, UUID> holding = new HashMap<>();
    public static final HashMap<UUID, UUID> heldBy = new HashMap<>();
    private static MinecraftServer cachedServer = null;
    public static final HashMap<UUID, Boolean> shieldMode = new HashMap<>();
    public static final HashMap<UUID, Long> shieldSwapCooldown = new HashMap<>();
    public static final HashMap<UUID, ArmorStand> shieldArmorStands = new HashMap<>();
    private static final long SHIELD_SWAP_COOLDOWN_MS = 1000;

    private static final HashMap<UUID, PendingThrow> pendingThrows = new HashMap<>();
    private static final HashMap<UUID, ThrownPlayerData> thrownPlayers = new HashMap<>();

    private static class PendingThrow {
        ServerPlayer holder;
        ServerPlayer held;
        Vec3 velocity;
        int ticksRemaining;
        PendingThrow(ServerPlayer holder, ServerPlayer held, Vec3 velocity, int delay) {
            this.holder = holder;
            this.held = held;
            this.velocity = velocity;
            this.ticksRemaining = delay;
        }
    }

    private static class ThrownPlayerData {
        double startY;
        int ticksFlying;
        boolean wasOnFire;
        Vec3 lastPos;
        Vec3 velocity;
        long throwTimeMs;
        boolean elytraBoostUsed;
        ThrownPlayerData(double startY, boolean wasOnFire, Vec3 velocity) {
            this.startY = startY;
            this.ticksFlying = 0;
            this.wasOnFire = wasOnFire;
            this.lastPos = null;
            this.velocity = velocity;
            this.throwTimeMs = System.currentTimeMillis();
            this.elytraBoostUsed = false;
        }
    }

    public static final HashMap<UUID, Long> elytraBoostRequests = new HashMap<>();
    public static final HashMap<UUID, float[]> airMovementInput = new HashMap<>();
    private static final double AIR_CONTROL_STRENGTH = 0.025;
    private static final long ELYTRA_BOOST_REQUEST_WINDOW_MS = 2500L;

    private static void sendPassengerUpdate(MinecraftServer server, net.minecraft.world.entity.Entity vehicle) {
        ClientboundSetPassengersPacket packet = new ClientboundSetPassengersPacket(vehicle);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.connection.send(packet);
        }
    }

    // ------------------------------------------------------------------ grab

    public static boolean tryGrab(ServerPlayer holder, ServerPlayer held) {
        if (holder == held) return false;
        if (holder.distanceTo(held) > 3.0f) return false;
        if (holding.containsKey(holder.getUUID())) return false;
        if (heldBy.containsKey(held.getUUID())) return false;
        if (PushInteractionHandler.hasPushImmunity(held.getUUID())) return false;
        PoseState holderPose = PoseNetworking.poseStates.getOrDefault(holder.getUUID(), PoseState.NONE);
        if (holderPose != PoseState.GRAB_READY) return false;
        if (holder.isPassenger() && holder.getVehicle() == held) return false;
        if (held.isPassenger() && held.getVehicle() == holder) return false;

        boolean success = held.startRiding(holder, true);
        if (!success) return false;

        holding.put(holder.getUUID(), held.getUUID());
        heldBy.put(held.getUUID(), holder.getUUID());
        PoseNetworking.poseStates.put(holder.getUUID(), PoseState.GRAB_HOLDING);
        PoseNetworking.poseStates.put(held.getUUID(), PoseState.GRABBED);

        MinecraftServer server = holder.getServer();
        if (server != null) {
            PoseNetworking.broadcastPoseChange(server, holder.getUUID(), PoseState.GRAB_HOLDING);
            PoseNetworking.broadcastPoseChange(server, held.getUUID(), PoseState.GRABBED);
            GrabNetworking.broadcastGrabState(server, holder.getUUID(), held.getUUID(), true);
            sendPassengerUpdate(server, holder);
        }
        holder.serverLevel().playSound(null, holder.getX(), holder.getY(), holder.getZ(),
                SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.PLAYERS, 1.0f, 1.0f);
        return true;
    }

    // ------------------------------------------------------------------ throw

    public static boolean tryThrow(ServerPlayer holder, float power) {
        if (!Float.isFinite(power)) return false;
        power = Math.max(0.0f, Math.min(1.0f, power));
        UUID heldId = holding.get(holder.getUUID());
        if (heldId == null) return false;
        if (isInShieldMode(holder.getUUID())) {
            holder.displayClientMessage(Component.literal("§cSwitch to throw mode first! (Press V)"), true);
            return false;
        }
        ServerPlayer held = holder.getServer().getPlayerList().getPlayer(heldId);
        if (held == null) {
            cleanupGrab(holder.getUUID());
            return false;
        }
        if (!holder.isCreative()) {
            if (holder.getFoodData().getFoodLevel() < 6) {
                holder.displayClientMessage(Component.literal("§cToo hungry to throw!"), true);
                return false;
            }
            holder.causeFoodExhaustion(2.0f);
        }

        Vec3 lookDir = holder.getViewVector(1.0f);
        float scaledPower = 0.5f + (2.5f - 0.5f) * power;
        double horizX = lookDir.x * scaledPower * 1.6;
        double horizZ = lookDir.z * scaledPower * 1.6;
        double verticalBase = 0.4 + (lookDir.y * 0.6);
        double maxVertical = 1.8;
        double verticalVel = Math.min(verticalBase + (power * 0.6), maxVertical);
        if (lookDir.y < 0) verticalVel = Math.max(0.3, verticalVel);
        Vec3 throwVelocity = new Vec3(horizX, verticalVel, horizZ);
        Vec3 releasePos = holder.position()
                .add(lookDir.multiply(1.5, 1.5, 1.5).multiply(1, 0, 1))
                .add(0, 0.5, 0);

        held.stopRiding();
        holding.remove(holder.getUUID());
        heldBy.remove(held.getUUID());
        PoseNetworking.poseStates.put(holder.getUUID(), PoseState.NONE);

        MinecraftServer server = holder.getServer();
        if (server != null) {
            PoseNetworking.broadcastPoseChange(server, holder.getUUID(), PoseState.NONE);
            PoseNetworking.broadcastAnimState(holder, 0);
            GrabNetworking.broadcastGrabState(server, holder.getUUID(), held.getUUID(), false);
            sendPassengerUpdate(server, holder);
        }

        float throwYaw = holder.getYRot();
        float throwPitch = holder.getXRot();
        held.moveTo(releasePos.x, releasePos.y, releasePos.z, throwYaw, throwPitch);
        held.setYHeadRot(throwYaw);
        held.connection.teleport(releasePos.x, releasePos.y, releasePos.z, throwYaw, throwPitch);

        pendingThrows.put(held.getUUID(), new PendingThrow(holder, held, throwVelocity, 3));

        holder.serverLevel().playSound(null, holder.getX(), holder.getY(), holder.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0f, 0.8f + (power * 0.4f));
        spawnThrowParticles(holder, held);
        return true;
    }

    private static void spawnThrowParticles(ServerPlayer holder, ServerPlayer held) {
        ServerLevel level = holder.serverLevel();
        Vec3 pos = holder.position();
        for (int i = 0; i < 10; i++) {
            double offsetX = (level.random.nextDouble() - 0.5) * 0.5;
            double offsetY = level.random.nextDouble() * 0.5 + 0.5;
            double offsetZ = (level.random.nextDouble() - 0.5) * 0.5;
            level.sendParticles(ParticleTypes.CLOUD,
                    pos.x + offsetX, pos.y + offsetY, pos.z + offsetZ,
                    1, 0, 0, 0, 0.05);
        }
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftServer server) {
        cachedServer = server;
        tickShieldMode(server);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID playerId = player.getUUID();
            PoseState pose = PoseNetworking.poseStates.getOrDefault(playerId, PoseState.NONE);
            if (pose == PoseState.GRABBED
                    && !player.isPassenger()
                    && !thrownPlayers.containsKey(playerId)
                    && !pendingThrows.containsKey(playerId)) {
                heldBy.remove(playerId);
                PoseNetworking.poseStates.put(playerId, PoseState.NONE);
                PoseNetworking.broadcastPoseChange(server, playerId, PoseState.NONE);
                PoseNetworking.broadcastAnimState(player, 0);
            }
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isPassenger() && player.getVehicle() instanceof ServerPlayer carrier) {
                PoseState pose = PoseNetworking.poseStates.get(player.getUUID());
                if (pose == PoseState.GRABBED) {
                    float carrierYaw = carrier.getYRot();
                    player.setYRot(carrierYaw);
                    player.setYBodyRot(carrierYaw);
                    player.setYHeadRot(carrierYaw);
                }
            }
        }

        var throwIterator = pendingThrows.entrySet().iterator();
        while (throwIterator.hasNext()) {
            var entry = throwIterator.next();
            PendingThrow pending = entry.getValue();
            pending.ticksRemaining--;
            if (pending.ticksRemaining <= 0) {
                ServerPlayer held = pending.held;
                if (held != null && held.isAlive()) {
                    held.setDeltaMovement(pending.velocity);
                    held.hurtMarked = true;
                    held.connection.send(new ClientboundSetEntityMotionPacket(held));
                    boolean wasOnFire = held.isOnFire();
                    thrownPlayers.put(held.getUUID(), new ThrownPlayerData(held.getY(), wasOnFire, pending.velocity));
                }
                throwIterator.remove();
            }
        }

        Set<UUID> toRemove = new HashSet<>();
        for (var entry : new HashMap<>(thrownPlayers).entrySet()) {
            UUID playerId = entry.getKey();
            ThrownPlayerData data = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                toRemove.add(playerId);
                continue;
            }
            data.ticksFlying++;

            Vec3 vel = player.getDeltaMovement();
            if (vel.horizontalDistanceSqr() > 0.01) {
                float velocityYaw = (float) Math.toDegrees(Math.atan2(-vel.x, vel.z));
                player.setYRot(velocityYaw);
                player.setYBodyRot(velocityYaw);
                player.setYHeadRot(velocityYaw);
            }

            float[] moveInput = airMovementInput.get(playerId);
            if (moveInput != null && (Math.abs(moveInput[0]) > 0.01f || Math.abs(moveInput[1]) > 0.01f)) {
                float yawRad = (float) Math.toRadians(player.getYRot());
                float forward = moveInput[0];
                float strafe = moveInput[1];
                double driftX = (-strafe * Math.cos(yawRad) - forward * Math.sin(yawRad)) * AIR_CONTROL_STRENGTH;
                double driftZ = (-strafe * Math.sin(yawRad) + forward * Math.cos(yawRad)) * AIR_CONTROL_STRENGTH;
                Vec3 currentVel = player.getDeltaMovement();
                player.setDeltaMovement(currentVel.add(driftX, 0, driftZ));
                player.hurtMarked = true;
            }

            long nowMs = System.currentTimeMillis();
            long timeSinceThrow = nowMs - data.throwTimeMs;
            if (!data.elytraBoostUsed && timeSinceThrow < 2000) {
                Long elytraRequestTime = elytraBoostRequests.get(playerId);
                if (elytraRequestTime != null && nowMs - elytraRequestTime <= ELYTRA_BOOST_REQUEST_WINDOW_MS) {
                    elytraBoostRequests.remove(playerId);
                    if (player.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof ElytraItem) {
                        Vec3 look = player.getViewVector(1.0f);
                        double boostStrength = 1.5;
                        player.setDeltaMovement(player.getDeltaMovement().add(
                                look.x * boostStrength,
                                look.y * boostStrength + 0.5,
                                look.z * boostStrength
                        ));
                        player.hurtMarked = true;
                        player.startFallFlying();
                        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.0f, 1.2f);
                        player.serverLevel().sendParticles(ParticleTypes.FIREWORK,
                                player.getX(), player.getY(), player.getZ(), 10, 0.2, 0.2, 0.2, 0.1);
                        data.elytraBoostUsed = true;
                        PoseNetworking.poseStates.put(playerId, PoseState.NONE);
                        PoseNetworking.broadcastPoseChange(server, playerId, PoseState.NONE);
                        airMovementInput.remove(playerId);
                        toRemove.add(playerId);
                        continue;
                    }
                } else if (elytraRequestTime != null && nowMs - elytraRequestTime > ELYTRA_BOOST_REQUEST_WINDOW_MS) {
                    elytraBoostRequests.remove(playerId);
                }
            }

            if (data.lastPos != null) {
                checkWallCollision(player, data);
            }
            data.lastPos = player.position();

            if (data.ticksFlying % 2 == 0) {
                spawnTrailParticles(player);
                if (data.wasOnFire && player.isOnFire()) {
                    spawnFireTrail(player, data);
                }
            }
            checkForNearbyCreepers(player);

            if (data.ticksFlying >= 10) {
                boolean onGround = player.onGround();
                boolean inWater = player.isInWater();
                boolean closeToGround = isCloseToGround(player);
                boolean isFalling = player.getDeltaMovement().y < -0.1;
                if (onGround || inWater || (closeToGround && isFalling)) {
                    PoseNetworking.poseStates.put(playerId, PoseState.NONE);
                    PoseNetworking.broadcastPoseChange(server, playerId, PoseState.NONE);
                    ServerPlayer landedPlayer = server.getPlayerList().getPlayer(playerId);
                    if (landedPlayer != null && !SpinHandler.isSpinning(playerId)) {
                        PoseNetworking.broadcastAnimState(landedPlayer, 0);
                    }
                    airMovementInput.remove(playerId);
                    if (data.wasOnFire) {
                        createFireExplosion(player);
                    }
                    spawnLandingParticles(player);
                    toRemove.add(playerId);
                }
            }

            int maxTicks = SpinHandler.isSpinning(playerId) ? 1200 : 100;
            if (data.ticksFlying > maxTicks) {
                PoseNetworking.poseStates.put(playerId, PoseState.NONE);
                PoseNetworking.broadcastPoseChange(server, playerId, PoseState.NONE);
                if (!SpinHandler.isSpinning(playerId)) {
                    ServerPlayer timedOutPlayer = server.getPlayerList().getPlayer(playerId);
                    if (timedOutPlayer != null) {
                        PoseNetworking.broadcastAnimState(timedOutPlayer, 0);
                    }
                }
                airMovementInput.remove(playerId);
                toRemove.add(playerId);
            }
        }
        thrownPlayers.keySet().removeAll(toRemove);
    }

    private static void checkWallCollision(ServerPlayer player, ThrownPlayerData data) {
        ServerLevel level = player.serverLevel();
        Vec3 currentPos = player.position();
        Vec3 velocity = player.getDeltaMovement();
        double speed = velocity.horizontalDistance();
        if (speed < 0.3) return;
        Vec3 direction = velocity.normalize();
        for (double dist = 0.3; dist <= 1.5; dist += 0.3) {
            Vec3 checkPos = currentPos.add(direction.multiply(dist, dist, dist));
            for (double yOff = 0; yOff <= 1.8; yOff += 0.9) {
                BlockPos blockPos = new BlockPos(
                        (int) Math.floor(checkPos.x),
                        (int) Math.floor(checkPos.y + yOff),
                        (int) Math.floor(checkPos.z)
                );
                BlockState blockState = level.getBlockState(blockPos);
                if (!blockState.isAir() && canBreakBlock(blockState, level, blockPos)) {
                    float hardness = blockState.getDestroySpeed(level, blockPos);
                    float damage = calculateWallDamage(hardness, speed);
                    level.destroyBlock(blockPos, true, player);
                    level.playSound(null, blockPos, blockState.getSoundType().getBreakSound(),
                            SoundSource.BLOCKS, 1.0f, 1.0f);
                    if (damage > 0) {
                        player.hurt(level.damageSources().flyIntoWall(), damage);
                    }
                    player.setDeltaMovement(velocity.multiply(0.7, 0.7, 0.7));
                    player.hurtMarked = true;
                    player.connection.send(new ClientboundSetEntityMotionPacket(player));
                    level.sendParticles(ParticleTypes.CRIT,
                            blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5,
                            10, 0.3, 0.3, 0.3, 0.1);
                }
            }
        }
    }

    private static boolean canBreakBlock(BlockState state, ServerLevel level, BlockPos pos) {
        if (state.is(Blocks.BEDROCK)) return false;
        if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)) return false;
        if (state.is(Blocks.REINFORCED_DEEPSLATE)) return false;
        if (state.is(Blocks.END_PORTAL_FRAME)) return false;
        if (state.is(Blocks.BARRIER)) return false;
        if (state.is(Blocks.COMMAND_BLOCK) || state.is(Blocks.CHAIN_COMMAND_BLOCK) ||
                state.is(Blocks.REPEATING_COMMAND_BLOCK)) return false;
        if (state.is(Blocks.STRUCTURE_BLOCK) || state.is(Blocks.JIGSAW)) return false;
        if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) ||
                state.is(Blocks.ENDER_CHEST) || state.is(Blocks.BARREL) ||
                state.is(Blocks.SHULKER_BOX) || state.is(Blocks.FURNACE) ||
                state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER) ||
                state.is(Blocks.BREWING_STAND) || state.is(Blocks.ENCHANTING_TABLE) ||
                state.is(Blocks.ANVIL) || state.is(Blocks.CHIPPED_ANVIL) ||
                state.is(Blocks.DAMAGED_ANVIL) || state.is(Blocks.CRAFTING_TABLE) ||
                state.is(Blocks.CARTOGRAPHY_TABLE) || state.is(Blocks.FLETCHING_TABLE) ||
                state.is(Blocks.GRINDSTONE) || state.is(Blocks.LOOM) ||
                state.is(Blocks.SMITHING_TABLE) || state.is(Blocks.STONECUTTER) ||
                state.is(Blocks.LECTERN) || state.is(Blocks.BEACON) ||
                state.is(Blocks.RESPAWN_ANCHOR) || state.is(Blocks.LODESTONE)) {
            return false;
        }
        if (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) ||
                state.is(BlockTags.FENCE_GATES)) {
            return false;
        }
        if (state.is(BlockTags.SIGNS) || state.is(BlockTags.ALL_HANGING_SIGNS)) {
            return false;
        }
        if (state.is(BlockTags.BEDS)) return false;
        if (state.is(BlockTags.BUTTONS) || state.is(Blocks.LEVER)) return false;
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) return false;
        return !(hardness > 25);
    }

    private static float calculateWallDamage(float hardness, double speed) {
        float baseDamage = hardness * 0.8f;
        float speedMultiplier = (float) Math.min(2.0, speed);
        return Math.max(1.0f, Math.min(10.0f, baseDamage * speedMultiplier));
    }

    private static void spawnTrailParticles(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position();
        level.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y + 0.5, pos.z, 1, 0.1, 0.1, 0.1, 0.02);
    }

    private static void spawnLandingParticles(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position();
        BlockPos groundPos = player.blockPosition().below();
        BlockState groundBlock = level.getBlockState(groundPos);

        level.sendParticles(ParticleTypes.EXPLOSION, pos.x, pos.y + 0.5, pos.z, 1, 0, 0, 0, 0);
        for (int i = 0; i < 24; i++) {
            double angle = (Math.PI * 2) * i / 24;
            double offsetX = Math.cos(angle) * 0.8;
            double offsetZ = Math.sin(angle) * 0.8;
            double velX = Math.cos(angle) * 0.3;
            double velZ = Math.sin(angle) * 0.3;
            level.sendParticles(ParticleTypes.CLOUD,
                    pos.x + offsetX, pos.y + 0.2, pos.z + offsetZ, 1, velX, 0.1, velZ, 0.05);
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    pos.x + offsetX * 0.5, pos.y + 0.1, pos.z + offsetZ * 0.5, 1, velX * 0.5, 0.2, velZ * 0.5, 0.02);
        }
        if (!groundBlock.isAir()) {
            BlockParticleOption blockParticle = new BlockParticleOption(ParticleTypes.BLOCK, groundBlock);
            for (int i = 0; i < 30; i++) {
                double offsetX = (level.random.nextDouble() - 0.5) * 1.5;
                double offsetZ = (level.random.nextDouble() - 0.5) * 1.5;
                double velY = level.random.nextDouble() * 0.5 + 0.2;
                level.sendParticles(blockParticle,
                        pos.x + offsetX, pos.y + 0.1, pos.z + offsetZ, 1, 0, velY, 0, 0.15);
            }
        }
        level.sendParticles(ParticleTypes.POOF, pos.x, pos.y + 0.3, pos.z, 15, 0.5, 0.3, 0.5, 0.05);
        level.sendParticles(ParticleTypes.CRIT, pos.x, pos.y + 0.5, pos.z, 10, 0.5, 0.5, 0.5, 0.3);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 0.5f, 1.2f);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.3f, 0.8f);
    }

    private static boolean isCloseToGround(ServerPlayer player) {
        if (player.onGround()) return true;
        if (player.getDeltaMovement().y >= 0) return false;
        double maxDistance = 1.0;
        double startY = player.getY();
        for (double checkY = startY; checkY > startY - maxDistance; checkY -= 0.5) {
            BlockPos blockPos = player.blockPosition().atY((int) checkY - 1);
            BlockState blockState = player.serverLevel().getBlockState(blockPos);
            if (!blockState.isAir() && blockState.isSolidRender(player.serverLevel(), blockPos)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ drop / escape

    public static boolean tryDrop(ServerPlayer holder) {
        UUID heldId = holding.get(holder.getUUID());
        if (heldId == null) return false;
        ServerPlayer held = holder.getServer().getPlayerList().getPlayer(heldId);
        if (held == null) {
            cleanupGrab(holder.getUUID());
            return false;
        }
        if (isInShieldMode(holder.getUUID())) {
            holder.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
        held.stopRiding();
        cleanupGrab(holder.getUUID());
        PoseNetworking.poseStates.put(holder.getUUID(), PoseState.NONE);
        PoseNetworking.poseStates.put(held.getUUID(), PoseState.NONE);

        MinecraftServer server = holder.getServer();
        if (server != null) {
            PoseNetworking.broadcastPoseChange(server, holder.getUUID(), PoseState.NONE);
            PoseNetworking.broadcastPoseChange(server, held.getUUID(), PoseState.NONE);
            PoseNetworking.broadcastAnimState(holder, 0);
            PoseNetworking.broadcastAnimState(held, 0);
            GrabNetworking.broadcastGrabState(server, holder.getUUID(), held.getUUID(), false);
            sendPassengerUpdate(server, holder);
        }
        holder.serverLevel().playSound(null, held.getX(), held.getY(), held.getZ(),
                SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f, 0.8f);
        return true;
    }

    public static boolean tryEscape(ServerPlayer held) {
        UUID holderId = heldBy.get(held.getUUID());
        if (holderId == null) return false;
        ServerPlayer holder = held.getServer().getPlayerList().getPlayer(holderId);
        if (holder != null && isInShieldMode(holderId)) {
            holder.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
        held.stopRiding();
        cleanupGrab(holderId);
        PoseNetworking.poseStates.put(held.getUUID(), PoseState.NONE);

        MinecraftServer server = held.getServer();
        if (holder != null) {
            PoseNetworking.poseStates.put(holder.getUUID(), PoseState.NONE);
            PoseNetworking.broadcastPoseChange(server, holder.getUUID(), PoseState.NONE);
            PoseNetworking.broadcastAnimState(holder, 0);
        }
        if (server != null) {
            PoseNetworking.broadcastPoseChange(server, held.getUUID(), PoseState.NONE);
            PoseNetworking.broadcastAnimState(held, 0);
            GrabNetworking.broadcastGrabState(server, holderId, held.getUUID(), false);
            if (holder != null) {
                sendPassengerUpdate(server, holder);
            }
            Vec3 escapePos = held.position().add(0, 0.1, 0);
            held.teleportTo(escapePos.x, escapePos.y, escapePos.z);
        }
        held.serverLevel().playSound(null, held.getX(), held.getY(), held.getZ(),
                SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.PLAYERS, 0.8f, 1.2f);
        return true;
    }

    public static void cleanupGrab(UUID holderUuid) {
        UUID heldUuid = holding.remove(holderUuid);
        if (heldUuid != null) {
            heldBy.remove(heldUuid);
        }
        shieldMode.remove(holderUuid);
        shieldSwapCooldown.remove(holderUuid);
        ArmorStand armorStand = shieldArmorStands.remove(holderUuid);
        if (armorStand != null && !armorStand.isRemoved()) {
            armorStand.discard();
        }
    }

    public static boolean isHolding(ServerPlayer player) {
        return holding.containsKey(player.getUUID());
    }

    public static boolean isBeingHeld(ServerPlayer player) {
        return heldBy.containsKey(player.getUUID());
    }

    public static void forceRelease(UUID playerUuid) {
        UUID heldUuid = holding.remove(playerUuid);
        if (heldUuid != null) heldBy.remove(heldUuid);
        UUID holderUuid = heldBy.remove(playerUuid);
        if (holderUuid != null) holding.remove(holderUuid);
        PoseNetworking.poseStates.remove(playerUuid);
        pendingThrows.remove(playerUuid);
        thrownPlayers.remove(playerUuid);
    }

    // ------------------------------------------------------------------ fire / creepers

    private static void spawnFireTrail(ServerPlayer player, ThrownPlayerData data) {
        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position();
        level.sendParticles(ParticleTypes.FLAME, pos.x, pos.y + 0.5, pos.z, 3, 0.2, 0.2, 0.2, 0.02);
        level.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y + 0.3, pos.z, 2, 0.1, 0.1, 0.1, 0.01);
        if (data.ticksFlying % 4 == 0) {
            BlockPos groundPos = player.blockPosition().below();
            for (int i = 0; i < 5; i++) {
                BlockState belowState = level.getBlockState(groundPos);
                if (!belowState.isAir()) {
                    BlockPos firePos = groundPos.above();
                    if (level.getBlockState(firePos).isAir()) {
                        level.setBlockAndUpdate(firePos, Blocks.FIRE.defaultBlockState());
                    }
                    break;
                }
                groundPos = groundPos.below();
            }
        }
    }

    private static void checkForNearbyCreepers(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        double checkRadius = 4.0;
        var nearbyCreepers = level.getEntitiesOfClass(
                Creeper.class,
                player.getBoundingBox().inflate(checkRadius),
                creeper -> creeper.isAlive() && player.distanceTo(creeper) <= checkRadius
        );
        for (var creeper : nearbyCreepers) {
            level.explode(creeper, creeper.getX(), creeper.getY(), creeper.getZ(),
                    3.0f, Level.ExplosionInteraction.MOB);
            creeper.discard();
            level.playSound(null, creeper.getX(), creeper.getY(), creeper.getZ(),
                    SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 1.0f, 1.0f);
        }

        var nearbyGhasts = level.getEntitiesOfClass(
                Ghast.class,
                player.getBoundingBox().inflate(checkRadius),
                ghast -> ghast.isAlive() && player.distanceTo(ghast) <= checkRadius
        );
        for (var ghast : nearbyGhasts) {
            Vec3 ghastPos = ghast.position();
            ghast.hurt(level.damageSources().playerAttack(player), 1000f);
            level.playSound(null, ghastPos.x, ghastPos.y, ghastPos.z,
                    ModSounds.EXPLOSION_IMPACT.get(), SoundSource.PLAYERS, 2.0f, 1.0f);
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, ghastPos.x, ghastPos.y, ghastPos.z, 3, 0.5, 0.5, 0.5, 0);
            level.sendParticles(ParticleTypes.CLOUD, ghastPos.x, ghastPos.y, ghastPos.z, 30, 1.5, 1.5, 1.5, 0.1);
            level.sendParticles(ParticleTypes.FLAME, ghastPos.x, ghastPos.y, ghastPos.z, 20, 1.0, 1.0, 1.0, 0.2);
            player.displayClientMessage(Component.literal("§6§l💥 GHAST OBLITERATED! 💥"), true);
        }
    }

    private static void createFireExplosion(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position();
        level.explode(player, pos.x, pos.y, pos.z, 2.0f, true, Level.ExplosionInteraction.MOB);
        level.playSound(null, pos.x, pos.y, pos.z,
                ModSounds.EXPLOSION_IMPACT.get(), SoundSource.PLAYERS, 1.5f, 1.0f);
        player.hurt(level.damageSources().onFire(), 16.0f);
        for (int i = 0; i < 20; i++) {
            double offsetX = (level.random.nextDouble() - 0.5) * 3;
            double offsetY = level.random.nextDouble() * 2;
            double offsetZ = (level.random.nextDouble() - 0.5) * 3;
            level.sendParticles(ParticleTypes.FLAME, pos.x + offsetX, pos.y + offsetY, pos.z + offsetZ, 1, 0, 0, 0, 0.1);
        }
    }

    // ------------------------------------------------------------------ air control / cleanup

    public static void setAirMovementInput(UUID playerId, float forward, float strafe) {
        if (thrownPlayers.containsKey(playerId) && Float.isFinite(forward) && Float.isFinite(strafe)) {
            airMovementInput.put(playerId, new float[]{
                    Math.max(-1.0f, Math.min(1.0f, forward)),
                    Math.max(-1.0f, Math.min(1.0f, strafe))
            });
        }
    }

    public static boolean isPlayerThrown(UUID playerId) {
        return thrownPlayers.containsKey(playerId);
    }

    public static void requestElytraBoost(UUID playerId) {
        if (thrownPlayers.containsKey(playerId) || pendingThrows.containsKey(playerId)) {
            elytraBoostRequests.put(playerId, System.currentTimeMillis());
        }
    }

    public static void fullCleanup(UUID playerId) {
        thrownPlayers.remove(playerId);
        pendingThrows.remove(playerId);
        elytraBoostRequests.remove(playerId);
        airMovementInput.remove(playerId);
        shieldMode.remove(playerId);
        shieldSwapCooldown.remove(playerId);
        ArmorStand armorStand = shieldArmorStands.remove(playerId);
        if (armorStand != null && !armorStand.isRemoved()) {
            armorStand.discard();
        }

        if (holding.containsKey(playerId)) {
            UUID heldId = holding.get(playerId);
            heldBy.remove(heldId);
            holding.remove(playerId);
            if (cachedServer != null) {
                ServerPlayer heldPlayer = cachedServer.getPlayerList().getPlayer(heldId);
                if (heldPlayer != null) {
                    heldPlayer.stopRiding();
                    PoseNetworking.poseStates.put(heldId, PoseState.NONE);
                    PoseNetworking.broadcastPoseChange(cachedServer, heldId, PoseState.NONE);
                    PoseNetworking.broadcastAnimState(heldPlayer, 0);
                    ClientboundSetPassengersPacket passPacket = new ClientboundSetPassengersPacket(heldPlayer);
                    for (ServerPlayer p : cachedServer.getPlayerList().getPlayers()) {
                        try { p.connection.send(passPacket); } catch (Exception ignored) { }
                    }
                }
                try {
                    GrabNetworking.broadcastGrabState(cachedServer, playerId, heldId, false);
                } catch (Exception ignored) { }
            }
            ArmorStand holderArmorStand = shieldArmorStands.remove(playerId);
            if (holderArmorStand != null && !holderArmorStand.isRemoved()) {
                holderArmorStand.discard();
            }
        }

        if (heldBy.containsKey(playerId)) {
            UUID holderId = heldBy.get(playerId);
            holding.remove(holderId);
            heldBy.remove(playerId);
            shieldMode.remove(holderId);
            if (cachedServer != null) {
                PoseNetworking.poseStates.put(playerId, PoseState.NONE);
                PoseNetworking.broadcastPoseChange(cachedServer, playerId, PoseState.NONE);
                try {
                    GrabNetworking.broadcastGrabState(cachedServer, holderId, playerId, false);
                } catch (Exception ignored) { }
                ServerPlayer holder = cachedServer.getPlayerList().getPlayer(holderId);
                if (holder != null) {
                    PoseNetworking.poseStates.put(holderId, PoseState.NONE);
                    PoseNetworking.broadcastPoseChange(cachedServer, holderId, PoseState.NONE);
                    PoseNetworking.broadcastAnimState(holder, 0);
                }
            }
            ArmorStand holderArmorStand = shieldArmorStands.remove(holderId);
            if (holderArmorStand != null && !holderArmorStand.isRemoved()) {
                holderArmorStand.discard();
            }
        }
    }

    // ------------------------------------------------------------------ human shield

    public static boolean toggleShieldMode(ServerPlayer holder) {
        UUID holderId = holder.getUUID();
        if (!holding.containsKey(holderId)) return false;
        Long cooldownEnd = shieldSwapCooldown.get(holderId);
        if (cooldownEnd != null && System.currentTimeMillis() < cooldownEnd) {
            long remaining = (cooldownEnd - System.currentTimeMillis()) / 100;
            holder.displayClientMessage(Component.literal("§cSwap cooldown! " + (remaining / 10.0) + "s"), true);
            return false;
        }
        UUID heldId = holding.get(holderId);
        ServerPlayer held = holder.getServer().getPlayerList().getPlayer(heldId);
        if (held == null) return false;

        boolean currentMode = shieldMode.getOrDefault(holderId, false);
        boolean newMode = !currentMode;
        shieldMode.put(holderId, newMode);
        shieldSwapCooldown.put(holderId, System.currentTimeMillis() + SHIELD_SWAP_COOLDOWN_MS);

        MinecraftServer server = holder.getServer();

        if (newMode) {
            holder.displayClientMessage(Component.literal("§b🛡 HUMAN SHIELD MODE"), true);
            held.displayClientMessage(Component.literal("§c⚠ You are now a SHIELD!"), true);
            held.stopRiding();
            ServerLevel level = holder.serverLevel();
            double yaw = Math.toRadians(holder.getYRot());
            double forwardX = -Math.sin(yaw) * 0.8;
            double forwardZ = Math.cos(yaw) * 0.8;
            ArmorStand armorStand = new ArmorStand(EntityType.ARMOR_STAND, level);
            armorStand.setPos(holder.getX() + forwardX, holder.getY() - 0.5, holder.getZ() + forwardZ);
            armorStand.setYRot(holder.getYRot());
            armorStand.setInvisible(true);
            armorStand.setNoGravity(true);
            armorStand.setInvulnerable(true);
            armorStand.setSilent(true);
            level.addFreshEntity(armorStand);
            shieldArmorStands.put(holderId, armorStand);
            held.startRiding(armorStand, true);
            level.playSound(null, holder.getX(), holder.getY(), holder.getZ(),
                    SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1.0f, 1.2f);
            holder.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 999999, 1, false, false, true));
            PoseNetworking.poseStates.put(heldId, PoseState.NONE);
            PoseNetworking.broadcastPoseChange(server, heldId, PoseState.NONE);
            PoseNetworking.poseStates.put(holderId, PoseState.NONE);
            PoseNetworking.broadcastAnimState(held, 29);
            PoseNetworking.broadcastAnimState(holder, 28);
            sendPassengerUpdate(server, holder);
            ArmorStand as = shieldArmorStands.get(holderId);
            if (as != null) {
                sendPassengerUpdate(server, as);
            }
            broadcastShieldMode(server, holderId, heldId, true);
        } else {
            holder.displayClientMessage(Component.literal("§e YEET"), true);
            held.displayClientMessage(Component.literal("§eBack to throw mode"), true);
            held.stopRiding();
            ArmorStand armorStand = shieldArmorStands.remove(holderId);
            if (armorStand != null) {
                armorStand.discard();
            }
            if (!holder.isPassenger() || holder.getVehicle() != held) {
                held.startRiding(holder, true);
            }
            PoseNetworking.poseStates.put(heldId, PoseState.GRABBED);
            PoseNetworking.broadcastPoseChange(server, heldId, PoseState.GRABBED);
            PoseNetworking.broadcastAnimState(holder, 3);
            holder.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            sendPassengerUpdate(server, holder);
            holder.serverLevel().playSound(null, holder.getX(), holder.getY(), holder.getZ(),
                    SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.PLAYERS, 1.0f, 1.0f);
            broadcastShieldMode(server, holderId, heldId, false);
        }
        return true;
    }

    public static void tickShieldMode(MinecraftServer server) {
        var iterator = shieldArmorStands.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            UUID holderId = entry.getKey();
            ArmorStand armorStand = entry.getValue();

            if (!shieldMode.getOrDefault(holderId, false)) {
                armorStand.discard();
                iterator.remove();
                continue;
            }
            ServerPlayer holder = server.getPlayerList().getPlayer(holderId);
            if (holder == null || armorStand.isRemoved()) {
                if (!armorStand.isRemoved()) armorStand.discard();
                iterator.remove();
                shieldMode.remove(holderId);
                continue;
            }
            UUID heldId = holding.get(holderId);
            if (heldId == null) {
                armorStand.discard();
                iterator.remove();
                shieldMode.remove(holderId);
                shieldSwapCooldown.remove(holderId);
                holder.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                PoseNetworking.poseStates.put(holderId, PoseState.NONE);
                PoseNetworking.broadcastPoseChange(server, holderId, PoseState.NONE);
                PoseNetworking.broadcastAnimState(holder, 0);
                GrabNetworking.broadcastGrabState(server, holderId, holderId, false);
                sendPassengerUpdate(server, holder);
                holder.displayClientMessage(Component.literal("§c Shield dropped!"), true);
                continue;
            }
            ServerPlayer heldPlayer = server.getPlayerList().getPlayer(heldId);
            if (heldPlayer == null || !heldPlayer.isAlive()) {
                armorStand.discard();
                iterator.remove();
                shieldMode.remove(holderId);
                shieldSwapCooldown.remove(holderId);
                holding.remove(holderId);
                heldBy.remove(heldId);
                holder.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                PoseNetworking.poseStates.put(holderId, PoseState.NONE);
                PoseNetworking.broadcastPoseChange(server, holderId, PoseState.NONE);
                PoseNetworking.broadcastAnimState(holder, 0);
                GrabNetworking.broadcastGrabState(server, holderId, heldId, false);
                sendPassengerUpdate(server, holder);
                holder.displayClientMessage(Component.literal("§c Shield died!"), true);
                continue;
            }

            double yaw = Math.toRadians(holder.getYRot());
            double forwardX = -Math.sin(yaw) * 0.8;
            double forwardZ = Math.cos(yaw) * 0.8;
            armorStand.setPos(holder.getX() + forwardX, holder.getY() - 0.5, holder.getZ() + forwardZ);
            armorStand.setYRot(holder.getYRot());

            if (armorStand.getFirstPassenger() instanceof ServerPlayer shieldPlayer) {
                float heldYaw = holder.getYRot();
                shieldPlayer.setYRot(heldYaw);
                shieldPlayer.setYBodyRot(heldYaw);
                shieldPlayer.setYHeadRot(heldYaw);
                CoopNetwork.sendToPlayer(holder,
                        new PoseNetworking.AnimStateSyncMsg(shieldPlayer.getUUID(), 29));
                holder.connection.send(new ClientboundTeleportEntityPacket(shieldPlayer));
                holder.connection.send(new ClientboundTeleportEntityPacket(armorStand));
            }
        }
    }

    public static boolean isInShieldMode(UUID holderId) {
        return shieldMode.getOrDefault(holderId, false);
    }

    public static ServerPlayer getShieldPlayer(ServerPlayer holder) {
        if (!isInShieldMode(holder.getUUID())) return null;
        UUID heldId = holding.get(holder.getUUID());
        if (heldId == null) return null;
        return holder.getServer().getPlayerList().getPlayer(heldId);
    }

    private static void broadcastShieldMode(MinecraftServer server, UUID holderId, UUID heldId, boolean enabled) {
        ShieldModeMsg msg = new ShieldModeMsg(holderId, heldId, enabled);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CoopNetwork.sendToPlayer(player, msg);
        }
    }

    /**
     * Fabric's ServerLivingEntityEvents.ALLOW_DAMAGE -> Forge LivingAttackEvent.
     * Cancelling the event is the Forge equivalent of returning false.
     */
    public static void registerShieldDamageEvent() {
        MinecraftForge.EVENT_BUS.register(GrabMechanic.class);
    }

    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer holder)) return;
        if (isInShieldMode(holder.getUUID())) {
            ServerPlayer shield = getShieldPlayer(holder);
            if (shield != null && shield.isAlive()) {
                shield.hurt(event.getSource(), event.getAmount());
                event.setCanceled(true);
            }
        }
    }

    // ------------------------------------------------------------------ shield mode message

    public record ShieldModeMsg(UUID holderId, UUID heldId, boolean enabled) {
        public static void encode(ShieldModeMsg m, net.minecraft.network.FriendlyByteBuf buf) {
            buf.writeUUID(m.holderId);
            buf.writeUUID(m.heldId);
            buf.writeBoolean(m.enabled);
        }
        public static ShieldModeMsg decode(net.minecraft.network.FriendlyByteBuf buf) {
            return new ShieldModeMsg(buf.readUUID(), buf.readUUID(), buf.readBoolean());
        }
        public static void handle(ShieldModeMsg m,
                                  java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            var c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () ->
                                    com.cooptest.client.GrabClientState.setShieldMode(
                                            m.holderId(), m.heldId(), m.enabled()));
                }
            });
            c.setPacketHandled(true);
        }

        public static void register() {
            CoopNetwork.register(ShieldModeMsg.class, ShieldModeMsg::encode, ShieldModeMsg::decode, ShieldModeMsg::handle);
        }
    }
}
