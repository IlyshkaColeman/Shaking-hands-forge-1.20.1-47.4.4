package com.cooptest;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * Ported from Fabric StatusEffect. In 1.20.1 the class is MobEffect and the
 * per-tick hooks are named differently:
 *   Yarn canApplyUpdateEffect(int,int) -> isDurationEffectTick(int,int)
 *   Yarn applyUpdateEffect(LivingEntity,int) (returns boolean in 1.21) -> applyEffectTick (void in 1.20.1)
 * Color and category preserved (HARMFUL, 0x9932CC).
 */
public class MahitoEffect extends MobEffect {

    public MahitoEffect() {
        super(MobEffectCategory.HARMFUL, 0x9932CC);
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        return duration % 10 == 0;
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {
        // no-op (matches Fabric applyUpdateEffect returning true with no side effects)
    }
}
