package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nobodiiiii.createbiotech.content.biopackager.BioPackagerContraptionDamageTracker;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

@Mixin(BlockBreakingMovementBehaviour.class)
public abstract class BlockBreakingMovementBehaviourMixin {

	@Inject(method = "damageEntities(Lcom/simibubi/create/content/contraptions/behaviour/MovementContext;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;)V",
		at = @At("HEAD"))
	private void createBiotech$beginTrackingContraptionDamage(MovementContext context, BlockPos pos, Level world,
		CallbackInfo ci) {
		AbstractContraptionEntity contraptionEntity = context.contraption.entity;
		if (contraptionEntity == null)
			return;
		BioPackagerContraptionDamageTracker.pushDamageContext(contraptionEntity);
	}

	@Inject(method = "damageEntities(Lcom/simibubi/create/content/contraptions/behaviour/MovementContext;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;)V",
		at = @At("RETURN"))
	private void createBiotech$endTrackingContraptionDamage(MovementContext context, BlockPos pos, Level world,
		CallbackInfo ci) {
		AbstractContraptionEntity contraptionEntity = context.contraption.entity;
		if (contraptionEntity == null)
			return;
		BioPackagerContraptionDamageTracker.popDamageContext();
	}
}
