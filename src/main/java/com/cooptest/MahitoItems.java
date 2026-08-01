package com.cooptest;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The custom "Mahito" potion, ported from Fabric.
 *
 * 1.21 used the data-component system (DataComponentTypes.POTION_CONTENTS +
 * PotionContentsComponent) and Registry.registerReference(Registries.POTION,...).
 * 1.20.1 has no data components: potions are registered via ForgeRegistries.POTIONS
 * and applied to a potion ItemStack with PotionUtils.setPotion. Duration/amplifier
 * (1200 ticks = 60s, amplifier 0) preserved.
 *
 * The creative-tab insertion (Fabric ItemGroupEvents on FOOD_AND_DRINK) is handled
 * by CoopMoves#addCreativeTabItems via BuildCreativeModeTabContentsEvent.
 */
public class MahitoItems {

    public static final DeferredRegister<Potion> POTIONS =
            DeferredRegister.create(ForgeRegistries.POTIONS, CoopMoves.NAMESPACE);

    public static final RegistryObject<Potion> MAHITO_POTION =
            POTIONS.register("mahito_stuff",
                    () -> new Potion(new MobEffectInstance(ModEffects.MAHITO.get(), 1200, 0)));

    public static ItemStack createMahitoPotion() {
        return PotionUtils.setPotion(new ItemStack(Items.POTION), MAHITO_POTION.get());
    }
}
