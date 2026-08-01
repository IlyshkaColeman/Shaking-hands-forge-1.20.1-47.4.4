package com.cooptest;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Mahito's Curse: if a player under the Mahito effect daps someone, the partner is
 * cursed — briefly frozen/levitated, then dramatically "trolled" to death. Ported
 * from Fabric to Forge 1.20.1.
 *
 * {@link #checkForMahitoTroll} is invoked by the Dap handlers (ported later); the
 * curse itself is fully functional here. Translations follow the project template
 * (StatusEffect*->MobEffect*, ServerWorld->ServerLevel, getVelocity/velocityModified
 * ->getDeltaMovement/hurtMarked, sendMessage->displayClientMessage, damage->hurt,
 * CustomPayload->CoopNetwork message).
 */
public final class MahitoTrollHandler {

    private MahitoTrollHandler() {}

    private static final Map<UUID, TrollData> trolledPlayers = new HashMap<>();
    private static final long TROLL_START_DELAY_MS = 2000;
    private static final long TROLL_DEATH_DELAY_MS = 4000;

    private static class TrollData {
        long dapTime;
        boolean trollStarted;
        UUID trollerId;
        TrollData(long time, UUID troller) {
            this.dapTime = time;
            this.trollStarted = false;
            this.trollerId = troller;
        }
    }

    public static void register() { }

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, TrollData>> iter = trolledPlayers.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<UUID, TrollData> entry = iter.next();
            TrollData data = entry.getValue();
            ServerPlayer victim = server.getPlayerList().getPlayer(entry.getKey());
            if (victim == null || !victim.isAlive()) { iter.remove(); continue; }
            long elapsed = now - data.dapTime;
            if (!data.trollStarted && elapsed >= TROLL_START_DELAY_MS) {
                data.trollStarted = true;
                startTroll(victim, server.getPlayerList().getPlayer(data.trollerId));
            }
            if (data.trollStarted && elapsed >= TROLL_START_DELAY_MS + TROLL_DEATH_DELAY_MS) {
                executeDeath(victim);
                iter.remove();
            }
            if (data.trollStarted) {
                victim.setDeltaMovement(0, victim.getDeltaMovement().y, 0);
                victim.hurtMarked = true;
            }
        }
    }

    /** Called by the Dap handlers when a dap connects. */
    public static void checkForMahitoTroll(ServerPlayer p1, ServerPlayer p2) {
        boolean p1HasMahito = p1.hasEffect(ModEffects.MAHITO.get());
        boolean p2HasMahito = p2.hasEffect(ModEffects.MAHITO.get());
        if (p1HasMahito && !p2HasMahito) {
            startMahitoTroll(p2, p1);
            p1.removeEffect(ModEffects.MAHITO.get());
        } else if (p2HasMahito && !p1HasMahito) {
            startMahitoTroll(p1, p2);
            p2.removeEffect(ModEffects.MAHITO.get());
        }
    }

    private static void startMahitoTroll(ServerPlayer victim, ServerPlayer troller) {
        trolledPlayers.put(victim.getUUID(), new TrollData(System.currentTimeMillis(), troller.getUUID()));
        if (troller != null) {
            troller.displayClientMessage(
                    Component.literal("§c§l☠ You cursed " + victim.getName().getString() + "! ☠"), true);
        }
    }

    private static void startTroll(ServerPlayer victim, ServerPlayer troller) {
        ServerLevel world = victim.serverLevel();
        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 255, false, false));
        victim.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 100, 1, false, false));
        victim.addEffect(new MobEffectInstance(MobEffects.JUMP, 100, 128, false, false));
        world.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                ModSounds.MAHITO.get(), SoundSource.PLAYERS, 2.0f, 1.0f);
        MinecraftServer server = victim.getServer();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                CoopNetwork.sendToPlayer(player, new MahitoAnimMsg(victim.getUUID()));
            }
        }
        world.sendParticles(ParticleTypes.SOUL, victim.getX(), victim.getY() + 1, victim.getZ(), 20, 0.5, 1.0, 0.5, 0.02);
        world.sendParticles(ParticleTypes.SMOKE, victim.getX(), victim.getY() + 1, victim.getZ(), 15, 0.4, 0.8, 0.4, 0.01);
        victim.displayClientMessage(Component.literal("§4§l☠ MAHITO'S CURSE! ☠"), true);
        world.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 0.5f, 1.5f);
    }

    private static void executeDeath(ServerPlayer victim) {
        ServerLevel world = victim.serverLevel();
        double x = victim.getX(), y = victim.getY(), z = victim.getZ();
        world.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y + 2, z, 1, 0, 0, 0, 0);
        world.sendParticles(ParticleTypes.FIREWORK, x, y + 2, z, 50, 0.5, 0.5, 0.5, 0.3);
        world.sendParticles(ParticleTypes.FLASH, x, y + 2, z, 3, 0, 0, 0, 0);
        world.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y + 2, z, 30, 0.4, 0.4, 0.4, 0.15);
        world.sendParticles(ParticleTypes.DRAGON_BREATH, x, y + 2, z, 20, 0.3, 0.3, 0.3, 0.1);
        world.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.PLAYERS, 2.0f, 1.0f);
        world.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.5f, 1.2f);
        world.playSound(null, x, y, z, SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0f, 0.5f);
        victim.hurt(world.damageSources().magic(), Float.MAX_VALUE);
        MinecraftServer server = victim.getServer();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player != victim) {
                    player.displayClientMessage(Component.literal(
                            "§4" + victim.getName().getString() + " §7was trolled by §cMahito's Curse!"), false);
                }
            }
        }
    }

    public static boolean isBeingTrolled(UUID playerId) {
        return trolledPlayers.containsKey(playerId);
    }

    public static void cleanup(UUID playerId) {
        trolledPlayers.remove(playerId);
    }

    // ------------------------------------------------------------------ networking

    public record MahitoAnimMsg(UUID playerId) {
        public static void encode(MahitoAnimMsg m, FriendlyByteBuf buf) { buf.writeUUID(m.playerId); }
        public static MahitoAnimMsg decode(FriendlyByteBuf buf) { return new MahitoAnimMsg(buf.readUUID()); }
        public static void handle(MahitoAnimMsg m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (!c.getDirection().getReceptionSide().isServer()) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.cooptest.client.MahitoClientHandler.onMahitoAnim(m.playerId()));
                }
            });
            c.setPacketHandled(true);
        }
    }

    public static void registerMessages() {
        CoopNetwork.register(MahitoAnimMsg.class, MahitoAnimMsg::encode, MahitoAnimMsg::decode, MahitoAnimMsg::handle);
    }
}
