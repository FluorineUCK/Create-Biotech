package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import java.util.function.Consumer;

import com.nobodiiiii.createbiotech.client.SonicDogCannonArmPose;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import org.jetbrains.annotations.Nullable;

public class SonicDogCannonItem extends Item {

	public SonicDogCannonItem(Properties properties) {
		super(properties);
	}

	@Override
	@OnlyIn(Dist.CLIENT)
	public void initializeClient(Consumer<IClientItemExtensions> consumer) {
		consumer.accept(new IClientItemExtensions() {
			@Override
			@Nullable
			public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
				return SonicDogCannonArmPose.ARM_POSE.getValue();
			}
		});
	}
}
