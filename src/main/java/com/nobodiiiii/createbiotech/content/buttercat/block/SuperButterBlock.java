package com.nobodiiiii.createbiotech.content.buttercat.block;

import com.nobodiiiii.createbiotech.registry.CBAttachmentTypes;

import net.minecraft.world.entity.LivingEntity;

public class SuperButterBlock extends ButterBlock {
	public SuperButterBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected int getRotationAmplifier(LivingEntity entity, long gameTime) {
		return entity.getData(CBAttachmentTypes.SUPER_BUTTER_STREAK).recordStep(gameTime);
	}
}
