package com.nobodiiiii.createbiotech.content.buttercat.mob_effect;

import com.nobodiiiii.createbiotech.registry.CBConfigs;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

public class ButterRotationEffect extends MobEffect {
    public ButterRotationEffect() {
        super(MobEffectCategory.NEUTRAL,  0xFFAA00);
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if(entity.level().isClientSide) return true;
        float rotationSpeed = getRotationAngularSpeed() * (6 * amplifier + 1);
        float newYaw = entity.getYRot() + rotationSpeed;

        entity.setYRot(newYaw);
        entity.setYHeadRot(newYaw);
        entity.setYBodyRot(newYaw);
        return true;
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }

    public static float getRotationAngularSpeed() {
        return CBConfigs.SERVER.butterCat.rotationAngularSpeed.get().floatValue();
    }
}

