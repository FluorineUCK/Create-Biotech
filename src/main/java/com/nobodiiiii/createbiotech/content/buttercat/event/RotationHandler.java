package com.nobodiiiii.createbiotech.content.buttercat.event;

import com.nobodiiiii.createbiotech.content.buttercat.ButterRotation;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.registry.CBMobEffects;

import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;


@EventBusSubscriber(modid = CreateBiotech.MOD_ID, value = Dist.CLIENT)
public class RotationHandler {
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Player player = Minecraft.getInstance().player;
        if (player == null)
            return;
        MobEffectInstance effect = player.getEffect(CBMobEffects.BUTTER_ROTATION.getDelegate());
        if (effect == null)
            return;

        float newYaw = player.getYRot() + ButterRotation.getTickAngleSpeed(effect.getAmplifier());
        player.setYRot(newYaw);
        player.setYHeadRot(newYaw);
        player.setYBodyRot(newYaw);
    }
}

