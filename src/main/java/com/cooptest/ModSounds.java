package com.cooptest;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Sound events, ported from Fabric (SoundEvent.of + Registry.register) to a Forge
 * DeferredRegister. Namespace kept as "testcoop" so ids and assets/testcoop/sounds.json
 * entries are unchanged.
 *
 * NOTE: the Fabric mod created but never registered "cooldap" (PERFECT_DAP) and
 * "aura" (AURA). They ARE registered here — that is the correct Forge pattern and
 * only makes those sounds resolve properly on dedicated servers. If exact silence
 * of those two is ever required for parity, remove their register() lines.
 *
 * Access at call sites via e.g. ModSounds.EPIC_DAP.get().
 */
public class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, CoopMoves.NAMESPACE);

    private static RegistryObject<SoundEvent> reg(String path) {
        return SOUND_EVENTS.register(path,
                () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(CoopMoves.NAMESPACE, path)));
    }

    public static final RegistryObject<SoundEvent> EPIC_DAP         = reg("epic_dap");
    public static final RegistryObject<SoundEvent> EXPLOSION_IMPACT = reg("explosion_impact");
    public static final RegistryObject<SoundEvent> MAHITO           = reg("mahito");
    public static final RegistryObject<SoundEvent> GALACTIC_DAP     = reg("galactic_dap");
    public static final RegistryObject<SoundEvent> TRUE_FRIENDSHIP  = reg("true_friendship");
    public static final RegistryObject<SoundEvent> IMPACT           = reg("impact");
    public static final RegistryObject<SoundEvent> MARIO_JUMP       = reg("mariojump");
    public static final RegistryObject<SoundEvent> FIRE_IMPACT      = reg("fireimpact");
    public static final RegistryObject<SoundEvent> DAP_MISS         = reg("dap.miss");
    public static final RegistryObject<SoundEvent> DAP_WEAK         = reg("dap.weak");
    public static final RegistryObject<SoundEvent> SNAP             = reg("snap");
    public static final RegistryObject<SoundEvent> DAP_HIT          = reg("dap.hit");
    public static final RegistryObject<SoundEvent> SLAP             = reg("slap");
    public static final RegistryObject<SoundEvent> PERFECT_DAP      = reg("cooldap");
    public static final RegistryObject<SoundEvent> AURA             = reg("aura");
    public static final RegistryObject<SoundEvent> HELI             = reg("heli");
    public static final RegistryObject<SoundEvent> CLAP_1           = reg("clap1");
    public static final RegistryObject<SoundEvent> CLAP_2           = reg("clap2");
    public static final RegistryObject<SoundEvent> CLAP_3           = reg("clap3");
    public static final RegistryObject<SoundEvent> CLAP_4           = reg("clap4");
    public static final RegistryObject<SoundEvent> CLAP_5           = reg("clap5");
    public static final RegistryObject<SoundEvent> CLAP_6           = reg("clap6");

    /** Equivalent of the Fabric CLAP_SOUNDS array (resolved SoundEvents). */
    public static SoundEvent[] clapSounds() {
        return new SoundEvent[] {
                CLAP_1.get(), CLAP_2.get(), CLAP_3.get(),
                CLAP_4.get(), CLAP_5.get(), CLAP_6.get()
        };
    }
}
