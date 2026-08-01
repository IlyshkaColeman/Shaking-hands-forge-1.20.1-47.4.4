package com.cooptest;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Clap mechanic: tap the clap key for tiered claps (slow / spam / strong). Ported
 * from Fabric to Forge 1.20.1. A strong clap scares nearby animals; nearby players
 * clapping within a short window produce a "sync" clap. Trigger lives in the client
 * grab input handler (V key when not holding anyone).
 *
 * AnimState ordinals travel over the wire; CoopAnimationHandler is client-only, so
 * we send raw ordinals here: CLAP = 49, CLAP_SPAM = 50, CLAP_STRONG = 51.
 *
 * Fabric API translations: ServerWorld->ServerLevel, Vec3d->Vec3, Box->AABB,
 * AnimalEntity->Animal, MobEntity->Mob, getEntitiesByClass->getEntitiesOfClass,
 * setVelocity/velocityModified->setDeltaMovement/hurtMarked, getBodyYaw->yBodyRot,
 * navigation.startMovingTo->moveTo, CustomPayload->CoopNetwork message.
 */
public final class ClapHandler {

    private ClapHandler() {}

    private static final long TIER_SLOW_MS   = 600;
    private static final long TIER_MEDIUM_MS = 200;
    private static final long SYNC_WINDOW_MS = 300;
    private static final double SYNC_RANGE   = 6.0;
    private static final int IMPACT_TICKS_SLOW   = 5;
    private static final int IMPACT_TICKS_MEDIUM = 3;
    private static final int IMPACT_TICKS_FAST   = 2;

    private static final int ANIM_CLAP        = 49;
    private static final int ANIM_CLAP_SPAM   = 50;
    private static final int ANIM_CLAP_STRONG = 51;

    private static final Random RANDOM = new Random();
    private static final Map<UUID, Long> lastPressTime = new HashMap<>();
    private static final List<ScheduledEffect> scheduled = new ArrayList<>();

    private static class ScheduledEffect {
        final ServerLevel world;
        final Vec3 armTip;
        final boolean syncClap;
        final int tier;
        int ticksRemaining;
        ScheduledEffect(ServerLevel world, Vec3 armTip, boolean syncClap, int tier, int delay) {
            this.world = world; this.armTip = armTip;
            this.syncClap = syncClap; this.tier = tier; this.ticksRemaining = delay;
        }
    }

    public static void register() { }

    public static void tick(MinecraftServer server) {
        Iterator<ScheduledEffect> it = scheduled.iterator();
        while (it.hasNext()) {
            ScheduledEffect fx = it.next();
            if (--fx.ticksRemaining <= 0) { playEffects(fx); it.remove(); }
        }
    }

    private static void onClapRequest(ServerPlayer player) {
        if (GrabMechanic.isHolding(player)) return;
        UUID id = player.getUUID();
        long now = System.currentTimeMillis();
        Long last = lastPressTime.get(id);
        int tier;
        if (last == null || now - last > TIER_SLOW_MS) tier = 0;
        else if (now - last > TIER_MEDIUM_MS) tier = 1;
        else tier = 2;
        lastPressTime.put(id, now);

        int animOrdinal = switch (tier) {
            case 1 -> ANIM_CLAP_SPAM;
            case 2 -> ANIM_CLAP_STRONG;
            default -> ANIM_CLAP;
        };
        PoseNetworking.broadcastAnimState(player, animOrdinal);

        if (tier == 2) {
            AABB fearBox = player.getBoundingBox().inflate(15.0);
            player.serverLevel().getEntitiesOfClass(Animal.class, fearBox, a -> !a.isRemoved())
                    .forEach(animal -> {
                        Vec3 away = animal.position().subtract(player.position());
                        if (away.horizontalDistanceSqr() < 0.001) away = new Vec3(1, 0, 0);
                        away = away.normalize();
                        animal.setDeltaMovement(away.x * 0.55, 0.35, away.z * 0.55);
                        animal.hurtMarked = true;
                        // Animal already extends Mob, so use it directly (no instanceof).
                        animal.setTarget(null);
                        Vec3 fleeTarget = animal.position().add(away.scale(8.0));
                        animal.getNavigation().moveTo(fleeTarget.x, fleeTarget.y, fleeTarget.z, 1.4);
                    });
        }

        boolean isSync = false;
        MinecraftServer server = player.getServer();
        if (server != null) {
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (other == player) continue;
                Long otherLast = lastPressTime.get(other.getUUID());
                if (otherLast == null || now - otherLast > SYNC_WINDOW_MS) continue;
                if (player.position().distanceTo(other.position()) <= SYNC_RANGE) { isSync = true; break; }
            }
        }

