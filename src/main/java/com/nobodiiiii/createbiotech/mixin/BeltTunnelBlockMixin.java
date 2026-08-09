package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nobodiiiii.createbiotech.content.beltsurface.BeltTunnelCapabilityInvalidator;
import com.nobodiiiii.createbiotech.content.beltsurface.StandardItemBeltPort;
import com.nobodiiiii.createbiotech.content.beltsurface.StandardItemBeltPortResolver;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.logistics.tunnel.BeltTunnelBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

@Mixin(BeltTunnelBlock.class)
public abstract class BeltTunnelBlockMixin {

	@Shadow
	protected abstract boolean canHaveWindow(BlockGetter reader, BlockPos pos, Axis axis);

	@Inject(method = "canSurvive(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z",
		at = @At("HEAD"), cancellable = true)
	private void createBiotech$canSurvive(BlockState state, LevelReader world, BlockPos pos,
		CallbackInfoReturnable<Boolean> cir) {
		if (StandardItemBeltPortResolver.canSupportTunnel(world.getBlockState(pos.below())))
			cir.setReturnValue(true);
	}

	@Inject(method = "isValidPositionForPlacement(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z",
		at = @At("HEAD"), cancellable = true)
	private void createBiotech$isValidPositionForPlacement(BlockState state, LevelReader world, BlockPos pos,
		CallbackInfoReturnable<Boolean> cir) {
		if (StandardItemBeltPortResolver.isHorizontalItemBelt(world.getBlockState(pos.below())))
			cir.setReturnValue(true);
	}

	@Inject(method = "updateShape", at = @At("HEAD"))
	private void createBiotech$invalidateCapabilityWhenBeltChanges(BlockState state, Direction facing,
		BlockState facingState, LevelAccessor world, BlockPos currentPos, BlockPos facingPos,
		CallbackInfoReturnable<BlockState> cir) {
		if (facing != Direction.DOWN || !(world instanceof Level level))
			return;
		BeltTunnelCapabilityInvalidator.invalidate(level, currentPos);
	}

	@Inject(method = "getTunnelState", at = @At("HEAD"), cancellable = true)
	private void createBiotech$getTunnelState(BlockGetter world, BlockPos pos,
		CallbackInfoReturnable<BlockState> cir) {
		BlockState belt = world.getBlockState(pos.below());
		if (!StandardItemBeltPortResolver.isHorizontalItemBelt(belt))
			return;

		BeltTunnelBlock self = (BeltTunnelBlock) (Object) this;
		Axis axis = belt.getValue(BlockStateProperties.HORIZONTAL_FACING).getAxis();
		BlockState state = self.defaultBlockState().setValue(BeltTunnelBlock.HORIZONTAL_AXIS, axis);
		Direction left = Direction.get(AxisDirection.POSITIVE, axis).getClockWise();
		boolean onLeft = createBiotech$isValidOutput(world, pos.below(), left);
		boolean onRight = createBiotech$isValidOutput(world, pos.below(), left.getOpposite());

		if (onLeft && onRight)
			state = state.setValue(BeltTunnelBlock.SHAPE, BeltTunnelBlock.Shape.CROSS);
		else if (onLeft)
			state = state.setValue(BeltTunnelBlock.SHAPE, BeltTunnelBlock.Shape.T_LEFT);
		else if (onRight)
			state = state.setValue(BeltTunnelBlock.SHAPE, BeltTunnelBlock.Shape.T_RIGHT);

		if (state.getValue(BeltTunnelBlock.SHAPE) == BeltTunnelBlock.Shape.STRAIGHT
			&& canHaveWindow(world, pos, axis))
			state = state.setValue(BeltTunnelBlock.SHAPE, BeltTunnelBlock.Shape.WINDOW);
		cir.setReturnValue(state);
	}

	@Inject(method = "hasValidOutput", at = @At("HEAD"), cancellable = true)
	private void createBiotech$hasValidOutput(BlockGetter world, BlockPos pos, Direction side,
		CallbackInfoReturnable<Boolean> cir) {
		BlockState neighbour = world.getBlockState(pos.relative(side));
		if (StandardItemBeltPortResolver.isHorizontalItemBelt(neighbour))
			cir.setReturnValue(createBiotech$isValidOutput(world, pos, side));
	}

	private static boolean createBiotech$isValidOutput(BlockGetter world, BlockPos pos, Direction side) {
		BlockPos outputPos = pos.relative(side);
		BlockState outputState = world.getBlockState(outputPos);
		if (StandardItemBeltPortResolver.isHorizontalItemBelt(outputState)) {
			StandardItemBeltPort port = StandardItemBeltPortResolver.getHorizontalPort(world, outputPos);
			return outputState.getValue(BlockStateProperties.HORIZONTAL_FACING).getAxis() == side.getAxis()
				&& port != null && port.createBiotech$addressesItemPort(side);
		}
		if (AllBlocks.BELT.has(outputState))
			return outputState.getValue(BeltBlock.HORIZONTAL_FACING).getAxis() == side.getAxis();
		DirectBeltInputBehaviour behaviour =
			BlockEntityBehaviour.get(world, outputPos, DirectBeltInputBehaviour.TYPE);
		return behaviour != null && behaviour.canInsertFromSide(side);
	}

}
