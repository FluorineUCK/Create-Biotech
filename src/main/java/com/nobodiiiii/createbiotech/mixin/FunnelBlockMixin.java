package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nobodiiiii.createbiotech.content.beltsurface.BeltFunnelStateExtensions;
import com.nobodiiiii.createbiotech.content.beltsurface.BeltSurface;
import com.nobodiiiii.createbiotech.content.beltsurface.BeltSurfaceResolver;
import com.nobodiiiii.createbiotech.content.processing.basin.BasinEntityProcessing;
import com.simibubi.create.content.logistics.funnel.AbstractFunnelBlock;
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.foundation.block.ProperWaterloggedBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(FunnelBlock.class)
public abstract class FunnelBlockMixin {

	@Inject(method = "entityInside(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)V",
		at = @At("HEAD"))
	private void createBiotech$captureSmallSlimeOnContact(BlockState state, Level level, BlockPos pos, Entity entity,
		CallbackInfo ci) {
		BasinEntityProcessing.handleFunnelEntityInside(level, pos, entity);
	}

	@Inject(method = "getStateForPlacement(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/level/block/state/BlockState;",
		at = @At("RETURN"), cancellable = true)
	private void createBiotech$getStateForPlacement(BlockPlaceContext context,
		CallbackInfoReturnable<BlockState> cir) {
		BlockState state = cir.getReturnValue();
		if (state == null)
			return;
		Direction worldFacing = AbstractFunnelBlock.getFunnelFacing(state);
		if (worldFacing == null)
			return;

		BeltSurface surface = BeltSurfaceResolver.resolveForPlacement(context.getLevel(), context.getClickedPos());
		if (surface == null)
			return;
		Direction localFacing = surface.localize(worldFacing);
		if (localFacing.getAxis().isVertical())
			return;

		cir.setReturnValue(buildBeltFunnelState(state, surface, localFacing, context.getLevel(),
			context.getClickedPos()));
	}

	// TODO(belt-placement-restore): re-enable once the crash is diagnosed and the revert/re-attach
	// invariants are stabilised. The original idea: when a slime belt is placed adjacent to an existing
	// plain FunnelBlock, this updateShape hook converts the funnel into a BeltFunnel attached to the new
	// surface. Currently this path crashes when placing a belt next to a funnel (likely a re-entrancy or
	// surface-stale issue during the LevelAccessor neighbour update). For now the auto-attach is disabled:
	// only initial placement via getStateForPlacement specialises (which is sufficient for the typical
	// "right-click chest above belt" workflow). Players who already have a placed funnel and want to
	// attach it after building the belt must remove and replace the funnel.
	// @Inject(method = "updateShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
	//   at = @At("HEAD"), cancellable = true)
	private void createBiotech$updateShape(BlockState state, Direction direction, BlockState neighbour,
		LevelAccessor world, BlockPos pos, BlockPos neighbourPos, CallbackInfoReturnable<BlockState> cir) {
		Direction worldFacing = AbstractFunnelBlock.getFunnelFacing(state);
		if (worldFacing == null)
			return;

		BeltSurface surface = BeltSurfaceResolver.resolveForPlacement(world, pos);
		if (surface == null)
			return;
		// only react when the neighbour on the belt side changed
		if (direction != surface.outwardNormal().getOpposite())
			return;
		Direction localFacing = surface.localize(worldFacing);
		if (localFacing.getAxis().isVertical())
			return;

		cir.setReturnValue(buildBeltFunnelState(state, surface, localFacing, world, pos));
	}

	private BlockState buildBeltFunnelState(BlockState vanillaState, BeltSurface surface, Direction localFacing,
		LevelAccessor world, BlockPos pos) {
		BlockState localState = vanillaState.setValue(FunnelBlock.FACING, localFacing);
		FunnelBlock self = (FunnelBlock) (Object) this;
		BlockState beltFunnel = ProperWaterloggedBlock.withWater(world,
			self.getEquivalentBeltFunnel(world, pos, localState), pos);
		return beltFunnel
			.setValue(BeltFunnelBlock.HORIZONTAL_FACING, localFacing)
			.setValue(BeltFunnelStateExtensions.ATTACHMENT_SURFACE, surface.outwardNormal().getOpposite())
			.setValue(BeltFunnelBlock.SHAPE,
				BeltFunnelBlock.getShapeForPosition(world, pos, localFacing,
					vanillaState.getValue(FunnelBlock.EXTRACTING)));
	}
}