        double yawRad = Math.toRadians(player.yBodyRot);
        Vec3 armTip = player.position().add(-Math.sin(yawRad) * 0.5, 1.2, Math.cos(yawRad) * 0.5);
        int delay = switch (tier) {
            case 1 -> IMPACT_TICKS_MEDIUM;
            case 2 -> IMPACT_TICKS_FAST;
            default -> IMPACT_TICKS_SLOW;
        };
        scheduled.add(new ScheduledEffect(player.serverLevel(), armTip, isSync, tier, delay));
    }

    private static void playEffects(ScheduledEffect fx) {
        Vec3 p = fx.armTip;
        SoundEvent[] claps = ModSounds.clapSounds();
        SoundEvent sound = claps[RANDOM.nextInt(claps.length)];
        if (fx.syncClap) {
            fx.world.playSound(null, p.x, p.y, p.z, sound, SoundSource.PLAYERS, 1.4f, 1.1f);
            fx.world.sendParticles(ParticleTypes.FIREWORK, p.x, p.y, p.z, 14, 0.1, 0.1, 0.1, 0.05);
            fx.world.sendParticles(ParticleTypes.WAX_ON, p.x, p.y, p.z, 8, 0.1, 0.1, 0.1, 0.02);
            fx.world.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 5, 0.1, 0.1, 0.1, 0.02);
        } else switch (fx.tier) {
            case 0 -> {
                fx.world.playSound(null, p.x, p.y, p.z, sound, SoundSource.PLAYERS, 0.9f, 1.0f);
                fx.world.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 5, 0.1, 0.1, 0.1, 0.03);
            }
            case 1 -> {
                fx.world.playSound(null, p.x, p.y, p.z, sound, SoundSource.PLAYERS, 1.0f, 1.1f);
                fx.world.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 3, 0.1, 0.1, 0.1, 0.04);
                fx.world.sendParticles(ParticleTypes.ENCHANTED_HIT, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.03);
            }
            case 2 -> {
                fx.world.playSound(null, p.x, p.y, p.z, sound, SoundSource.PLAYERS, 1.2f, 1.3f);
                fx.world.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 4, 0.12, 0.12, 0.12, 0.05);
                fx.world.sendParticles(ParticleTypes.ENCHANTED_HIT, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.04);
                fx.world.sendParticles(ParticleTypes.FIREWORK, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.03);
            }
        }
    }

    public static void cleanup(UUID playerId) {
        lastPressTime.remove(playerId);
    }

    // ------------------------------------------------------------------ networking

    public record ClapRequestMsg() {
        public static void encode(ClapRequestMsg m, FriendlyByteBuf buf) { }
        public static ClapRequestMsg decode(FriendlyByteBuf buf) { return new ClapRequestMsg(); }
        public static void handle(ClapRequestMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player == null) return;
                if (!CoopMovesConfig.get().enableClap) return;
                onClapRequest(player);
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(ClapRequestMsg.class,
                ClapRequestMsg::encode, ClapRequestMsg::decode, ClapRequestMsg::handle);
    }

    public static void sendClapRequest() {
        CoopNetwork.sendToServer(new ClapRequestMsg());
    }
}
