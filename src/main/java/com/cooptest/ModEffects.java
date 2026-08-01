package com.cooptest;

import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Status effects, ported to Forge. Fabric used
 * Registry.registerReference(Registries.STATUS_EFFECT, ...) returning a
 * RegistryEntry; here a RegistryObject<MobEffect> plays the same role.
 * Access via ModEffects.MAHITO.get().
 */
public class ModEffects {

    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, CoopMoves.NAMESPACE);

    public static final RegistryObject<MobEffect> MAHITO =
            MOB_EFFECTS.register("mahito", MahitoEffect::new);
}
