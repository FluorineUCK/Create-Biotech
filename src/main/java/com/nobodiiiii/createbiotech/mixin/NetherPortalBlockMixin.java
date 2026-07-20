package com.nobodiiiii.createbiotech.mixin;

import java.util.Optional;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.fluid.NetherPortalFluidBlockEntity;
import com.nobodiiiii.createbiotech.registry.CBFluids;

import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.DimensionTransition;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin implements EntityBlock {
	@Shadow
	private static DimensionTransition getDimensionTransitionFromExit(Entity entity, BlockPos pos,
		BlockUtil.FoundRectangle rectangle, ServerLevel level,
		DimensionTransition.PostDimensionTransition postDimensionTransition) {
		throw new AssertionError();
	}

	@Override
	@Nullable
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new NetherPortalFluidBlockEntity(pos, state);
	}

	@Inject(method = "getExitPortal",
		at = @At(value = "INVOKE_ASSIGN",
			target = "Lnet/minecraft/world/level/portal/PortalForcer;findClosestPortalPosition(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/level/border/WorldBorder;)Ljava/util/Optional;"),
		cancellable = true)
	private void createBiotech$includeTeleportationFluid(ServerLevel level, Entity entity, BlockPos pos,
		BlockPos exitPos, boolean isNether, WorldBorder worldBorder,
		CallbackInfoReturnable<DimensionTransition> cir, @Local Optional<BlockPos> portalPosition) {
		if (portalPosition.isEmpty())
			return;

		BlockPos fluidPos = portalPosition.get();
		if (!level.getFluidState(fluidPos)
			.getType()
			.isSame(CBFluids.TELEPORTATION.get()))
			return;

		BlockUtil.FoundRectangle fluidRectangle = new BlockUtil.FoundRectangle(fluidPos, 1, 1);
		DimensionTransition.PostDimensionTransition postTransition =
			DimensionTransition.PLAY_PORTAL_SOUND.then(transitionedEntity ->
				transitionedEntity.placePortalTicket(fluidPos));
		cir.setReturnValue(getDimensionTransitionFromExit(entity, pos, fluidRectangle, level, postTransition));
	}
}
